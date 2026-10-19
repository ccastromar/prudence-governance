package cc.ccmlab.prudence.alerting.domain;

/// What an alert is about. Governance alerts go to the governance team (and the system owner
/// where there is one); operational alerts go to whoever runs Prudence.
public enum AlertKind {
  /// An approval entered its review window — renew it before it lapses.
  REVIEW_DUE,
  /// An approval lapsed without a renewal; the system may no longer run.
  APPROVAL_EXPIRED,
  /// Executions were recorded for a system that is not approved (or not registered).
  DRIFT_VIOLATION,
  /// A piece of governance evidence could not be delivered and was dead-lettered.
  EVIDENCE_DEAD_LETTERED,
  /// A delivery target (Ernest) refuses Prudence's configuration; records are queueing.
  DELIVERY_CONFIGURATION,
  /// A serious incident (AI Act Art. 3(49)) was registered or re-triaged as serious.
  SERIOUS_INCIDENT,
  /// An Art. 73 report is due within the next 24 hours and has not been filed.
  INCIDENT_REPORT_DUE,
  /// An Art. 73 reporting deadline has passed without a report.
  INCIDENT_REPORT_OVERDUE,
  /// The drift check could not read all execution evidence, so it cannot vouch for anything.
  DRIFT_SCAN_INCOMPLETE
}
