package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// How Prudence's CloudEvents reach a company's own audit store (ADR-001, custody-only sink):
/// a Kafka topic or an HTTPS webhook (`prudence.evidence.cloudevents.transport`). Either way an
/// event counts as delivered only once the store acknowledged it, and its id is the stable
/// outbox id, so the store can deduplicate retries.
public interface CloudEventsTransport {

  /// One CloudEvent: `type` is versioned (chestertown.prudence.<kind>.v1); `subject` is the AI
  /// system (or register) it is about.
  record CloudEvent(
      String id, String type, String subject, Instant time, Map<String, Object> data) {}

  /// Sends every event: one entry per input, in order, null when acknowledged, otherwise the
  /// failure (classified by the delivery worker like any other transport error).
  List<Exception> send(List<CloudEvent> events);

  /// Sends one event, throwing its failure.
  default void sendOne(CloudEvent event) throws Exception {
    Exception failure = send(List.of(event)).get(0);
    if (failure != null) throw failure;
  }

  /// Where events go, for posture and attestations (no credentials).
  String target();

  /// Whether the store answers right now.
  boolean reachable();

  /// The CloudEvents 1.0 structured-mode envelope of an event.
  static Map<String, Object> envelope(String source, CloudEvent event) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("specversion", "1.0");
    envelope.put("id", event.id());
    envelope.put("source", source);
    envelope.put("type", event.type());
    envelope.put("subject", event.subject());
    envelope.put("time", event.time().toString());
    envelope.put("datacontenttype", "application/json");
    envelope.put("data", event.data());
    return envelope;
  }

  /// The content type of a structured-mode CloudEvent.
  String CONTENT_TYPE = "application/cloudevents+json; charset=UTF-8";
}
