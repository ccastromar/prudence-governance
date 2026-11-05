# Prudence behind an AI gateway (LiteLLM)

The framework adapters govern an agent from inside its code. This governs it from outside: the
agent has no adapter, its tools run on an MCP server, and an AI gateway between the agent and both
its model and its tools asks Prudence before every model call and every tool call. Nothing in the agent changes but where it connects.

```
agent (no adapter) ──► LiteLLM ──► model
                          │  └────► MCP tool server
                          └── before each model call and tool call: AuthZEN ──► Prudence
```

| File | What it is |
| --- | --- |
| `prudence_guardrail.py` | A LiteLLM custom guardrail: the policy enforcement point |
| `identity_gate.py` | Whether an answer lets the call run (`contract/identity/` section 12), standard library only |
| `check_gate_vectors.py` | Runs `contract/identity/vectors/gate.json` against `identity_gate.py`; no LiteLLM or Prudence needed |
| `config.yaml` | LiteLLM with the model, the tool server and the guardrail |
| `mock_tools.py` | The example agents' tools as an MCP server, logging every call that reaches them |
| `run.py` | End to end with the twelve example agents in gateway mode |
| `../conformance/agents/gateway_mode.py` | What the example agents need to run behind the gateway (no governance in it) |

## How a call is decided

1. The agent presents **its own access token, issued for Prudence**: as `Authorization: Bearer`
   on the gateway's MCP endpoint (the gateway key goes in `x-litellm-api-key`), and in
   `x-prudence-agent-token` on model calls (where `Authorization` is the gateway key).
2. Before the call, the guardrail sends Prudence an AuthZEN evaluation: resource
   `{type: tool, id: <tool>}` with action `tools/call`, or `{type: model, id: <model>}` with
   action `invoke`, and the agent's token in `context.agent.token`. It authenticates as the
   gateway itself. It sends names, never the tool's arguments or the prompt.
3. Prudence validates the agent's token, checks that the gateway may speak for that agent
   (`speaks_for`), takes the AI system the agent is declared in, and decides
   (`contract/identity/`).
4. The call goes through only on an ALLOW about that agent with `agentAssurance: verified`:
   the guardrail names the agent from its token as Prudence does (`PRUDENCE_PRINCIPAL_MODE`,
   default `legacy`) and checks the answer's `identity.agent` is that name.
   Anything else blocks it: a denial, no agent token, Prudence unreachable or slow, an answer
   about another agent (`403` from the gateway, the reason in the message).
5. After the tool ran, the guardrail reports the execution (`POST /api/executions`, runtime
   `litellm-gateway`, with the decision, the AI system Prudence resolved and the agent), tied to
   the decision by LiteLLM's call id. Prudence's drift then counts it as a governed execution.
   The report never blocks the call; the gateway's identity needs `execution.report`.

On model calls the guardrail removes `x-prudence-agent-token` from the request once it has read
it; LiteLLM's own debug logging masks the value too (checked with `LITELLM_LOG=DEBUG`: the token
never appears).

The agent token makes the agent `verified`, which a high-risk system requires behind a gateway.
A token the agent presented to the gateway for the gateway is not enough: Prudence checks its own
audience, so the agent forwards one issued for Prudence (for example by token exchange).

## The example agents in gateway mode

Every example agent in `../conformance/agents` runs with `GOVERNANCE_MODE=gateway`: no adapter,
its model client sends the agent's token in `x-prudence-agent-token` (each framework's own option
for extra headers), and its tool runs on the MCP server through the gateway. The OpenAI Agents SDK
example uses the SDK's own MCP client; the others keep their in-code tool, whose body calls the MCP
tool through `gateway_mode.call_tool` (a small streamable-HTTP JSON-RPC client), so their example
code is unchanged apart from that body and no extra packages go into their environments. The
Claude Agent SDK reaches the gateway's Anthropic Messages endpoint, with the token in
`ANTHROPIC_CUSTOM_HEADERS`; `config.yaml` sends those calls to the OpenAI-compatible model over
`chat/completions`.

The demo runner and the conformance run start agents with `python -I`; `gateway_mode` is imported
only in gateway mode, and `run.py` starts agents without `-I` from their own directory.

## The console's governed demos

The demo runner (`../demo-runner`) offers each demo in gateway mode too when
`PRUDENCE_DEMO_GATEWAY_URL` is set; the console then lets a run be governed by the adapter or by
the gateway. The runner gets the agent's token with its PDP client and sends the run's AI system
in `x-prudence-ai-system`; the guardrail passes it to Prudence, which checks that the system
declares the agent. `mock_tools.py` serves every demo's tool. `policy/opa/data.json` grants the
demos' tools and the scripted model to `framework-demo-runner` and lets
`service-account-ai-gateway` speak for the runner.

## What Prudence needs

- The AI system declares the agent: `POST /api/registry/systems/{id}/agents`.
- The policy grants the agent its models (`model:<name>`, action `invoke`) and tools
  (`tool:<name>`, action `tools/call`), and lists the agent under the gateway's identity in
  `speaks_for`.
- The gateway has a workload identity with `pdp.decide`.

## Run it

LiteLLM's proxy, pinned. Versions 1.82.7 and 1.82.8 on PyPI were compromised (March 2026) and
yanked; pin an exact version and, for anything beyond a local run, install with hashes
(`uv pip compile --generate-hashes`).

```bash
python3.13 -m venv .venv && .venv/bin/pip install 'litellm[proxy]==1.106.1'
```

Then, with a Prudence set up as above, the two tokens at hand and the conformance environments
(`../conformance/.venv-<framework>`, see its README):

```bash
PRUDENCE_URL=… PRUDENCE_TOKEN=… AGENT_TOKEN=… LITELLM=.venv/bin/litellm .venv/bin/python run.py
```

Per framework (`FRAMEWORKS=openai,langchain…` for a subset), then once:

| Case | Expected |
| --- | --- |
| allowed model and tool, the agent's own token | the tool runs |
| tool the policy does not grant | it does not run; the gateway says `tool delete_customer: denied…` |
| model the policy does not grant | the model call is refused, so no tool runs |
| no agent token | the first model call is refused before Prudence is asked |
| Prudence unreachable | the first model call is refused (fail closed) |
| with `READER_TOKEN` and `AI_SYSTEM_ID` | one execution report per framework from `litellm-gateway`, citing the ALLOW, under the agent |

## Not covered yet

- **Native MCP clients** for the eleven frameworks that call the tool through `gateway_mode`;
  each has one, but it means more packages in each environment.
- **No bypass** is a network property: in a deployment the agent must not reach the tool server
  except through the gateway. A local run cannot show that.
- LiteLLM may also call the guardrail on tool listing; it allows listing and decides each call.
