package cc.ccmlab.prudence.config;

/**
 * Prudence's own role vocabulary. It is the contract of this application and does not depend on any
 * identity provider: each deployment maps its IdP's claim values onto these names with {@code
 * prudence.identity.role-mapping}.
 */
public final class PrudenceRoles {
  public static final String READER = "reader";
  public static final String AUTHOR = "author";
  public static final String SYSTEM_APPROVER = "system-approver";
  public static final String POLICY_REVIEWER = "policy-reviewer";
  public static final String ADMIN = "admin";

  /** Any Prudence role: enough to read the registers. */
  public static final String[] ALL = {READER, AUTHOR, SYSTEM_APPROVER, POLICY_REVIEWER, ADMIN};

  private PrudenceRoles() {}
}
