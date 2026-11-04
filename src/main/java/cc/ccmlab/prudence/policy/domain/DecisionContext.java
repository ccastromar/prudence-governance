package cc.ccmlab.prudence.policy.domain;

/// What AOS asks Prudence at a gate: may this subject run this pipeline against this AI
/// system, for this action/context? `subject` is the agent that will act; `identity` adds who
/// asked and for whom (`contract/identity/`), and is the subject acting for itself when absent.
public record DecisionContext(
    String subject,
    String pipeline,
    String aiSystemId,
    String action,
    String policyVersion,
    String policyHash,
    String systemVersion,
    String modelHash,
    String causationId,
    String correlationId,
    Long systemSequence,
    String aiSystemName,
    String riskTier,
    String decisionId,
    DecisionIdentity identity) {

  public DecisionContext {
    if (identity == null) identity = DecisionIdentity.self(subject);
  }

  /// Without an identity: the subject acts for itself.
  public DecisionContext(
      String subject,
      String pipeline,
      String aiSystemId,
      String action,
      String policyVersion,
      String policyHash,
      String systemVersion,
      String modelHash,
      String causationId,
      String correlationId,
      Long systemSequence,
      String aiSystemName,
      String riskTier,
      String decisionId) {
    this(
        subject,
        pipeline,
        aiSystemId,
        action,
        policyVersion,
        policyHash,
        systemVersion,
        modelHash,
        causationId,
        correlationId,
        systemSequence,
        aiSystemName,
        riskTier,
        decisionId,
        null);
  }

  public DecisionContext withAiSystemId(String id) {
    return new DecisionContext(
        subject,
        pipeline,
        id,
        action,
        policyVersion,
        policyHash,
        systemVersion,
        modelHash,
        causationId,
        correlationId,
        systemSequence,
        aiSystemName,
        riskTier,
        decisionId,
        identity);
  }

  public DecisionContext withPolicy(String version, String digest) {
    return new DecisionContext(
        subject,
        pipeline,
        aiSystemId,
        action,
        version,
        digest,
        systemVersion,
        modelHash,
        causationId,
        correlationId,
        systemSequence,
        aiSystemName,
        riskTier,
        decisionId,
        identity);
  }

  /// Without a decision id: the outbox then assigns one.
  public DecisionContext(
      String subject,
      String pipeline,
      String aiSystemId,
      String action,
      String policyVersion,
      String policyHash,
      String systemVersion,
      String modelHash,
      String causationId,
      String correlationId,
      Long systemSequence,
      String aiSystemName,
      String riskTier) {
    this(
        subject,
        pipeline,
        aiSystemId,
        action,
        policyVersion,
        policyHash,
        systemVersion,
        modelHash,
        causationId,
        correlationId,
        systemSequence,
        aiSystemName,
        riskTier,
        null);
  }

  /// Without the system's identity (name, risk tier): callers that only know the request.
  public DecisionContext(
      String subject,
      String pipeline,
      String aiSystemId,
      String action,
      String policyVersion,
      String policyHash,
      String systemVersion,
      String modelHash,
      String causationId,
      String correlationId,
      Long systemSequence) {
    this(
        subject,
        pipeline,
        aiSystemId,
        action,
        policyVersion,
        policyHash,
        systemVersion,
        modelHash,
        causationId,
        correlationId,
        systemSequence,
        null,
        null);
  }

  public DecisionContext(
      String subject,
      String pipeline,
      String aiSystemId,
      String action,
      String policyVersion,
      String policyHash,
      String systemVersion,
      String modelHash) {
    this(
        subject,
        pipeline,
        aiSystemId,
        action,
        policyVersion,
        policyHash,
        systemVersion,
        modelHash,
        null,
        null,
        null);
  }

  public DecisionContext(String subject, String pipeline, String aiSystemId, String action) {
    this(
        subject,
        pipeline,
        aiSystemId,
        action,
        "unversioned",
        "unknown",
        "unknown",
        "unknown",
        null,
        null,
        null);
  }
}
