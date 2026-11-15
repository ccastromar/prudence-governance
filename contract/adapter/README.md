# Prudence adapter contract · v1

What a framework adapter does so that an agent framework (LangGraph, CrewAI, the OpenAI Agents
SDK, Vercel AI SDK, Spring AI, anything) is governed by Prudence: where it asks, what it asks, what
it does with the answer, what it reports, and how it is configured. The HTTP calls underneath are
the Decision API (`contract/decision/`); this contract says how an adapter uses them, so that every
adapter, in any language, behaves the same and a grant written once works for all of them.

| File | What it is |
| --- | --- |
| `README.md` | This contract (normative) |
| `vectors/adapter.json` | A tool call, Prudence's answer → what the adapter asks, whether the tool runs, what the model is told, what is reported |

Who runs the vectors:

| Side | Implementation | How |
| --- | --- | --- |
| Adapters | `integrations/*` (the twelve Python adapters) | `integrations/conformance-kit/kit.py`, one agent per framework |
| Adapters | `integrations/typescript/*` (Vercel AI SDK, OpenAI Agents JS, Mastra, LangGraph.js) | the same kit, with `integrations/typescript/conformance` |
| Adapters | `integrations/java/*` (Spring AI, LangChain4j) | the same kit, with `integrations/java/conformance` |
| Any new adapter | yours | the same kit, with your agent as the command (section 8) |

An adapter conforms when it passes every case. The kit needs no Prudence, no model and no API key:
it plays Prudence and a scripted model itself.

## 1. Versions

`prudence.adapter/v1`. v1 only grows: new optional configuration, new vectors that spell out
behaviour v1 already had. Anything that would change what an existing vector expects is v2.

## 2. Where the gate sits

1. **Before every governed tool call.** The adapter sits at the framework's own hook in front of a
   tool (a before-tool hook, a middleware, a guardrail, a plugin) so that it governs every tool of
   every agent it is attached to. Where the framework has no such hook, the adapter wraps each tool
   and says so: a tool left unwrapped is not governed.
2. **Ungoverned tools are named, never guessed.** The integrator lists tools that need no decision
   (`ungoverned`, read-only helpers). Every other tool is governed, including tools added later.
3. **A node or step, not a tool, may be the unit** (a graph node, a pipeline step). The same rules
   apply with the integrator's pipeline name (section 3).

## 3. What it asks

One `POST /api/policy/decisions` per governed call, never cached (an approval can end between two
calls):

| Field | Value |
| --- | --- |
| `pipeline` | `tool:<tool name>` by default; a pipeline the integrator mapped the tool or node to |
| `action` | `tools/call` for `tool:<name>`; `run` for a pipeline of the integrator's |
| `aiSystemId` | the AI system the agent acts as: one per agent, or one per tool |
| `subject` | the runtime's name (ignored by a secured Prudence: the caller comes from the token) |
| `correlationId` | the agent run's flow id, when there is one (section 5); otherwise absent |

`tool:<name>` with `tools/call` is the grant an AI gateway and an MCP gateway use too, so one grant
covers a tool however it is reached. A tool with **no AI system** is refused **without asking**:
there is nothing Prudence could have approved, and the adapter never picks a system for it.

## 4. What it does with the answer

1. **Runs the tool only when the Decision API gate runs** (`contract/decision/`, section 2): status
   200, a JSON object, `decision` exactly `ALLOW`, a non-blank `decisionId`. Anything else, a
   timeout, a refused connection, a token that cannot be obtained, stops the tool.
2. **On a refusal the model is told why.** The tool's result is replaced by a refusal that names the
   verdict (`DENY`, `APPROVAL_REQUIRED`, `ERROR`), the `reason` and, for `APPROVAL_REQUIRED`, the
   `approver`. The model never receives the tool's output, and the refusal is something the model can
   read (a tool result or a tool error), not a crash of the run. The core's `Decision.refusal()`
   writes it: `Not permitted by governance (Prudence DENY): <reason>.`
3. **A hook never fails open.** Where the framework would run the tool anyway after the hook raised
   (CrewAI does), the hook catches its own failures and refuses. An adapter may raise to stop the
   run only where the framework then does not run the tool, and only if the integrator asked for it.

## 5. The flow of one agent run

An integrator may wrap an agent run in a flow (`prudence_governance.run()` in Python): every
decision inside it carries the same `correlationId`. It must be a plain token
(`[A-Za-z0-9._:-]`, at most 128 characters); the adapter checks it when the flow starts rather than
let Prudence refuse every decision of the run. Reports and execution evidence cite their decision,
and join the flow through it.

## 6. After an ALLOW

1. **Report the execution**: `POST /api/executions` with the `decisionId`, the AI system, the same
   `pipeline` it asked about, the runtime's name and a fresh `executionId` for this call
   (`contract/decision/`, section 3).
2. **Seal it in Ernest** when the integrator gave an evidence client: `stage: execution` under the
   AI system's id, the same `executionId` as `correlationId`, the `decisionId`, and only a SHA-256
   of the inputs.
3. **Both are best effort.** Neither blocks nor fails the tool, whatever Prudence or Ernest
   answers; a failed report is not retried in the tool's path.
4. **The decision is not recorded again.** Prudence recorded it before answering.
5. **No content reaches Prudence**: ids, names and times only; never prompts, inputs or outputs.

## 7. Configuration

The same names in every language, read when the integrator passes nothing explicitly. They are
the names AOS uses too.

| Variable | Meaning | Default |
| --- | --- | --- |
| `PRUDENCE_PDP_URL` | Prudence's base URL (`PRUDENCE_URL` is still read) | `http://127.0.0.1:8099` |
| `PRUDENCE_PDP_TOKEN_URL` | the IdP's token endpoint; with the two below, the adapter authenticates | (none: open development profile) |
| `PRUDENCE_PDP_CLIENT_ID` | the runtime's client (`scripts/provision-runtime.py` creates it) | |
| `PRUDENCE_PDP_CLIENT_SECRET` | its secret | |
| `PRUDENCE_PDP_SCOPE` | scope asked for | `pdp.decide` |
| `ERNEST_URL`, `ERNEST_API_KEY` | Ernest, for execution evidence | `http://127.0.0.1:3001` |
| `ERNEST_INGESTOR_URL`, `ERNEST_INGESTOR_KEY` | Ernest's event-ingestor, for volume | (direct) |

A token that cannot be obtained stops the tool, like any other failure (section 4).

## 8. Proving it: the conformance kit

`integrations/conformance-kit/kit.py` plays Prudence (from `vectors/adapter.json`) and a scripted
model, and runs **your agent** once per case. Your agent is any command that:

- reads `PRUDENCE_PDP_URL`, `LLM_URL` (an OpenAI Chat Completions or Anthropic Messages base URL),
  `AI_SYSTEM_ID` (empty for the case without a system) and `TOOL` (the tool's name);
- gives an agent of your framework one tool called `TOOL`, governed by your adapter with no pipeline
  mapping, and asks it to use the tool;
- prints, as its last JSON line, `{"toolRan": <bool>, "answer": "<the agent's final answer>"}`.

The kit checks what your adapter asked, whether the tool ran, whether the model was told, and what
was reported. Python, TypeScript, Java: the kit does not care, it only runs the command.
