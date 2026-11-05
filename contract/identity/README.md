# Prudence identity contract · v1

Who asks Prudence, who acts, and on whose behalf. This contract extends the Decision API
(`contract/decision/`) and the AuthZEN endpoints (`docs/api/decision-api-v1.yaml`) so that a
Policy Enforcement Point that is **not** the agent itself (an AI gateway, an MCP gateway) can ask
about the agent behind it, and so that the person an agent acts for can take part in a decision.

Status: **draft, partly served.** Prudence resolves `self` and `delegated` agents and `asserted`
persons, checks `speaks_for`, resolves and checks the AI system from the agents each system declares
(`GET`/`POST /api/registry/systems/{id}/agents`), applies the high-risk rule, and returns `identity`
and `reasonCode` (vectors 1–5, 8–11, 16). An undecidable system is recorded under the AI system id
`unresolved`. Recorded decisions keep the identities; evidence to Ernest and CloudEvents carries them
when they are more than the subject acting for itself, with the person as a pseudonym; execution
reports accept `agent`. Forwarded tokens are validated and `person_requires` applies (vectors 6–7b,
12–15); without a token issuer (the open development profile) a request that forwards a token is
refused with `400`, since nothing could validate it. Every vector is served; vectors 1 and 2 are the
behaviour before this contract and must keep passing.

| File | What it is |
| --- | --- |
| `README.md` | This contract (normative) |
| `vectors/resolution.json` | Request + token + policy data + registry → identities, decision and reason |
| `vectors/gate.json` | What a gate that asked about another agent does with each answer |

## 1. Three identities

| Identity | What it is | Where it comes from | Required |
| --- | --- | --- | --- |
| `caller` | The enforcement point asking Prudence (AOS, a gateway, an agent runtime with an adapter) | The authenticated access token, always. Unchanged from Decision API v1. | Yes |
| `agent` | The software that will act | The caller, unless the caller asks for another agent (section 3) | Yes |
| `onBehalfOf` | The person the agent acts for | A verified token only (section 4). Never the request body. | No |

**Compatibility.** A request that names no agent and no person resolves to `agent = caller` and no
`onBehalfOf`: exactly how Prudence decides today. AOS, the framework adapters and every existing
vector are unaffected.

Prudence never takes from the request **who the caller is**, **whom the caller may speak for**, or
**who the person is**. Each of these comes from a token Prudence verified or from the published policy.

## 2. Assurance

Every resolved identity carries how Prudence knows it:

| Value | Applies to | Meaning |
| --- | --- | --- |
| `self` | agent | The agent is the caller |
| `delegated` | agent | The caller named the agent and is allowed to speak for it (section 3.1) |
| `verified` | agent, person | Prudence validated a token that names it |
| `asserted` | person | The request named a person without a token. Recorded, **never grants anything** |

## 3. Agents

### 3.1 Delegated

The caller names the agent (`agent` in the Decision API, `context.agent.id` in AuthZEN). Prudence
accepts it only when the agent is listed in `speaks_for[caller]` in the published policy data.
Otherwise the decision is `DENY` with `reasonCode: agent_not_delegated`, recorded like any decision.

### 3.2 Verified

