package com.prudence.alerting.application.port.in;

import com.prudence.alerting.domain.AlertKind;
import java.util.List;

/// Driving port: raise an alert. It is stored in the caller's transaction — committing (or
/// rolling back) with the event that caused it — and then sent by email, retried until
/// delivered. `dedupKey` makes raising idempotent: the same key is alerted once, ever, no
/// matter how many sweeps, retries, restarts or instances raise it.
public interface RaiseAlert {
  void raise(AlertRequest request);

  enum Audience {
    GOVERNANCE,
    OPERATIONS
  }

  /// `extraRecipients` are added to the audience's list (e.g. the system owner); may be empty.
  record AlertRequest(
      AlertKind kind,
      String dedupKey,
      Audience audience,
      List<String> extraRecipients,
      String aiSystemId,
      String subject,
      String body) {}
}
