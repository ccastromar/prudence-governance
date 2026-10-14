package com.prudence.evidence.delivery;

/// Notified by the [EvidenceDeliveryWorker] when delivery needs a human: a record was
/// dead-lettered, or a target's configuration started failing. Called inside the worker's
/// transaction for the record, so a listener that writes (e.g. queues an alert) commits with
/// the dead-letter itself. Implementations must not throw.
public interface DeliveryListener {
  void deadLettered(String target, String recordId, DeliveryFailure failure, String error);

  void configurationFailing(String target, String error);
}
