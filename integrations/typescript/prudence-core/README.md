# @chestertown/prudence-core

Framework-agnostic Prudence governance for TypeScript: the same rules and configuration as the
Python `prudence-governance`. The framework adapters build on it; use it directly for anything
they do not cover.

```ts
import { PrudenceGate, run } from "@chestertown/prudence-core";

const gate = new PrudenceGate();   // PRUDENCE_PDP_URL, PRUDENCE_PDP_TOKEN_URL, … from the environment
const decision = await gate.decide("my-runtime", "tool:send_customer_letter", aiSystemId, "tools/call");
if (decision.allowed) {
  void gate.reportExecution(decision, aiSystemId, "tool:send_customer_letter", { runtime: "my-runtime" });
  // run the step
} else {
  return decision.refusal();       // "Not permitted by governance (Prudence DENY): …"
}
```

- **`PrudenceGate.decide()`** never throws and fails closed: only HTTP 200 with `decision: ALLOW`
  and a `decisionId` is an ALLOW; a timeout, an unreachable Prudence, a token that cannot be
  obtained or a malformed answer is a DENY.
- **`reportExecution()`** (`POST /api/executions`) is best effort and never throws.
- **`ErnestEvidence.logExecution()`** seals the execution in Ernest with only a SHA-256 of the
  inputs (the same canonical JSON as the Python core, so the same inputs hash the same), retrying
  for a few seconds while Ernest does not know the system yet. Direct or through the
  event-ingestor (`ERNEST_INGESTOR_URL`). No qualified seals yet.
- **`run()`** gives every decision of one agent run the same `correlationId`.
- **`toolTarget(tool, pipelines)`** is how every adapter names a call: `["tool:<tool>", "tools/call"]`,
  or `[pipelines[tool], "run"]` when mapped.
- **`ToolGovernor`** is the shared adapter logic: ungoverned tools, the AI system per tool (none:
  refused without asking), the decision, the refusal, and the background report and evidence.

| Variable | Meaning | Default |
|---|---|---|
| `PRUDENCE_PDP_URL` | Prudence's base URL (`PRUDENCE_URL` is still read) | `http://127.0.0.1:8099` |
| `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`, `PRUDENCE_PDP_CLIENT_SECRET` | the runtime's client credentials (`scripts/provision-runtime.py`) | none: open development profile |
| `PRUDENCE_PDP_SCOPE` | scope asked for | `pdp.decide` |
| `ERNEST_URL`, `ERNEST_API_KEY` | Ernest, for execution evidence | `http://127.0.0.1:3001` |
| `ERNEST_INGESTOR_URL`, `ERNEST_INGESTOR_KEY` | Ernest's event-ingestor, for volume | direct |
