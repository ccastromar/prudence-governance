package com.prudence.registry.application.port.out;

import com.prudence.registry.domain.ReviewEvent;

import java.util.List;

/// Driven port: durable, append-only storage of governance review events.
public interface ReviewLog {
    void append(ReviewEvent event);
    List<ReviewEvent> findBySystem(String aiSystemId);
    List<ReviewEvent> findAll();
}
