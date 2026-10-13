package com.prudence.evidence.adapter.out.ernest;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final JsonMapper mapper = JsonMapper.builder().build();

    ErnestKafkaPublisher(String bootstrapServers, String topic) {
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
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
        this.topic = topic;
    }

    /// Sends every event and waits for the broker. One entry per input, in order: null when
    /// the broker acknowledged it, otherwise the failure (classified by [ErnestHttp#classify]).
    List<Exception> send(List<Event> events) {
        List<Future<RecordMetadata>> sent = new ArrayList<>(events.size());
        List<Exception> outcomes = new ArrayList<>(events.size());
        for (Event event : events) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, event.key(), mapper.writeValueAsBytes(event.body()));
                record.headers()
                        .add(new RecordHeader("source", bytes("prudence-pdp")))
                        .add(new RecordHeader("eventType", bytes("inference.logged")))
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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
