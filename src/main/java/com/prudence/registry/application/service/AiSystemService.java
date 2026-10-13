package com.prudence.registry.application.service;

import com.prudence.alerting.application.port.in.RaiseAlert;
import com.prudence.alerting.application.port.in.RaiseAlert.AlertRequest;
import com.prudence.alerting.application.port.in.RaiseAlert.Audience;
import com.prudence.alerting.domain.AlertKind;
import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.ApprovalLifecycleUseCase;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewQueries;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.application.port.out.ReviewLog;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.AiSystemNotFoundException;
import com.prudence.registry.domain.ApprovalStatus;
import com.prudence.registry.domain.ReviewEvent;
import com.prudence.registry.domain.RiskTier;
import com.prudence.evidence.delivery.DeliverEvidence;
import com.prudence.shared.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// Application service implementing the registry use cases. Orchestrates the domain
/// aggregate and the persistence port; holds no business rules itself.
@Service
public class AiSystemService implements RegisterAiSystemUseCase, AiSystemQueries, ReviewAiSystemUseCase, ReviewQueries,
        ApprovalLifecycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiSystemService.class);
    /// Actor recorded in the audit trail for automatic lifecycle transitions.
    static final String LIFECYCLE_ACTOR = "prudence-lifecycle";

    private final AiSystemRepository repository;
    private final ReviewLog reviewLog;
    private final DeliverEvidence evidence;
    private final RaiseAlert alerts;
    private final int approvalValidityDays;
    private final int reviewWindowDays;

    public AiSystemService(AiSystemRepository repository, ReviewLog reviewLog, DeliverEvidence evidence, RaiseAlert alerts,
                           @Value("${prudence.governance.approval-validity-days:365}") int approvalValidityDays,
                           @Value("${prudence.governance.review-window-days:30}") int reviewWindowDays) {
        this.repository = repository;
        this.reviewLog = reviewLog;
        this.evidence = evidence;
        this.alerts = alerts;
        this.approvalValidityDays = approvalValidityDays;
        this.reviewWindowDays = reviewWindowDays;
    }

    @Override
    @Transactional
    public AiSystem register(RegisterAiSystemCommand command) {
        AiSystem system = AiSystem.register(
                AiSystemId.newId(),
                command.name(),
                new AiSystem.IntendedPurpose(command.intendedPurpose()),
                new AiSystem.Owner(command.ownerName(), command.ownerEmail()),
                command.riskTier(), command.classification());
        return repository.save(system);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiSystem> findById(AiSystemId id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiSystem> findAll() {
        return repository.findAll();
    }

    @Override
    @Transactional
    public AiSystem submitForReview(AiSystemId id) {
        AiSystem system = requireForUpdate(id);
        system.submitForReview();
        return repository.save(system);
    }

    @Override
    @Transactional
    public AiSystem approve(AiSystemId id, String reviewer) {
        AiSystem system = requireForUpdate(id);
        rejectSelfApproval(system, reviewer);
        String previous = system.approvalStatus().name();
        boolean finalApproval = system.approve(reviewer, approvalValidityDays);
        AiSystem saved = repository.save(system);
        recordReview(id.value(), finalApproval ? "APPROVE" : "FIRST_APPROVAL", reviewer, null, previous, saved.approvalStatus().name());
        log.info("governance review: {} system={} by={}", finalApproval ? "APPROVE" : "FIRST_APPROVAL", id.value(), reviewer);
        return saved;
    }

    @Override
    @Transactional
    public AiSystem reject(AiSystemId id, String reviewer, String reason) {
        AiSystem system = requireForUpdate(id);
        rejectSelfApproval(system, reviewer);
        if (reason == null || reason.isBlank()) throw new DomainException("a rejection reason is required");
        String previous = system.approvalStatus().name();
        system.reject();
        AiSystem saved = repository.save(system);
        recordReview(id.value(), "REJECT", reviewer, reason, previous, saved.approvalStatus().name());
        log.info("governance review: REJECT system={} by={} reason={}", id.value(), reviewer, reason);
        return saved;
    }

    @Override
    @Transactional
    public AiSystem renew(AiSystemId id, String reviewer) {
        AiSystem system = requireForUpdate(id);
        rejectSelfApproval(system, reviewer);
        boolean finalRenewal = system.renew(reviewer, approvalValidityDays, Instant.now());
        AiSystem saved = repository.save(system);
        String action = finalRenewal ? "RENEW" : "FIRST_RENEWAL_APPROVAL";
        recordReview(id.value(), action, reviewer, null, "APPROVED", "APPROVED");
        log.info("governance review: {} system={} by={}", action, id.value(), reviewer);
        return saved;
    }

    @Override
    @Transactional
    public AiSystem suspend(AiSystemId id, String actor, String reason) {
        if (reason == null || reason.isBlank()) throw new DomainException("a suspension reason is required");
        AiSystem system = requireForUpdate(id);
        String previous = system.approvalStatus().name();
        system.suspend();
        AiSystem saved = repository.save(system);
        recordReview(id.value(), "SUSPEND", actor, reason, previous, saved.approvalStatus().name());
        log.info("governance review: SUSPEND system={} by={} reason={}", id.value(), actor, reason);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiSystemId> approvedSystems() {
        return repository.findIdsByStatus(ApprovalStatus.APPROVED);
    }

    @Override
    @Transactional
    public Outcome applyLifecycle(AiSystemId id, Instant now) {
        // Locked, so two instances sweeping at once cannot both expire (or notify) a system.
        AiSystem system = repository.findByIdForUpdate(id).orElse(null);
        if (system == null) return Outcome.NONE;
        if (system.hasLapsed(now)) {
            system.expire(now);
            repository.save(system);
            recordReview(id.value(), "EXPIRE", LIFECYCLE_ACTOR,
                    "Approval validity ended " + system.approvalExpiresAt() + " without a renewal.", "APPROVED", "EXPIRED");
            // Same transaction as the transition: the alert exists iff the expiry happened.
            alerts.raise(new AlertRequest(AlertKind.APPROVAL_EXPIRED, "EXPIRED:" + id.value() + ":" + system.approvalExpiresAt(),
                    Audience.GOVERNANCE, List.of(ownerEmail(system)), id.value(),
                    "Approval expired: " + system.name(),
                    "The governance approval of \"" + system.name() + "\" (" + id.value() + ", " + system.riskTier()
                            + " risk) expired on " + system.approvalExpiresAt() + " without a renewal.\n\n"
                            + "Prudence now denies its executions. To use it again, resubmit it for review and "
                            + "complete the approval process."));
            log.info("governance lifecycle: EXPIRE system={}", id.value());
            return Outcome.EXPIRED;
        }
        if (system.needsReviewNotice(now, reviewWindowDays)) {
            system.markReviewDue(now);
            repository.save(system);
            recordReview(id.value(), "REVIEW_DUE", LIFECYCLE_ACTOR,
                    "Approval expires " + system.approvalExpiresAt() + "; renewal review due.", "APPROVED", "APPROVED");
            alerts.raise(new AlertRequest(AlertKind.REVIEW_DUE, "REVIEW_DUE:" + id.value() + ":" + system.approvalExpiresAt(),
                    Audience.GOVERNANCE, List.of(ownerEmail(system)), id.value(),
                    "Renewal review due: " + system.name(),
                    "The governance approval of \"" + system.name() + "\" (" + id.value() + ", " + system.riskTier()
                            + " risk) expires on " + system.approvalExpiresAt() + ".\n\n"
                            + "Renew it before then (Approvals → Renewal due) and the system keeps running"
                            + (system.riskTier() == RiskTier.HIGH
                                    ? "; a high-risk renewal needs two independent sign-offs." : ".")
                            + " If it lapses, Prudence stops allowing it and it must be resubmitted for a full review."));
            log.info("governance lifecycle: REVIEW_DUE system={} expires={}", id.value(), system.approvalExpiresAt());
            return Outcome.REVIEW_DUE;
        }
        return Outcome.NONE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewEvent> reviewsForSystem(String aiSystemId) {
        return reviewLog.findBySystem(aiSystemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewEvent> allReviews() {
        return reviewLog.findAll();
    }

    /// Append the review to the audit log in the review's own transaction — no network call
    /// here, so the decision and its audit record commit (or roll back) together. Its evidence
    /// is delivered to Ernest right after commit, and retried by the delivery worker if that
    /// fails; it never fails or slows the review on an evidence-plane problem.
    private void recordReview(String systemId, String action, String reviewer, String reason,
                              String previous, String next) {
        reviewLog.append(new ReviewEvent(UUID.randomUUID().toString(), systemId, action, reviewer, reason,
                Instant.now().toString(), previous, next, "PENDING", false, null));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evidence.deliverNow("reviews");
            }
        });
    }

    /// Every state change goes through a row lock: two reviewers acting on the same system at
    /// once (e.g. both giving the "second" high-risk sign-off) are serialised, not interleaved.
    private AiSystem requireForUpdate(AiSystemId id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new AiSystemNotFoundException(id));
    }

    private static String ownerEmail(AiSystem system) {
        return system.owner().email() == null ? "" : system.owner().email();
    }

    private static void rejectSelfApproval(AiSystem system, String reviewer) {
        if (reviewer != null && (reviewer.equalsIgnoreCase(system.owner().email())
                || reviewer.equalsIgnoreCase(system.owner().name()))) {
            throw new DomainException("the system owner cannot review their own system");
        }
    }
}