The caller forwards the agent's own access token (`agentToken`, `context.agent.token`). Prudence
validates it exactly like a token on its own API (signature against the issuer's keys, trusted
issuer, Prudence's API audience, expiry): a gateway forwards a token issued for Prudence, for
example one obtained by token exchange (RFC 8693), not the token the agent presented to the
gateway. The agent is named exactly as a caller would be (`email`, then `preferred_username`, then
`sub`, or the configured principal mode), so an agent has one id whether it asks Prudence itself
or a gateway asks for it. If the request also names an agent, it must be the same one, else
`DENY` with `reasonCode: subject_mismatch`. The agent must also
be in `speaks_for[caller]`: a valid token does not let any caller speak for its holder. A token that
fails validation is `DENY` with `reasonCode: agent_token_invalid`.

### 3.3 `speaks_for`

`speaks_for` lives in the policy data (`data.prudence.speaks_for`, a map from caller to agent ids),
next to the grants. It is versioned and digested with the bundle, published with the same
author ≠ approver rule, and cited in every decision through `policyVersion` and `policyDigest`.

With managed policy (Prudence publishes role rules as bundles), `speaks_for` is the deployment's
configuration instead: `prudence.identity.speaks-for` (`PRUDENCE_IDENTITY_SPEAKS_FOR`),
`caller=agent` pairs separated by commas. Which gateway stands in front of which agents is part of
the deployment and is reviewed with it. Managed rules grant by role: a `verified` agent is decided
by the roles in its own token, never the caller's; a `delegated` agent has no roles Prudence can
verify, so managed rules never grant it. A tool or model call is running its `tool:` or `model:`
pipeline (`pipeline.run`).

A caller that names an agent other than itself and has no `speaks_for` entry is refused like any
other undelegated agent: Prudence never falls back to deciding about the caller.

## 4. The person

`onBehalfOf` is `verified` only when it comes from a token Prudence validated:

- `onBehalfOfToken`: the person's own access token, forwarded; the person is its subject
  (`email`, then `preferred_username`, then `sub`, as for workloads);
- or an `act`/delegation token obtained by token exchange (RFC 8693) and sent as the agent token:
  the person is its subject and the agent its actor.

A person named in the body without such a token (`onBehalfOf` in the Decision API, a `subject` of
type `user` in AuthZEN) is recorded as `asserted`. Policy may read it for reporting, never to grant.

A verified person's governance functions (for example `ai.use:finance`) are resolved from their token
with the installation's governance model (`contract/governance/`), in every app of the suite and not
only Prudence (`ai.use` is an AOS function, and the agent may run anywhere), and handed to the policy. A
pipeline listed in `data.prudence.person_requires` (pipeline → function) is allowed only for a
`verified` person holding that function; otherwise `DENY` with `reasonCode: person_function_required`.

If the body names a person and a verified token names another, the decision is `DENY` with
`reasonCode: subject_mismatch`.

## 5. Agents of an AI system

Each AI system in the registry declares the agents that act for it (`agentIds`). An agent may act
for several systems.

| Request | Result |
| --- | --- |
| `aiSystemId` sent, agent declared in that system | That system |
| `aiSystemId` sent, agent not declared in it | `DENY`, `agent_not_in_system` |
| No `aiSystemId` (AuthZEN `tool` only), agent declared in exactly one system | That system |
| No `aiSystemId`, agent declared in several systems | `DENY`, `agent_system_ambiguous` |
| No `aiSystemId`, agent declared in none | `DENY`, `agent_unknown` |

The check applies whenever `agentAssurance` is `delegated` or `verified`. For `self` (today's
callers) it applies once the system declares agents; until then the system is open to its granted
callers, as today. `prudence.authzen.tool-systems` stays as a fallback for `self` callers and is
deprecated.

Adding an agent to an approved HIGH system is a change that needs review, as any change to an
approved HIGH system.

## 6. High-risk systems

For an AI system of tier HIGH, an agent named by another caller must be `verified`. A `delegated`
agent is `DENY` with `reasonCode: high_risk_requires_verified_agent`. (`self` is unaffected: the
caller's own token already verifies it.)

## 7. Order of checks

Prudence resolves identities before it decides anything, and the first failure is the answer:

1. caller from the access token (401 / 403 as today, nothing recorded);
2. a malformed request: `400`, nothing recorded (as today);
3. agent: token validation, `subject_mismatch`, `agent_not_delegated`;
4. person: `subject_mismatch`;
5. AI system: `agent_not_in_system`, `agent_system_ambiguous`, `agent_unknown`;
6. `high_risk_requires_verified_agent`;
7. the registry checks of today (unknown, not approved, expired, prohibited);
8. the policy engine, with the input of section 8.

Steps 3–8 produce a recorded decision.

## 8. Policy input

```json
{"input": {
  "subject": "claims-assistant",
  "caller": "tool-gateway",
  "agent": "claims-assistant",
  "agentAssurance": "delegated",
  "onBehalfOf": {"id": "alice", "assurance": "verified", "functions": ["ai.use:claims"]},
  "pipeline": "tool:send_customer_letter",
  "aiSystemId": "7d1e0c2a-3f4b-4a5c-9d6e-0f1a2b3c4d5e",
  "action": "run"
}}
```

`subject` equals `agent`, so rules written against `subject` keep their meaning. `onBehalfOf` is
absent when there is no person. The policy also requires, independently of the checks in Java,
that `caller == agent` or `agent in data.prudence.speaks_for[caller]`.

## 9. On the wire

### Decision API (`POST /api/policy/decisions`)

New optional request fields: `agent`, `agentToken`, `onBehalfOf`, `onBehalfOfToken`.
`aiSystemId` stays required here.

New optional response fields: `reasonCode` (section 10) and `identity`:

```json
{"decision": "ALLOW", "decisionId": "…",
 "identity": {"caller": "tool-gateway", "agent": "claims-assistant", "agentAssurance": "delegated",
              "onBehalfOf": {"id": "alice", "assurance": "verified"}}}
```

### AuthZEN (`/access/v1/evaluation[s]`), as the COAZ-MCP binding shapes it

```json
{"subject":  {"type": "user", "id": "alice"},
 "resource": {"type": "tool", "id": "send_customer_letter"},
 "action":   {"name": "tools/call"},
 "context":  {"agent": {"id": "claims-assistant", "token": "<optional JWT>"},
              "onBehalfOfToken": "<optional JWT>"}}
```

- `subject` of type `user` is the person (`asserted` unless a token verifies it); any other type
  is ignored as today.
- `context.agent` names the agent; without it the agent is the caller.
- `resource` may also be `{type: "model", id: <model>}`: a model call through an AI gateway,
  governed as the pipeline `model:<model>` of the AI system the agent acts for (section 5), so an
  unapproved or suspended system reaches no model and the policy says which models an agent uses.
- The answer's `context` adds `reasonCode`, `identity`, as above, and `aiSystemId`: the AI system
  the decision was recorded under, which a tool call may have left to Prudence. A gateway cites it
  when it reports the execution.

### Execution reports (`POST /api/executions`)

New optional field `agent`, resolved like a decision's agent (`self` if absent, `delegated` against
`speaks_for` of the reporting workload otherwise). Drift matches executions to decisions by agent
and AI system.

## 10. Reason codes

| `reasonCode` | When |
| --- | --- |
| `agent_not_delegated` | The caller named an agent it may not speak for |
| `agent_token_invalid` | The forwarded agent token failed validation |
| `person_token_invalid` | The forwarded on-behalf-of token failed validation |
| `subject_mismatch` | Body and verified token disagree about the agent or the person |
| `agent_not_in_system` | The agent is not declared in the AI system sent |
| `agent_system_ambiguous` | No AI system sent and the agent acts for several |
| `agent_unknown` | No AI system sent and the agent acts for none |
| `high_risk_requires_verified_agent` | HIGH system, agent only `delegated` |
| `person_function_required` | A rule needs a verified person's function that is missing |

Existing denials keep their `reason` text and gain no code until they are listed here.

## 11. Evidence

The recorded decision carries `caller`, `agent`, `agentAssurance`, `onBehalfOf` and its assurance.
In evidence that leaves Prudence, the person appears only as a pseudonym: the hex HMAC-SHA256 of the
person's identity under the installation's evidence pseudonym key, as for every person in evidence.
Workload identities (caller, agent) appear as each sink shows workloads today: in clear in Ernest's
record metadata (`subject` is the agent; `caller`, `agentAssurance`, `onBehalfOf`,
`onBehalfOfAssurance` are added), hashed in CloudEvents (`subjectHash` and an `identity` block).
The added fields appear only when the decision is more than the subject acting for itself, so
those records are byte for byte what they were.

## 12. Gates that name another agent

A gate that sends `agent`/`agentToken` must check that the answer's `identity.agent` is the agent it
asked about. An answer without `identity` comes from a Prudence that does not know this contract
and decided about the caller instead: the gate does not run the step. `vectors/gate.json` spells
this out; the rules of `contract/decision/` README section 2 still apply first.

A gate that forwards only `agentToken` names the agent from that token exactly as Prudence names a
caller (section 3.2, same principal mode) and compares the answer with that name. The LiteLLM
gateway (`integrations/litellm-gateway/identity_gate.py`) is the reference gate:
`check_gate_vectors.py` next to it runs every case of `vectors/gate.json`.
