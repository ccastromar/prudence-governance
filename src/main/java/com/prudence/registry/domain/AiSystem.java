package com.prudence.registry.domain;

import com.prudence.shared.exception.DomainException;
import java.time.Duration;
import java.time.Instant;

/// Aggregate root of the governance registry: an AI system/use case with its risk tier,
/// owner and approval state. Pure domain — no framework or persistence concerns here.
public final class AiSystem {

  public record Owner(String name, String email) {
    public Owner {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("owner name required");
    }
  }

  public record IntendedPurpose(String value) {
    public IntendedPurpose {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException("intended purpose required");
    }
  }

  private final AiSystemId id;
  private final String name;
  private final IntendedPurpose intendedPurpose;
  private final Owner owner;
  private final ClassificationAssessment classification;
  private RiskTier riskTier;
  private ApprovalStatus approvalStatus;
  private String approvedAt;
  private String approvalExpiresAt;
  private String firstApprover;
  private String firstApprovedAt;
  /// First sign-off of an in-flight renewal of a high-risk approval (four-eyes, like approval).
  private String renewalFirstApprover;
  private String renewalFirstApprovedAt;
  /// When the periodic-review notice was issued for the current approval (null = not yet).
  private String reviewDueNotifiedAt;

  private AiSystem(
      AiSystemId id,
      String name,
      IntendedPurpose intendedPurpose,
      Owner owner,
      RiskTier riskTier,
      ApprovalStatus approvalStatus,
      ClassificationAssessment classification,
      String approvedAt,
      String approvalExpiresAt,
      String firstApprover,
      String firstApprovedAt,
      String renewalFirstApprover,
      String renewalFirstApprovedAt,
      String reviewDueNotifiedAt) {
    this.id = id;
    this.name = name;
    this.intendedPurpose = intendedPurpose;
    this.owner = owner;
    this.riskTier = riskTier;
    this.approvalStatus = approvalStatus;
    this.classification = classification;
    this.approvedAt = approvedAt;
    this.approvalExpiresAt = approvalExpiresAt;
    this.firstApprover = firstApprover;
    this.firstApprovedAt = firstApprovedAt;
    this.renewalFirstApprover = renewalFirstApprover;
    this.renewalFirstApprovedAt = renewalFirstApprovedAt;
    this.reviewDueNotifiedAt = reviewDueNotifiedAt;
  }

