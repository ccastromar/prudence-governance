package com.prudence.registry.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// JPA row for an append-only governance review event.
@Entity
@Table(name = "review_event")
public class ReviewEventJpaEntity {

    @Id
    private String id;
    private String aiSystemId;
    private String action;
    private String reviewer;
    @Column(length = 2000)
    private String reason;
    private String at;
    private String previousStatus;
    private String newStatus;
    private boolean sealed;
    private Long blockIndex;

    protected ReviewEventJpaEntity() {}

    public ReviewEventJpaEntity(String id, String aiSystemId, String action, String reviewer, String reason,
                                String at, String previousStatus, String newStatus, boolean sealed, Long blockIndex) {
        this.id = id;
        this.aiSystemId = aiSystemId;
        this.action = action;
        this.reviewer = reviewer;
        this.reason = reason;
        this.at = at;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.sealed = sealed;
        this.blockIndex = blockIndex;
    }

    public String getId() { return id; }
    public String getAiSystemId() { return aiSystemId; }
    public String getAction() { return action; }
    public String getReviewer() { return reviewer; }
    public String getReason() { return reason; }
    public String getAt() { return at; }
    public String getPreviousStatus() { return previousStatus; }
    public String getNewStatus() { return newStatus; }
    public boolean isSealed() { return sealed; }
    public Long getBlockIndex() { return blockIndex; }
}
