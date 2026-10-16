package com.prudence.registry.adapter.out.persistence;

import com.prudence.registry.application.port.out.ReviewLog;
import com.prudence.registry.domain.ReviewEvent;
import java.util.List;
import org.springframework.stereotype.Component;

/// Driven adapter: append-only persistence of review events over JPA.
@Component
public class ReviewPersistenceAdapter implements ReviewLog {

  private final ReviewEventJpaRepository jpa;

  public ReviewPersistenceAdapter(ReviewEventJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void append(ReviewEvent e) {
    // Appended PENDING, in the review's own transaction; the evidence delivery worker
    // records it in Ernest afterwards.
    jpa.save(
        new ReviewEventJpaEntity(
            e.id(),
            e.aiSystemId(),
            e.action(),
            e.reviewer(),
            e.reason(),
            e.at(),
            e.previousStatus(),
            e.newStatus()));
  }

  @Override
  public List<ReviewEvent> findBySystem(String aiSystemId) {
    return jpa.findByAiSystemIdOrderByAtDesc(aiSystemId).stream()
        .map(ReviewPersistenceAdapter::toDomain)
        .toList();
  }

  @Override
  public List<ReviewEvent> findAll() {
    return jpa.findAllByOrderByAtDesc().stream().map(ReviewPersistenceAdapter::toDomain).toList();
  }

  public static ReviewEvent toDomain(ReviewEventJpaEntity e) {
    return new ReviewEvent(
        e.getId(),
        e.getAiSystemId(),
        e.getAction(),
        e.getReviewer(),
        e.getReason(),
        e.getAt(),
        e.getPreviousStatus(),
        e.getNewStatus(),
        e.getDelivery().status().name(),
        e.isSealed(),
        e.getBlockIndex());
  }
}