  /// Registration creates an assessment dossier; it never implicitly authorises use.
  public static AiSystem register(
      AiSystemId id,
      String name,
      IntendedPurpose purpose,
      Owner owner,
      RiskTier riskTier,
      ClassificationAssessment classification) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    ApprovalStatus initial =
        switch (riskTier) {
          case PROHIBITED -> ApprovalStatus.BLOCKED;
          case HIGH, LIMITED, MINIMAL -> ApprovalStatus.UNDER_ASSESSMENT;
        };
    return new AiSystem(
        id,
        name,
        purpose,
        owner,
        riskTier,
        initial,
        classification,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /// Reconstitute from persistence (no invariants re-applied).
  public static AiSystem rehydrate(
      AiSystemId id,
      String name,
      IntendedPurpose purpose,
      Owner owner,
      RiskTier riskTier,
      ApprovalStatus approvalStatus,
      ClassificationAssessment classification,
      String approvedAt,
      String approvalExpiresAt,
      String firstApprover,
      String firstApprovedAt,
      String renewalFirstApprover,
      String renewalFirstApprovedAt,
      String reviewDueNotifiedAt) {
    return new AiSystem(
        id,
        name,
        purpose,
        owner,
        riskTier,
        approvalStatus,
        classification,
        approvedAt,
        approvalExpiresAt,
        firstApprover,
        firstApprovedAt,
        renewalFirstApprover,
        renewalFirstApprovedAt,
        reviewDueNotifiedAt);
  }

  /// Reclassify risk; prohibited systems become blocked, otherwise approval is reset.
  public void reclassify(RiskTier newTier) {
    this.riskTier = newTier;
    this.approvalStatus =
        switch (newTier) {
          case PROHIBITED -> ApprovalStatus.BLOCKED;
          case HIGH, LIMITED, MINIMAL -> ApprovalStatus.UNDER_ASSESSMENT;
        };
  }

  public void submitForReview() {
    if (riskTier == RiskTier.PROHIBITED)
      throw new DomainException("a prohibited AI system cannot be submitted");
    if (approvalStatus != ApprovalStatus.UNDER_ASSESSMENT
        && approvalStatus != ApprovalStatus.REJECTED
        && approvalStatus != ApprovalStatus.EXPIRED
        && approvalStatus != ApprovalStatus.SUSPENDED) {
      throw new DomainException(
          "only an assessed, rejected, expired or suspended system can be submitted (was "
              + approvalStatus
              + ")");
    }
    this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
    // A fresh review: nothing from a previous approval period carries over (it stays in
    // the audit trail).
    this.approvedAt = null;
    this.approvalExpiresAt = null;
    this.firstApprover = null;
    this.firstApprovedAt = null;
    this.reviewDueNotifiedAt = null;
  }

  /// A governance reviewer clears the system to go live. Only a system awaiting a decision
  /// can be approved; prohibited systems are blocked outright and never eligible. A high-risk
  /// system needs two independent sign-offs: returns false after the first, true once the
  /// approval is final (and starts its `validityDays` expiry clock).
  public boolean approve(String reviewer, int validityDays) {
    if (riskTier == RiskTier.PROHIBITED) {
      throw new DomainException("a prohibited AI system cannot be approved");
    }
    if (riskTier == RiskTier.HIGH && approvalStatus == ApprovalStatus.PENDING_APPROVAL) {
      this.firstApprover = reviewer;
      this.firstApprovedAt = java.time.Instant.now().toString();
      this.approvalStatus = ApprovalStatus.PENDING_SECOND_APPROVAL;
      return false;
    }
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL
        && approvalStatus != ApprovalStatus.PENDING_SECOND_APPROVAL) {
      throw new DomainException(
          "only a system awaiting approval can be approved (was " + approvalStatus + ")");
    }
    if (approvalStatus == ApprovalStatus.PENDING_SECOND_APPROVAL
        && reviewer.equalsIgnoreCase(firstApprover)) {
      throw new DomainException("the second high-risk approver must be independent");
    }
    this.approvalStatus = ApprovalStatus.APPROVED;
    this.approvedAt = java.time.Instant.now().toString();
    this.approvalExpiresAt =
        java.time.Instant.now().plus(java.time.Duration.ofDays(validityDays)).toString();
    this.reviewDueNotifiedAt = null;
    return true;
  }

  /// Periodic review: a reviewer renews a still-valid approval before it lapses, so a
  /// governed system never has to stop running for its review. The system stays APPROVED
  /// throughout. A high-risk renewal needs two independent sign-offs, exactly like the
  /// original approval: returns false after the first, true once the renewal is final.
  /// An approval that already expired cannot be renewed — it must be resubmitted.
  public boolean renew(String reviewer, int validityDays, Instant now) {
    if (!isApproved(now)) {
      throw new DomainException(
          "only a currently approved system can be renewed (was "
              + approvalStatus
              + (approvalStatus == ApprovalStatus.APPROVED ? ", expired" : "")
              + "); resubmit it for review");
    }
    if (riskTier == RiskTier.HIGH && renewalFirstApprover == null) {
      this.renewalFirstApprover = reviewer;
      this.renewalFirstApprovedAt = now.toString();
      return false;
    }
    if (riskTier == RiskTier.HIGH && reviewer.equalsIgnoreCase(renewalFirstApprover)) {
      throw new DomainException("the second high-risk renewal approver must be independent");
    }
    this.approvedAt = now.toString();
    this.approvalExpiresAt = now.plus(Duration.ofDays(validityDays)).toString();
    this.renewalFirstApprover = null;
    this.renewalFirstApprovedAt = null;
    this.reviewDueNotifiedAt = null;
    return true;
  }

