package cc.ccmlab.prudence.policy.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "policy_revision")
public class PolicyRevisionEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, length = 36)
  private String policyId;

  @Column(nullable = false)
  private int revisionNumber;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(nullable = false)
  private int ruleSchemaVersion;

  @Column(nullable = false, columnDefinition = "text")
  private String rulesJson;

  @Column(nullable = false, length = 512)
  private String createdBy;

  @Column(nullable = false)
  private OffsetDateTime createdAt;

  @Column(length = 512)
  private String approvedBy;

  private OffsetDateTime approvedAt;
  @Version private long version;

  protected PolicyRevisionEntity() {}

  public PolicyRevisionEntity(
      String id, String policyId, String rulesJson, String actor, OffsetDateTime at) {
    this(id, policyId, 1, rulesJson, actor, at);
  }

  public PolicyRevisionEntity(
      String id,
      String policyId,
      int revisionNumber,
      String rulesJson,
      String actor,
      OffsetDateTime at) {
    this.id = id;
    this.policyId = policyId;
    this.revisionNumber = revisionNumber;
    this.status = "DRAFT";
    this.ruleSchemaVersion = 1;
    this.rulesJson = rulesJson;
    this.createdBy = actor;
    this.createdAt = at;
  }

  public void reopen(long expectedVersion) {
    requireVersion(expectedVersion);
    if (!"VALIDATED".equals(status))
      throw new IllegalStateException("only validated revisions can be reopened");
    status = "DRAFT";
  }

  public void updateDraft(String rules, long expectedVersion) {
    if (!"DRAFT".equals(status))
      throw new IllegalStateException("only draft revisions can be changed");
    if (version != expectedVersion) throw new IllegalStateException("stale draft version");
    this.rulesJson = rules;
  }

  public void validateDraft(long expectedVersion) {
    requireVersion(expectedVersion);
    if (!"DRAFT".equals(status)) throw new IllegalStateException("only drafts can be validated");
    status = "VALIDATED";
  }

  public void approve(long expectedVersion, String actor, OffsetDateTime at) {
    requireVersion(expectedVersion);
    if (!"VALIDATED".equals(status))
      throw new IllegalStateException("only validated revisions can be approved");
    if (createdBy.equals(actor))
      throw new IllegalStateException("author cannot approve their own policy");
    status = "APPROVED";
    approvedBy = actor;
    approvedAt = at;
  }

  private void requireVersion(long expectedVersion) {
    if (version != expectedVersion) throw new IllegalStateException("stale draft version");
  }

  public String getApprovedBy() {
    return approvedBy;
  }

  public OffsetDateTime getApprovedAt() {
    return approvedAt;
  }

  public String getId() {
    return id;
  }

  public String getPolicyId() {
    return policyId;
  }

  public int getRevisionNumber() {
    return revisionNumber;
  }

  public String getStatus() {
    return status;
  }

  public int getRuleSchemaVersion() {
    return ruleSchemaVersion;
  }

  public String getRulesJson() {
    return rulesJson;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public long getVersion() {
    return version;
  }
}
