# Conformance kit — prove your adapter follows the Prudence adapter contract

Writing an adapter for a framework we do not cover (Vercel AI SDK, Mastra, Spring AI, LangChain4j,
your in-house orchestrator)? The kit tells you whether it behaves like every other Prudence
adapter, before you go near a real Prudence. It plays Prudence and a scripted model itself, so it
needs **no Prudence, no model and no API key**, and it does not care what language your agent is
written in.

The contract it checks is [`contract/adapter/`](../../contract/adapter/README.md); the cases are
[`contract/adapter/vectors/adapter.json`](../../contract/adapter/vectors/adapter.json): ALLOW,
ALLOW with a failing report, APPROVAL_REQUIRED, DENY, ERROR, malformed ALLOWs, a sign-in page
instead of a decision, Prudence down, and a tool with no AI system.

## Your agent

Any command that:

1. reads `PRUDENCE_PDP_URL` (the kit's Prudence), `LLM_URL` (an OpenAI Chat Completions and
   Anthropic Messages base URL), `AI_SYSTEM_ID` (empty in one case) and `TOOL` (the tool's name);
2. builds an agent of your framework with **one tool named `TOOL`**, governed by your adapter with
   **no pipeline mapping** (so it is asked as `tool:<TOOL>` with `tools/call`), and asks it to use
   the tool, e.g. "Send the letter to customer C-1";
3. prints, as its last JSON line, `{"toolRan": <did the tool body run>, "answer": "<final answer>"}`.

The scripted model calls the first tool it is offered, filling required arguments from the schema,
and then answers with the tool result it got back, so a refusal your adapter returns ends up in
`answer`. The agents in [`../conformance/agents`](../conformance/agents) are complete examples, one
per framework.

## Run it

```bash
integrations/conformance-kit/kit.py -- python my_agent.py
integrations/conformance-kit/kit.py -- node dist/agent.js
integrations/conformance-kit/kit.py --case DENY --case "no AI system" -- ./gradlew -q run
integrations/conformance-kit/kit.py --list
```

Python 3.10+ for the kit itself, standard library only. It exits 1 if a case fails, with what was
expected and not seen: a request with the wrong `pipeline`/`action`/`aiSystemId`, a tool that ran
when it should not have, a model that was not told, a missing or unexpected execution report.

The kit does not authenticate and does not check identity (Prudence takes the caller from the
token; see the integration guide). For that, and for a real policy and registry, run your agent
against a real Prudence as [`../conformance`](../conformance) does for ours.

## The same kit for our adapters

Each agent in `../conformance/agents` drops its pipeline mapping when `PIPELINE` is empty, which is
what the kit sets, so any of them can be run through it from its own environment:

```bash
integrations/conformance-kit/kit.py -- integrations/conformance/.venv-crewai/bin/python -I \
  integrations/conformance/agents/crewai_agent.py
integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/mastra.js
integrations/conformance-kit/kit.py -- java -jar integrations/java/conformance/target/prudence-java-conformance.jar spring-ai
```

The TypeScript agents (Vercel AI SDK, OpenAI Agents JS, Mastra, LangGraph.js) are in
[`../typescript/conformance`](../typescript/conformance); build them first
(`npm install && npm run build` in `integrations/typescript`). The Java ones (Spring AI,
LangChain4j) are in [`../java/conformance`](../java/conformance) (`mvn install` in
`integrations/java`).