  /// Stop the system now — e.g. after a serious incident. The PDP denies it at once; it can
  /// only return to service through a fresh submission and full review.
  public void suspend() {
    if (approvalStatus == ApprovalStatus.BLOCKED
        || approvalStatus == ApprovalStatus.RETIRED
        || approvalStatus == ApprovalStatus.SUSPENDED) {
      throw new DomainException("a " + approvalStatus + " AI system cannot be suspended");
    }
    this.approvalStatus = ApprovalStatus.SUSPENDED;
    this.renewalFirstApprover = null;
    this.renewalFirstApprovedAt = null;
  }

  /// The approval's validity has run out (the PDP already treats it as not approved).
  public boolean hasLapsed(Instant now) {
    return approvalStatus == ApprovalStatus.APPROVED && !isApproved(now);
  }

  /// Lifecycle sweep: make a lapsed approval visible as EXPIRED. Any in-flight renewal is void.
  public void expire(Instant now) {
    if (!hasLapsed(now))
      throw new DomainException("only a lapsed approval can expire (was " + approvalStatus + ")");
    this.approvalStatus = ApprovalStatus.EXPIRED;
    this.renewalFirstApprover = null;
    this.renewalFirstApprovedAt = null;
  }

  /// True when a still-valid approval has entered its review window and no notice has been
  /// issued for it yet — the notice is issued once per approval period.
  public boolean needsReviewNotice(Instant now, int reviewWindowDays) {
    return isApproved(now)
        && reviewDueNotifiedAt == null
        && !Instant.parse(approvalExpiresAt).minus(Duration.ofDays(reviewWindowDays)).isAfter(now);
  }

  public void markReviewDue(Instant now) {
    this.reviewDueNotifiedAt = now.toString();
  }

  /// A governance reviewer declines the request. The system stays off-limits to the PDP
  /// (not approved) until it is re-submitted or reclassified.
  public void reject() {
    if (approvalStatus == ApprovalStatus.BLOCKED) {
      throw new DomainException("a blocked AI system cannot be rejected");
    }
    // Either reviewer of a four-eyes approval may decline, including after the first sign-off.
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL
        && approvalStatus != ApprovalStatus.PENDING_SECOND_APPROVAL) {
      throw new DomainException(
          "only a system awaiting approval can be rejected (was " + approvalStatus + ")");
    }
    this.approvalStatus = ApprovalStatus.REJECTED;
    // A resubmission starts a fresh review: the earlier first sign-off no longer counts.
    this.firstApprover = null;
    this.firstApprovedAt = null;
  }

  public boolean isApproved() {
    return isApproved(Instant.now());
  }

  private boolean isApproved(Instant now) {
    return approvalStatus == ApprovalStatus.APPROVED
        && approvalExpiresAt != null
        && Instant.parse(approvalExpiresAt).isAfter(now);
  }

  public AiSystemId id() {
    return id;
  }

  public String name() {
    return name;
  }

  public IntendedPurpose intendedPurpose() {
    return intendedPurpose;
  }

  public Owner owner() {
    return owner;
  }

  public RiskTier riskTier() {
    return riskTier;
  }

  public ApprovalStatus approvalStatus() {
    return approvalStatus;
  }

  public ClassificationAssessment classification() {
    return classification;
  }

  public String approvedAt() {
    return approvedAt;
  }

  public String approvalExpiresAt() {
    return approvalExpiresAt;
  }

  public String firstApprover() {
    return firstApprover;
  }

  public String firstApprovedAt() {
    return firstApprovedAt;
  }

  public String renewalFirstApprover() {
    return renewalFirstApprover;
  }

  public String renewalFirstApprovedAt() {
    return renewalFirstApprovedAt;
  }

  public String reviewDueNotifiedAt() {
    return reviewDueNotifiedAt;
  }

  public boolean reviewDue() {
    return reviewDueNotifiedAt != null;
  }
}
