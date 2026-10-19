package cc.ccmlab.prudence.evidence.delivery;

import cc.ccmlab.prudence.evidence.adapter.out.ernest.ErnestHttp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/// One kind of durable outbound record the [EvidenceDeliveryWorker] delivers: governance
/// evidence to Ernest (decisions, reviews, attestations) and alerts by email. Each context
/// plugs its own in; the worker owns the lease, the failure contract and the alerting, so
/// everything is delivered the same way.
public interface DeliveryTarget<E> {

  /// Short name used in logs and for [DeliverEvidence#deliverNow].
  String name();

  /// Up to `limit` records that are PENDING and due at or before `now`, oldest first.
  List<E> due(String now, int limit);

  String id(E record);

  Delivery delivery(E record);

  /// Atomically lease the record: set its next attempt to `leaseUntil` only if it is still
  /// pending and its next attempt is still `expected`. Returns the number of rows updated.
  int claim(String id, String expected, String leaseUntil);

  Optional<E> find(String id);

  /// Most recently dead-lettered records first, for operators to inspect and re-drive.
  List<E> deadLettered(int limit);

  /// The AI system the record concerns, when it concerns one (null for estate-wide records).
  default String aiSystemId(E record) {
    return null;
  }

  void save(E record);

  /// Sends the record under a stable id. Throws on failure (see [#classify]); on success
  /// returns the receipt to apply to the record (e.g. Ernest's block index).
  Consumer<E> send(E record) throws Exception;

  /// How many records [#sendBatch] takes per call; 1 means one at a time.
  default int batchSize() {
    return 1;
  }

  /// Sends several records in one call and reports one outcome per record, in order.
  /// Targets whose transport has a batch endpoint override it; the default sends one by one.
  default List<Outcome<E>> sendBatch(List<E> records) {
    List<Outcome<E>> outcomes = new ArrayList<>(records.size());
    for (E record : records) {
      try {
        outcomes.add(Outcome.delivered(send(record)));
      } catch (Exception e) {
        outcomes.add(Outcome.failed(e));
      }
    }
    return outcomes;
  }

  /// The result of sending one record: a receipt to apply, or the failure to classify.
  record Outcome<E>(Consumer<E> receipt, Exception failure) {
    public static <E> Outcome<E> delivered(Consumer<E> receipt) {
      return new Outcome<>(receipt, null);
    }

    public static <E> Outcome<E> failed(Exception failure) {
      return new Outcome<>(null, failure);
    }
  }

  /// Maps a [#send] failure onto the delivery contract. Defaults to Ernest's HTTP contract.
  default DeliveryFailure classify(Exception error) {
    return ErnestHttp.classify(error);
  }

  /// Whether this target's own failures are reported to [DeliveryListener]s. A target that
  /// *is* the alert channel opts out, so a broken channel cannot alert about itself forever.
  default boolean reportsFailures() {
    return true;
  }
}
