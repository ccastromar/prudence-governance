/**
 * @chestertown/prudence-core — Prudence governance for TypeScript agent runtimes.
 *
 * The same core as the Python `prudence-governance`, with the same rules (contract/adapter/)
 * and the same environment variables: a fail-closed PDP gate, the execution report, Ernest
 * evidence (hashes only), one flow id per agent run, and the `ToolGovernor` the framework
 * adapters share.
 */
export { Decision, type Verdict } from "./decision.js";
export { M2MAuth, PrudenceGate, type PrudenceGateOptions } from "./gate.js";
export {
  ErnestEvidence,
  canonicalJson,
  sha256Json,
  type ErnestEvidenceOptions,
  type EvidenceResult,
} from "./evidence.js";
export {
  RUN_ACTION,
  TOOL_ACTION,
  checkCorrelationId,
  currentCorrelationId,
  run,
  toolTarget,
} from "./run.js";
export {
  ToolGovernor,
  type GovernOptions,
  type ToolVerdict,
} from "./governor.js";
