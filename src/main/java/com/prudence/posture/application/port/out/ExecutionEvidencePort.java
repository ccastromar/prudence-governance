package com.prudence.posture.application.port.out;

import java.util.Map;

/// ACL over the evidence plane: how many runtime *execution* evidence blocks are tagged with
/// each AI system id. Lets oversight detect executions that happened outside governance.
public interface ExecutionEvidencePort {
    /// aiSystemId -> count of execution-stage evidence blocks referencing it. Empty on error
    /// (fail-closed: unknown is not evidence of a run).
    Map<String, Long> executionCountsBySystem();
}
