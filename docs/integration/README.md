# Connect your orchestrator to Prudence

Prudence decides whether a governed AI system may run. Your orchestrator, whether that is
LangChain, LangGraph, Google ADK, the OpenAI Agents SDK, Microsoft Agent Framework, Semantic
Kernel, Strands Agents, CrewAI, Agno, LlamaIndex, smolagents, the Claude Agent SDK, an MCP
gateway or something in-house, keeps running the work. It asks Prudence first and obeys the
answer.

This guide takes a runtime from nothing to governed in five steps. AOS, the Chestertown
reference runtime, does exactly this; nothing here is AOS-specific.

```
  your runtime                         Prudence                       your audit store
  ────────────                         ────────                       ────────────────
  1 ask ──── POST /api/policy/decisions ──►  decide (registry + policy)
         ◄── ALLOW | DENY | APPROVAL_REQUIRED + decisionId                (evidence) ──►
  2 run only on ALLOW
  3 report ─ POST /api/executions ───────►  drift: who ran what, under which decision
```

## The rules

These are the contract. A runtime that breaks one is not governed, whatever else it does.

1. **Fail closed.** Run only on `ALLOW`. Treat `DENY`, `APPROVAL_REQUIRED`, any error status,
   a timeout and an unreadable body as "do not run".
2. **Ask every time.** Ask before each governed run. Do not cache decisions: an approval can
   expire or be revoked between two runs.
3. **Report what you ran.** After a governed run starts, report it with the `decisionId` that
   allowed it. Reporting is best effort and must never block or fail the run.
4. **Identity comes from the token.** Prudence takes the caller from the access token. The
   `subject` you send is ignored, so there is nothing to spoof.
5. **Send identifiers, never content.** Prudence needs ids, names and times. Do not put
   prompts, inputs or outputs in any field.

A framework adapter follows these through the [adapter contract](../../contract/adapter/README.md):
it asks before every tool call as the pipeline `tool:<name>` with the action `tools/call` (the same
grant an AI or MCP gateway uses), tells the model why when it may not run, and can prove it with the
[conformance kit](../../integrations/conformance-kit), with no Prudence and no model.

## Step 1: register the runtime

Each runtime gets its own machine identity (an OAuth2 client) and a grant listing the
pipelines it may run. One script does both:

```bash
KEYCLOAK_URL=https://sso.example.com KEYCLOAK_ADMIN_PASSWORD=… \
  scripts/provision-runtime.py --name claims-langgraph \
    --pipeline claims_triage --pipeline claims_letter
```

It creates (or tightens) the Keycloak client `claims-langgraph-pdp` with:

- a service account, and no browser login;
- the scopes `pdp.decide` (ask) and `execution.report` (report);
- a `preferred_username` mapper, so the token names the runtime
  (`service-account-claims-langgraph-pdp`).

It then grants that name a role in `policy/opa/data.json` that may `run` the listed pipelines.
For an agent whose adapter governs each tool call, grant the tools instead (or as well):
`--tool send_customer_letter` grants `tools/call` on `tool:send_customer_letter`.
The client secret goes to a `0600` file, never to the terminal.

Commit `data.json` afterwards. OPA reloads the policy folder it watches. Run the script again
with more `--pipeline` flags to widen the grant; `--dry-run` shows the plan without changing
anything.

> **Why the mapper matters.** Without it the token carries only an opaque `sub`, Prudence uses
> that as the caller, OPA does not know it, and every decision is `DENY` ("subject has no
> roles"). This is the most common integration mistake.

## Step 2: register and approve the AI system

In the Prudence console, **AI system registry**: register the system your pipeline uses, with
its risk tier and owner, and take it through **Approvals**. A high-risk system needs two
different reviewers.

Your runtime sends the system's id as `aiSystemId`. Until the system is approved, Prudence
answers `APPROVAL_REQUIRED` or `DENY`. That is the governance working, not an error.

## Step 3: ask before running

Get a token with the client credentials, then ask. Prudence answers with the HTTP status and
a body:

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=claims-langgraph-pdp \
  -d client_secret="$(cat claims-langgraph-pdp.secret)" -d scope=pdp.decide \
  "$KEYCLOAK_URL/realms/prudence/protocol/openid-connect/token" | jq -r .access_token)

