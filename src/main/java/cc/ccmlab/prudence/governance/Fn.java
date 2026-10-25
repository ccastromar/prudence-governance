package cc.ccmlab.prudence.governance;

/**
 * Spring authorities for the governance functions Prudence authorizes by (contract/governance,
 * section 2). The converter in SecurityConfig adds one per function the caller's token resolves to.
 */
public final class Fn {

  private Fn() {}

  public static final String PREFIX = "FN_";

  public static final String SYSTEM_DECLARE = PREFIX + "system.declare";
  public static final String SYSTEM_APPROVE = PREFIX + "system.approve";
  public static final String POLICY_AUTHOR = PREFIX + "policy.author";
  public static final String POLICY_APPROVE = PREFIX + "policy.approve";
  public static final String OVERSIGHT = PREFIX + "oversight";
  public static final String INCIDENT_HANDLE = PREFIX + "incident.handle";
  public static final String AUDIT = PREFIX + "audit";
  public static final String PLATFORM_ADMIN = PREFIX + "platform.admin";
  public static final String RUNTIME_DECIDE = PREFIX + "runtime.decide";
  public static final String RUNTIME_REPORT = PREFIX + "runtime.report";

  /** Every person function that exists in Prudence: holding any of them lets a person read. */
  public static final String[] PERSON = {
    SYSTEM_DECLARE,
    SYSTEM_APPROVE,
    POLICY_AUTHOR,
    POLICY_APPROVE,
    OVERSIGHT,
    INCIDENT_HANDLE,
    AUDIT,
    PLATFORM_ADMIN
  };
}
