package cc.ccmlab.prudence.evidence.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class EvidenceDeliveryWorkerTest {

  @Test
  void sendsClaimedRecordsInConfiguredBatchesAndMarksEverySuccessDelivered() {
    FakeTarget target = new FakeTarget(2, List.of("one", "two", "three"));
    EvidenceDeliveryWorker worker = worker(target, List.of());

    worker.deliverNow(target.name());
    worker.shutdown();

    assertEquals(List.of("one", "three", "two"), target.sentIds());
    assertEquals(List.of(1, 2), target.sentBatches.stream().map(List::size).sorted().toList());
    target.records.forEach(
        record -> assertEquals(Delivery.Status.DELIVERED, record.delivery.status()));
  }

  @Test
  void configurationFailureKeepsRecordsQueuedWithoutSpendingAttemptsAndAlertsOnce() {
    FakeTarget target = new FakeTarget(10, List.of("one", "two"));
    target.failure = new IllegalStateException("Kafka credentials rejected");
    List<String> configurationAlerts = new CopyOnWriteArrayList<>();
    DeliveryListener listener =
        new DeliveryListener() {
          @Override
          public void deadLettered(
              String target, String id, DeliveryFailure failure, String error) {}

          @Override
          public void configurationFailing(String target, String error) {
            configurationAlerts.add(target + ":" + error);
          }
        };
    EvidenceDeliveryWorker worker = worker(target, List.of(listener));

    worker.deliverNow(target.name());
    worker.shutdown();

    target.records.forEach(
        record -> {
          assertEquals(Delivery.Status.PENDING, record.delivery.status());
          assertEquals(0, record.delivery.getAttempts());
          assertTrue(
              record.delivery.getNextAttemptAt().compareTo(Delivery.stamp(java.time.Instant.now()))
                  > 0);
        });
    assertEquals(List.of("decisions:Kafka credentials rejected"), configurationAlerts);
  }

  @Test
  void permanentFailureDeadLettersTheRecordAndNotifiesListeners() {
    FakeTarget target = new FakeTarget(1, List.of("bad-record"));
    target.failure = new IllegalArgumentException("payload rejected");
    target.failureKind = DeliveryFailure.PERMANENT;
    List<String> deadLetters = new CopyOnWriteArrayList<>();
    DeliveryListener listener =
        new DeliveryListener() {
          @Override
          public void deadLettered(
              String target, String id, DeliveryFailure failure, String error) {
            deadLetters.add(target + ":" + id + ":" + failure + ":" + error);
          }

          @Override
          public void configurationFailing(String target, String error) {}
        };
    EvidenceDeliveryWorker worker = worker(target, List.of(listener));

    worker.deliverNow(target.name());
    worker.shutdown();

    FakeRecord record = target.records.getFirst();
    assertEquals(Delivery.Status.DEAD, record.delivery.status());
    assertEquals(1, record.delivery.getAttempts());
    assertTrue(record.delivery.getDeadAt() != null);
    assertEquals(List.of("decisions:bad-record:PERMANENT:payload rejected"), deadLetters);
  }

  @Test
  void deliveryIsANewSpanLinkedToTheTraceThatWroteTheRecords() {
    List<SpanData> ended = new CopyOnWriteArrayList<>();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(collecting(ended)).build();
    OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

    // The records are written inside a request's trace (the decision), and delivered later.
    Span decision = otel.getTracer("test").spanBuilder("POST /api/policy/decisions").startSpan();
    FakeTarget target;
    try (Scope ignored = decision.makeCurrent()) {
      target = new FakeTarget(10, List.of("one", "two"));
    } finally {
      decision.end();
    }
    target.records.forEach(
        record ->
            assertEquals(
                "00-"
                    + decision.getSpanContext().getTraceId()
                    + "-"
                    + decision.getSpanContext().getSpanId()
                    + "-"
                    + decision.getSpanContext().getTraceFlags().asHex(),
                record.delivery.getTraceparent()));
    assertNull(new FakeRecord("outside").delivery.getTraceparent(), "outside a trace");

    EvidenceDeliveryWorker worker =
        new EvidenceDeliveryWorker(
            List.of(target),
            List.of(),
            new ImmediateTransactionManager(),
            3,
            2,
            new SimpleMeterRegistry(),
            otel);
    worker.deliverNow(target.name());
    worker.shutdown();
    provider.close();

    SpanData delivery =
        ended.stream()
            .filter(span -> span.getName().equals("prudence.evidence.deliver"))
            .findFirst()
            .orElseThrow();
    assertNotEquals(
        decision.getSpanContext().getTraceId(), delivery.getTraceId(), "a new trace, not a child");
    assertEquals(2, delivery.getLinks().size());
    delivery
        .getLinks()
        .forEach(
            link -> {
              assertEquals(
                  decision.getSpanContext().getTraceId(), link.getSpanContext().getTraceId());
              assertEquals(
                  decision.getSpanContext().getSpanId(), link.getSpanContext().getSpanId());
            });
  }

  @Test
  void aStoredTraceparentThatIsNotOneIsIgnored() {
    for (String bad :
        new String[] {
          null,
          "",
          "garbage",
          "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
          "00-00000000000000000000000000000000-b7ad6b7169203331-01"
        }) {
      assertFalse(EvidenceDeliveryWorker.spanContext(bad).isValid(), String.valueOf(bad));
    }
    assertTrue(
        EvidenceDeliveryWorker.spanContext(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
            .isValid());
  }

  private static SpanProcessor collecting(List<SpanData> ended) {
    return new SpanProcessor() {
      @Override
      public void onStart(Context parentContext, ReadWriteSpan span) {}

      @Override
      public boolean isStartRequired() {
        return false;
      }

      @Override
      public void onEnd(ReadableSpan span) {
        ended.add(span.toSpanData());
      }

      @Override
      public boolean isEndRequired() {
        return true;
      }
    };
  }

  private static EvidenceDeliveryWorker worker(
      FakeTarget target, List<DeliveryListener> listeners) {
    return new EvidenceDeliveryWorker(
        List.of(target), listeners, new ImmediateTransactionManager(), 3, 2);
  }

  private static final class ImmediateTransactionManager
      extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  private static final class FakeTarget implements DeliveryTarget<FakeRecord> {
    private final int batchSize;
    private final List<FakeRecord> records;
    private final List<List<String>> sentBatches = new CopyOnWriteArrayList<>();
    private Exception failure;
    private DeliveryFailure failureKind = DeliveryFailure.CONFIGURATION;

    private FakeTarget(int batchSize, List<String> ids) {
      this.batchSize = batchSize;
      this.records = ids.stream().map(FakeRecord::new).toList();
    }

    @Override
    public String name() {
      return "decisions";
    }

    @Override
    public List<FakeRecord> due(String now, int limit) {
      return records.stream()
          .filter(r -> r.delivery.status() == Delivery.Status.PENDING)
          .filter(r -> r.delivery.getNextAttemptAt().compareTo(now) <= 0)
          .limit(limit)
          .toList();
    }

    @Override
    public String id(FakeRecord record) {
      return record.id;
    }

    @Override
    public Delivery delivery(FakeRecord record) {
      return record.delivery;
    }

    @Override
    public int claim(String id, String expected, String leaseUntil) {
      return 1;
    }

    @Override
    public Optional<FakeRecord> find(String id) {
      return records.stream().filter(r -> r.id.equals(id)).findFirst();
    }

    @Override
    public List<FakeRecord> deadLettered(int limit) {
      return List.of();
    }

    @Override
    public void save(FakeRecord record) {}

    @Override
    public Consumer<FakeRecord> send(FakeRecord record) {
      return ignored -> {};
    }

    @Override
    public int batchSize() {
      return batchSize;
    }

    @Override
    public DeliveryFailure classify(Exception error) {
      return failureKind;
    }

    @Override
    public List<Outcome<FakeRecord>> sendBatch(List<FakeRecord> batch) {
      sentBatches.add(batch.stream().map(r -> r.id).toList());
      return batch.stream()
          .map(
              record ->
                  failure == null
                      ? Outcome.delivered((Consumer<FakeRecord>) ignored -> {})
                      : Outcome.<FakeRecord>failed(failure))
          .toList();
    }

    private List<String> sentIds() {
      return sentBatches.stream().flatMap(List::stream).sorted(Comparator.naturalOrder()).toList();
    }
  }

  private static final class FakeRecord {
    private final String id;
    private final Delivery delivery = Delivery.pending();

    private FakeRecord(String id) {
      this.id = id;
    }
  }
}
