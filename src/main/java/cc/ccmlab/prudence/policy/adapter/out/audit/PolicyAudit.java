package cc.ccmlab.prudence.policy.adapter.out.audit;

import org.springframework.stereotype.Component;

/** Joined to the caller's transaction: audit cannot disappear if a policy change commits. */
@Component
public class PolicyAudit {
  private final PolicyAuditRepository repository;

  public PolicyAudit(PolicyAuditRepository repository) {
    this.repository = repository;
  }

  public void record(String aggregate, String action, String actor, String details) {
    repository.save(new PolicyAuditEntity(aggregate, action, actor, details));
  }
}
