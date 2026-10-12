package com.prudence.registry.application.service;

import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewQueries;
import com.prudence.registry.application.port.out.AiSystemRepository;
import com.prudence.registry.application.port.out.ReviewLog;
import com.prudence.registry.application.port.out.ReviewSealPort;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.AiSystemNotFoundException;
import com.prudence.registry.domain.ReviewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// Application service implementing the registry use cases. Orchestrates the domain
/// aggregate and the persistence port; holds no business rules itself.
@Service
public class AiSystemService implements RegisterAiSystemUseCase, AiSystemQueries, ReviewAiSystemUseCase, ReviewQueries {

    private static final Logger log = LoggerFactory.getLogger(AiSystemService.class);

    private final AiSystemRepository repository;
    private final ReviewLog reviewLog;
    private final ReviewSealPort reviewSeal;

    public AiSystemService(AiSystemRepository repository, ReviewLog reviewLog, ReviewSealPort reviewSeal) {
        this.repository = repository;
        this.reviewLog = reviewLog;
        this.reviewSeal = reviewSeal;
    }

    @Override
    @Transactional
    public AiSystem register(RegisterAiSystemCommand command) {
        AiSystem system = AiSystem.register(
                AiSystemId.newId(),
                command.name(),
                new AiSystem.IntendedPurpose(command.intendedPurpose()),
                new AiSystem.Owner(command.ownerName(), command.ownerEmail()),
                command.riskTier());
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
    public AiSystem approve(AiSystemId id, String reviewer) {
        AiSystem system = require(id);
        String previous = system.approvalStatus().name();
        system.approve();
        AiSystem saved = repository.save(system);
        recordReview(id.value(), "APPROVE", reviewer, null, previous, saved.approvalStatus().name());
        log.info("governance review: APPROVE system={} by={}", id.value(), reviewer);
        return saved;
    }

    @Override
    @Transactional
    public AiSystem reject(AiSystemId id, String reviewer, String reason) {
        AiSystem system = require(id);
        String previous = system.approvalStatus().name();
        system.reject();
        AiSystem saved = repository.save(system);
        recordReview(id.value(), "REJECT", reviewer, reason, previous, saved.approvalStatus().name());
        log.info("governance review: REJECT system={} by={} reason={}", id.value(), reviewer, reason);
        return saved;
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

    /// Seal the review in Ernest (best-effort), then append it to the audit log. Never fails
    /// the review on an evidence-plane problem.
    private void recordReview(String systemId, String action, String reviewer, String reason,
                              String previous, String next) {
        ReviewEvent event = new ReviewEvent(UUID.randomUUID().toString(), systemId, action, reviewer, reason,
                Instant.now().toString(), previous, next, false, null);
        ReviewSealPort.SealResult seal;
        try {
            seal = reviewSeal.seal(event);
        } catch (Exception e) {
            log.warn("could not seal review event for system {}: {}", systemId, e.getMessage());
            seal = new ReviewSealPort.SealResult(false, null);
        }
        reviewLog.append(new ReviewEvent(event.id(), systemId, action, reviewer, reason,
                event.at(), previous, next, seal.sealed(), seal.blockIndex()));
    }

    private AiSystem require(AiSystemId id) {
        return repository.findById(id).orElseThrow(() -> new AiSystemNotFoundException(id));
    }
}