curl -s -X POST https://prudence.example.com/api/policy/decisions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"pipeline":"claims_triage","aiSystemId":"68bf42c1-…","action":"run"}'
```

| Status | `decision` | What your runtime does |
|---|---|---|
| 200 | `ALLOW` | Run. Keep `decisionId` for step 4. |
| 202 | `APPROVAL_REQUIRED` | Do not run. `approver` names the role that must approve in Prudence. |
| 403 | `DENY` | Do not run. `reason` says why. |
| anything else | — | Do not run. |

Every answer carries `decisionId`, and Prudence has already recorded the decision as evidence
by the time you receive it.

**Prefer a standard?** The same decision is available as
[OpenID AuthZEN 1.0](https://openid.net/specs/authorization-api-1_0.html):
`POST /access/v1/evaluation`, with
`resource: {type: "pipeline", id: <pipeline>, properties: {aiSystemId}}` and
`action: {name: "run"}`. AuthZEN answers `true` or `false`. Prudence answers `true` only for
`ALLOW`, so a generic AuthZEN client fails closed; `context` carries the full verdict and the
`decisionId`. Discovery is at `/.well-known/authzen-configuration`.

These endpoints are independent of AOS and work with an orchestrator running outside the
Prudence deployment. Server-to-server integrations use the deployment's OIDC issuer and a
client-credentials token; browser-based integrations additionally need their exact web origin
listed in `PRUDENCE_CORS_ORIGINS`. Do not expose workload credentials to a browser.

The full contract is in [`docs/api/decision-api-v1.yaml`](../api/decision-api-v1.yaml)
(OpenAPI 3.1).

### Governing MCP tool calls through a gateway

If your agents call tools over MCP through a gateway that speaks AuthZEN (for example
Traefik Hub's MCP gateway), the gateway can ask Prudence before each tool call. It sends the
tool as the resource:

```json
{ "subject":  { "type": "agent", "id": "claims-assistant" },
  "resource": { "type": "tool", "id": "send_customer_letter" },
  "action":   { "name": "tools/call" } }
```

Prudence checks it as the pipeline `tool:send_customer_letter` of the AI system the tool acts
for. Tell Prudence which system that is, either in the request
(`resource.properties.aiSystemId`) or once, in its configuration:

```yaml
prudence:
  authzen:
    tool-systems:
      "[send_customer_letter]": 68bf42c1-…
```

A tool mapped to no AI system is refused, never guessed. Register the gateway like any other
runtime and grant it the tools it may call:

```bash
scripts/provision-runtime.py --name mcp-gateway --tool send_customer_letter
```

Configure the gateway's action name as `tools/call`; that is what the grant allows, and the same
grant the framework adapters and the AI gateway use, so a tool is granted once however it is
reached.

## Step 4: report the execution

When a governed run actually starts, tell Prudence, citing the decision:

```bash
curl -s -X POST https://prudence.example.com/api/executions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"runtime":"claims-langgraph","executionId":"run-7f3a9c",
       "decisionId":"4f1c2a5e-…","aiSystemId":"68bf42c1-…",
       "pipeline":"claims_triage","startedAt":"2026-10-21T10:15:30.120Z"}'
```

- It is idempotent on `(runtime, executionId)`. Retry freely; a retry with different facts is
  rejected.
- Prudence records every report, whatever decision it cites. An execution after a `DENY`, or
  with no decision at all, is exactly what the **drift** check must see.
- With Ernest, drift also compares your reports against the execution evidence there, so seal
  each execution in Ernest as well: the adapters do it when given `evidence=ErnestEvidence(…)`
  (`stage: execution` under the AI system's id). A run reported to Prudence with no evidence in
  Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`. Without Ernest, your reports are
  drift's only source, so do not skip this step.

## Step 5 (optional): keep the evidence in your own store

By default Prudence seals its evidence in **Ernest**, which adds inclusion proofs, an external
time anchor and read-back. If your company must keep governance evidence in its own audit
store (a SIEM, an object-locked bucket, an in-house ledger), point Prudence at it instead:

```properties
prudence.evidence.sink=cloudevents
# Kafka (default transport)
prudence.evidence.cloudevents.transport=kafka
prudence.evidence.cloudevents.kafka-bootstrap-servers=broker-1:9093
prudence.evidence.cloudevents.topic=prudence.evidence
# or an HTTPS webhook
#prudence.evidence.cloudevents.transport=webhook
#prudence.evidence.cloudevents.webhook-url=https://siem.example.com/intake/prudence
#prudence.evidence.cloudevents.webhook-token=…
```

Prudence then sends CloudEvents 1.0: decisions, reviews, register records and attestations.
The schema is in [`docs/api/evidence-events-v1.yaml`](../api/evidence-events-v1.yaml)
(AsyncAPI 3.0). Delivery is at least once; deduplicate on the event `id`.

