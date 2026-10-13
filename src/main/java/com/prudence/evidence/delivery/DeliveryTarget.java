package com.prudence.evidence.delivery;

import com.prudence.evidence.adapter.out.ernest.ErnestHttp;

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

    void save(E record);

    /// Sends the record under a stable id. Throws on failure (see [#classify]); on success
    /// returns the receipt to apply to the record (e.g. Ernest's block index).
    Consumer<E> send(E record) throws Exception;

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
