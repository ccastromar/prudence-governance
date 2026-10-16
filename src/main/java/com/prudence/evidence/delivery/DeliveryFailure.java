package com.prudence.evidence.delivery;

/// How a failed delivery must be handled. Each [DeliveryTarget] maps its transport's errors
/// (Ernest HTTP, SMTP, …) onto these three outcomes; the worker applies the same policy to all.
public enum DeliveryFailure {
  /// Transient (network, timeout, overload): retry with backoff.
  RETRYABLE,
  /// Credentials or endpoint wrong / not configured: every delivery will fail the same way
  /// until an operator fixes it — keep retrying, alert once, never dead-letter.
  CONFIGURATION,
  /// The receiver rejected this payload: retrying the same bytes cannot succeed, so it is
  /// dead-lettered for a human to inspect.
  PERMANENT
}
