package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.application.port.out.ReviewLog;
import com.prudence.registry.domain.ReviewEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/// Driven adapter: append-only persistence of review events over JPA.
@Component
public class ReviewPersistenceAdapter implements ReviewLog {

    private final ReviewEventJpaRepository jpa;

    public ReviewPersistenceAdapter(ReviewEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(ReviewEvent e) {
        jpa.save(new ReviewEventJpaEntity(e.id(), e.aiSystemId(), e.action(), e.reviewer(), e.reason(),
                e.at(), e.previousStatus(), e.newStatus(), e.sealed(), e.blockIndex()));
    }

    @Override
    public List<ReviewEvent> findBySystem(String aiSystemId) {
        return jpa.findByAiSystemIdOrderByAtDesc(aiSystemId).stream().map(ReviewPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<ReviewEvent> findAll() {
        return jpa.findAllByOrderByAtDesc().stream().map(ReviewPersistenceAdapter::toDomain).toList();
    }

    private static ReviewEvent toDomain(ReviewEventJpaEntity e) {
        return new ReviewEvent(e.getId(), e.getAiSystemId(), e.getAction(), e.getReviewer(), e.getReason(),
                e.getAt(), e.getPreviousStatus(), e.getNewStatus(), e.isSealed(), e.getBlockIndex());
    }
}
