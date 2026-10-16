package com.prudence.evidence.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/// Delivery state of one piece of governance evidence on its way to Ernest, embedded in the
/// row that carries the evidence (so the row is its own outbox). The same columns back the
/// decision outbox, review events and attestations.
///
/// PENDING (neither delivered nor dead) → DELIVERED, or → DEAD when Ernest rejects it
/// permanently or it runs out of attempts. A dead record is never retried automatically.
@Embeddable
public class Delivery {

  public enum Status {
    PENDING,
    DELIVERED,
    DEAD
  }

  /// Fixed-width UTC timestamps: the poller compares them as strings, and Instant.toString()
  /// drops zero fractions ("...:37Z" vs "...:37.611Z"), which breaks lexicographic order.
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private String deliveredAt;
  private String deadAt;
  private int attempts;
  /// Doubles as the lease: a worker claims a row by pushing this past the lease window.
  private String nextAttemptAt;

  @Column(length = 2000)
  private String lastError;

  protected Delivery() {}

  /// A new record, due for delivery immediately.
  public static Delivery pending() {
    Delivery d = new Delivery();
    d.nextAttemptAt = stamp(Instant.now());
    return d;
  }

  public static String stamp(Instant instant) {
    return STAMP.format(instant);
  }

  public Status status() {
    if (deliveredAt != null) return Status.DELIVERED;
    if (deadAt != null) return Status.DEAD;
    return Status.PENDING;
  }

  public void delivered() {
    deliveredAt = stamp(Instant.now());
    lastError = null;
  }

  /// Applies the delivery failure contract to this record and says what happened.
  public Status recordFailure(
      DeliveryFailure failure, Exception error, int maxAttempts, Duration configurationRetry) {
    lastError = describe(error);
    switch (failure) {
      case PERMANENT -> {
        attempts++;
        deadAt = stamp(Instant.now());
      }
      case RETRYABLE -> {
        attempts++;
        if (attempts >= maxAttempts) {
          deadAt = stamp(Instant.now());
        } else {
          // Exponential backoff, capped at 1h.
          long seconds = Math.min(3600, 1L << Math.min(attempts, 12));
          nextAttemptAt = stamp(Instant.now().plus(Duration.ofSeconds(seconds)));
        }
      }
      // Not this record's fault: it waits for an operator without consuming attempts.
      case CONFIGURATION -> nextAttemptAt = stamp(Instant.now().plus(configurationRetry));
    }
    return status();
  }

  /// Put a dead-lettered record back in the queue (an operator fixed the cause). It gets a
  /// fresh attempt budget; the last error is kept until the next attempt replaces it.
  public void requeue() {
    if (status() != Status.DEAD)
      throw new IllegalStateException("only a dead-lettered record can be requeued");
    deadAt = null;
    attempts = 0;
    nextAttemptAt = stamp(Instant.now());
  }

  private static String describe(Exception error) {
    String message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }

  public String getDeliveredAt() {
    return deliveredAt;
  }

  public String getDeadAt() {
    return deadAt;
  }

  public int getAttempts() {
    return attempts;
  }

  public String getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }
}
