package com.prudence.causality;

import java.time.*;
import java.util.*;

/// Logical order and causal links, independent of Kafka delivery order.
public record GovernanceEventEnvelope(
    String eventId,
    String eventType,
    String occurredAt,
    String aiSystemId,
    String systemVersion,
    String governanceDecisionId,
    String causationId,
    String correlationId,
    long systemSequence,
    Map<String, Object> payload) {}
