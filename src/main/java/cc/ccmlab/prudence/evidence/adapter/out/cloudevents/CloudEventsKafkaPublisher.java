package cc.ccmlab.prudence.evidence.adapter.out.cloudevents;

import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestKafkaPublisher;
import jakarta.annotation.PreDestroy;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/// Prudence's evidence as CloudEvents 1.0 (structured mode) on a Kafka topic, for a company
/// that keeps its own audit store (ADR-001, the custody-only C1 sink). Each record is
/// acknowledged by all in-sync replicas before it counts as delivered; the event id is the
/// stable outbox id, so a consumer deduplicates retries. Only identifiers, outcomes, times and
/// hashes are sent — never prompts, inputs or outputs.
@Component
@ConditionalOnExpression(
    "'${prudence.evidence.sink:ernest}' == 'cloudevents'"
        + " and '${prudence.evidence.cloudevents.transport:kafka}' == 'kafka'")
public class CloudEventsKafkaPublisher implements CloudEventsTransport {

  static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

  private final Producer<String, byte[]> producer;
  private final String topic;
  private final String source;
  private final String bootstrapServers;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public CloudEventsKafkaPublisher(
      @Value("${prudence.evidence.cloudevents.kafka-bootstrap-servers:}") String bootstrapServers,
      @Value("${prudence.evidence.cloudevents.topic:prudence.evidence}") String topic,
      @Value("${prudence.evidence.cloudevents.source:prudence}") String source,
      @Value("${prudence.evidence.cloudevents.kafka-security-protocol:PLAINTEXT}") String protocol,
      @Value("${prudence.evidence.cloudevents.kafka-sasl-mechanism:SCRAM-SHA-512}")
          String mechanism,
      @Value("${prudence.evidence.cloudevents.kafka-sasl-username:}") String username,
      @Value("${prudence.evidence.cloudevents.kafka-sasl-password:}") String password,
      @Value("${prudence.evidence.cloudevents.kafka-truststore-location:}") String truststore,
      @Value("${prudence.evidence.cloudevents.kafka-truststore-password:}")
          String truststorePassword) {
    if (bootstrapServers == null || bootstrapServers.isBlank()) {
      throw new IllegalStateException(
          "prudence.evidence.sink=cloudevents needs prudence.evidence.cloudevents.kafka-bootstrap-servers");
    }
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT.toMillis());
    config.put(ProducerConfig.CLIENT_ID_CONFIG, "prudence-evidence");
    config.putAll(
        ErnestKafkaPublisher.securityConfig(
            protocol, mechanism, username, password, truststore, truststorePassword, Map.of()));
    this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    this.topic = topic;
    this.source = source;
    this.bootstrapServers = bootstrapServers;
  }

  public String topic() {
    return topic;
  }

  @Override
  public String target() {
    return "kafka://" + bootstrapServers + "/" + topic;
  }

  /// Sends every event and waits for the broker: one entry per input, in order, null when
  /// acknowledged, otherwise the failure.
  @Override
  public List<Exception> send(List<CloudEvent> events) {
    List<Future<RecordMetadata>> sent = new ArrayList<>(events.size());
    List<Exception> outcomes = new ArrayList<>(events.size());
    for (CloudEvent event : events) {
      try {
        // The subject is also the partition key, so one system's events stay in order.
        Map<String, Object> envelope = CloudEventsTransport.envelope(source, event);
        ProducerRecord<String, byte[]> record =
            new ProducerRecord<>(topic, event.subject(), mapper.writeValueAsBytes(envelope));
        record
            .headers()
            .add(
                new RecordHeader(
                    "content-type",
                    CloudEventsTransport.CONTENT_TYPE.getBytes(StandardCharsets.UTF_8)));
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
        outcomes.set(i, e);
      }
    }
    return outcomes;
  }

  /// Whether the broker answers for the topic right now (metadata within the block timeout).
  @Override
  public boolean reachable() {
    try {
      return !producer.partitionsFor(topic).isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }

  @PreDestroy
  void close() {
    producer.close(Duration.ofSeconds(5));
  }
}
