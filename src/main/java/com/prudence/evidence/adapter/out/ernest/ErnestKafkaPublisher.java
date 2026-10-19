package com.prudence.evidence.adapter.out.ernest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

/// Ernest ADR-005 transport for PDP decisions: one Kafka record per decision, carrying the
/// body and headers POST /events would carry (source, eventType, sourceEventId). Keyed by AI
/// system, so one system's decisions keep their order. The producer is idempotent with
/// acks=all: a send that completes means the broker durably holds the record, which is when
/// the outbox may mark it delivered. Ernest deduplicates replays by the decision id.
final class ErnestKafkaPublisher implements AutoCloseable {

  /// Bounded well below the outbox lease, like the HTTP timeouts.
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

  /// One record to publish: partition key, Ernest's dedup id, and the event body.
  record Event(String key, String sourceEventId, Map<String, Object> body) {}

  private final Producer<String, byte[]> producer;
  private final String topic;
  private final JsonMapper mapper = JsonMapper.builder().build();

  ErnestKafkaPublisher(String bootstrapServers, String topic, Map<String, Object> security) {
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    // Batching is the producer's job: wait up to 5 ms to fill a batch, compress it.
    config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    config.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);
    config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT.toMillis());
    config.put(ProducerConfig.CLIENT_ID_CONFIG, "prudence-pdp");
    // Security last: it may also override any setting above (kafka-properties passthrough).
    config.putAll(security);
    this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    this.topic = topic;
  }

  /// Kept package-visible so transport behaviour can be tested with Kafka's MockProducer
  /// without opening a broker connection.
  ErnestKafkaPublisher(Producer<String, byte[]> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  /// Sends every event and waits for the broker. One entry per input, in order: null when
  /// the broker acknowledged it, otherwise the failure (classified by [ErnestHttp#classify]).
  List<Exception> send(List<Event> events) {
    List<Future<RecordMetadata>> sent = new ArrayList<>(events.size());
    List<Exception> outcomes = new ArrayList<>(events.size());
    for (Event event : events) {
      try {
        ProducerRecord<String, byte[]> record =
            new ProducerRecord<>(topic, event.key(), mapper.writeValueAsBytes(event.body()));
        record
            .headers()
            .add(new RecordHeader("source", bytes("prudence-pdp")))
            .add(new RecordHeader("eventType", bytes(ErnestEvidenceAdapter.DECISION_EVENT_TYPE)))
            .add(new RecordHeader("sourceEventId", bytes(event.sourceEventId())));
        sent.add(producer.send(record));
        outcomes.add(null);
      } catch (Exception e) {
        sent.add(null);
        outcomes.add(e);
      }
    }
    long deadline = System.nanoTime() + SEND_TIMEOUT.toNanos();
    for (int i = 0; i < sent.size(); i++) {
      Future<RecordMetadata> future = sent.get(i);
      if (future == null) continue;
      try {
        future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (ExecutionException e) {
        outcomes.set(i, e.getCause() instanceof Exception cause ? cause : e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        outcomes.set(i, e);
      } catch (Exception e) {
        outcomes.set(i, e); // timeout: not acknowledged, so not delivered
      }
    }
    return outcomes;
  }

  /// Client security from Prudence's settings: protocol, SASL login (SCRAM or PLAIN, built
  /// into a JAAS line so credentials stay in environment variables), optional truststore, then
  /// any `kafka-properties` passthrough.
  static Map<String, Object> securityConfig(
      String protocol,
      String mechanism,
      String username,
      String password,
      String truststoreLocation,
      String truststorePassword,
      Map<String, String> passthrough) {
    Map<String, Object> config = new java.util.LinkedHashMap<>();
    String p =
        protocol == null || protocol.isBlank() ? "PLAINTEXT" : protocol.strip().toUpperCase();
    if (!java.util.Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(p)) {
      throw new IllegalStateException(
          "prudence.ernest.kafka-security-protocol must be PLAINTEXT, SSL, SASL_PLAINTEXT or"
              + " SASL_SSL (was "
              + protocol
              + ")");
    }
    config.put("security.protocol", p);
    if (p.startsWith("SASL_")) {
      String m =
          mechanism == null || mechanism.isBlank()
              ? "SCRAM-SHA-512"
              : mechanism.strip().toUpperCase();
      String module =
          switch (m) {
            case "SCRAM-SHA-512", "SCRAM-SHA-256" ->
                "org.apache.kafka.common.security.scram.ScramLoginModule";
            case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
            default ->
                throw new IllegalStateException(
                    "prudence.ernest.kafka-sasl-mechanism must be SCRAM-SHA-512, SCRAM-SHA-256 or"
                        + " PLAIN (was "
                        + mechanism
                        + ")");
          };
      if (username == null || username.isBlank() || password == null || password.isBlank()) {
        throw new IllegalStateException(
            "SASL needs prudence.ernest.kafka-sasl-username and kafka-sasl-password");
      }
      config.put("sasl.mechanism", m);
      config.put(
          "sasl.jaas.config",
          module
              + " required username=\""
              + jaasEscape(username)
              + "\" password=\""
              + jaasEscape(password)
              + "\";");
    }
    if (truststoreLocation != null && !truststoreLocation.isBlank()) {
      config.put("ssl.truststore.location", truststoreLocation);
      if (truststoreLocation.toLowerCase().endsWith(".pem"))
        config.put("ssl.truststore.type", "PEM");
      if (truststorePassword != null && !truststorePassword.isBlank())
        config.put("ssl.truststore.password", truststorePassword);
    }
    if (passthrough != null) config.putAll(passthrough);
    return config;
  }

  private static String jaasEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    producer.close(Duration.ofSeconds(5));
  }
}