This store gives **custody only**. Prudence says so in Posture and in attestations ("held in an
external store; not independently verifiable"), and never claims a seal it does not have.

## Ready-made adapters

| Runtime | Package | Hook |
|---|---|---|
| Any Python code | [`integrations/prudence-core`](../../integrations/prudence-core) | `PrudenceGate.decide()` and `report_execution()` |
| LangGraph | [`integrations/langgraph`](../../integrations/langgraph) | a governed node and a routing gate |
| LangChain agents (`create_agent`) | [`integrations/langchain`](../../integrations/langchain) | agent middleware: every tool call |
| Pydantic AI | [`integrations/pydantic-ai`](../../integrations/pydantic-ai) | a `@governed` decorator for a tool |
| Google ADK | [`integrations/google-adk`](../../integrations/google-adk) | a runner plugin: every tool of every agent |
| OpenAI Agents SDK | [`integrations/openai-agents`](../../integrations/openai-agents) | a tool input guardrail |
| Microsoft Agent Framework | [`integrations/agent-framework`](../../integrations/agent-framework) | function middleware |
| Strands Agents | [`integrations/strands-agents`](../../integrations/strands-agents) | a hook provider: every tool call |
| CrewAI | [`integrations/crewai`](../../integrations/crewai) | tool call hooks: every crew in the process |
| Agno | [`integrations/agno`](../../integrations/agno) | a tool hook |
| LlamaIndex | [`integrations/llamaindex`](../../integrations/llamaindex) | a wrapper per tool |
| smolagents | [`integrations/smolagents`](../../integrations/smolagents) | a wrapper per tool |
| Claude Agent SDK | [`integrations/claude-agent-sdk`](../../integrations/claude-agent-sdk) | a PreToolUse hook: built-in and MCP tools |
| Any TypeScript code | [`integrations/typescript/prudence-core`](../../integrations/typescript/prudence-core) | `PrudenceGate.decide()` and `reportExecution()` |
| Vercel AI SDK | [`integrations/typescript/ai-sdk`](../../integrations/typescript/ai-sdk) | `governTools()`: a wrapper per tool |
| OpenAI Agents SDK (JS) | [`integrations/typescript/openai-agents`](../../integrations/typescript/openai-agents) | a tool input guardrail |
| Mastra | [`integrations/typescript/mastra`](../../integrations/typescript/mastra) | agent tool hooks: every tool of the agent |
| LangGraph.js | [`integrations/typescript/langgraph`](../../integrations/typescript/langgraph) | governed tools, or a governed node and a routing gate |
| Any Java code | [`integrations/java/prudence-core`](../../integrations/java) | `PrudenceGate.decide()` and `reportExecution()` |
| Spring AI | [`integrations/java/spring-ai`](../../integrations/java) | governed `ToolCallback`s and `ToolCallbackProvider`s |
| LangChain4j | [`integrations/java/langchain4j`](../../integrations/java) | governed `ToolExecutor`s and `ToolProvider`s |
| Go | AOS `internal/governance` | the reference implementation |
| MCP gateway (AuthZEN) | none needed | `resource.type: tool`, see step 3 |

Each adapter does the same two things at its framework's natural hook point: ask before the
step, report after it starts.

**Install** from a checkout until the packages are published:
`pip install -e integrations/prudence-core -e integrations/<framework>`; for TypeScript,
`npm install && npm run build` in `integrations/typescript`; for Java, `mvn install` in
`integrations/java` (see their READMEs). Every adapter depends
on its language's core and reads the same configuration:

| Variable | Meaning | Default |
|---|---|---|
| `PRUDENCE_PDP_URL` | Prudence's base URL (`PRUDENCE_URL` is still read) | `http://127.0.0.1:8099` |
| `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`, `PRUDENCE_PDP_CLIENT_SECRET` | the runtime's client credentials (step 1); all three, or none in the open development profile | none |
| `PRUDENCE_PDP_SCOPE` | scope asked for | `pdp.decide` |
| `ERNEST_URL`, `ERNEST_API_KEY` | Ernest, for execution evidence | `http://127.0.0.1:3001` |
| `ERNEST_INGESTOR_URL`, `ERNEST_INGESTOR_KEY` | Ernest's event-ingestor, for volume | direct |

**One flow per agent run:** wrap the run in `with prudence_governance.run():` (or pass your own
id, `run("ticket-4711")`) and every decision inside carries the same `correlationId`, so the
run's tool calls read as one flow in Prudence and in Ernest. [`integrations/conformance`](../../integrations/conformance)
checks that they do, with real agents of each framework against a real Prudence. For anything else, steps 3 and 4 are two HTTP calls.

## When it says no

| What you see | Usual cause | Fix |
|---|---|---|
| `DENY`, reason "denied by policy engine" or "subject has no roles" | The token has no `preferred_username`, or the runtime has no grant for this pipeline | Re-run `provision-runtime.py` with the pipeline; check the token's `preferred_username` |
| 403 with no decision body | The token lacks `pdp.decide` | The client must have the scope as a default scope (the script does this) |
| 401 | No token, an expired token, or a token from another realm | Check the token URL and realm |
| `APPROVAL_REQUIRED` | The AI system is not approved yet, or its approval expired | Approve it in Prudence (two reviewers for high risk) |
| `DENY`, "prohibited AI system" | The system is classified as a prohibited practice | By design; it never runs |
| A run you reported shows as a drift **VIOLATION** | It ran while the system had no approval in force, or no `ALLOW` covers it: it cites no decision, an unknown one, one about another system, or a verdict other than `ALLOW` | Investigate: this is the alarm working |

---

Prudence helps you put the EU AI Act into practice. It is not legal advice or a certification
of compliance.
