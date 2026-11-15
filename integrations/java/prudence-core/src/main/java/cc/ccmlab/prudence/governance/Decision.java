package cc.ccmlab.prudence.governance;

/** A Prudence decision, as the gate returns it (Decision API v1). */
public record Decision(String decision, String reason, String approver, String decisionId) {

  /** A refusal the gate makes itself, without a decision Prudence recorded. */
  public static Decision deny(String reason) {
    return new Decision("DENY", reason, null, null);
  }

  public boolean allowed() {
    return "ALLOW".equals(decision);
  }

  /**
   * What to tell the model when the call may not run: the verdict, why, and who must approve.
   * Adapters return it instead of the tool's result (contract/adapter/, section 4).
   */
  public String refusal() {
    StringBuilder text =
        new StringBuilder("Not permitted by governance (Prudence ").append(decision).append(')');
    if (reason != null && !reason.isEmpty()) text.append(": ").append(reason);
    if (approver != null && !approver.isEmpty())
      text.append(". Approval required from: ").append(approver);
    return text.append('.').toString();
  }
}
