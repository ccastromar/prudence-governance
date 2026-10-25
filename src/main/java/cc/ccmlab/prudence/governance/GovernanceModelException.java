package cc.ccmlab.prudence.governance;

/** A governance model that breaks a contract rule; {@link #code} is the contract's error code. */
public final class GovernanceModelException extends RuntimeException {

  private final String code;

  public GovernanceModelException(String code, String detail) {
    super(code + ": " + detail);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
