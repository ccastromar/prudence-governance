package com.prudence.incident.domain;

public enum IncidentStatus {
  /// Being triaged; for a serious incident, the report has not been filed yet.
  OPEN,
  /// An initial (possibly incomplete) report was filed with the authority (Art. 73(5)).
  INITIAL_REPORT_SUBMITTED,
  /// The complete report was filed, including root cause and corrective actions.
  FINAL_REPORT_SUBMITTED,
  CLOSED
}
