# TypeScript conformance agents

One agent per framework, each with one tool governed by its Prudence adapter, speaking the
[conformance kit](../../conformance-kit)'s protocol: they read `PRUDENCE_PDP_URL`, `LLM_URL`,
`AI_SYSTEM_ID`, `TOOL` and `PIPELINE` (empty: the tool is asked as `tool:<TOOL>`), ask the agent to
use the tool, and print `{"toolRan": …, "answer": …}`.

```bash
cd integrations/typescript && npm install && npm run build && cd ../..
for a in ai-sdk openai-agents mastra langgraph; do
  integrations/conformance-kit/kit.py -- node integrations/typescript/conformance/dist/$a.js
done
```

They are also the shortest complete example of each adapter in use.
