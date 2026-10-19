package cc.ccmlab.prudence.posture.application.port.out;

import java.util.Map;

/// ACL over the incident register: per AI system, open serious incidents and Art. 73 reports
/// that are overdue. Systems with neither are absent.
public interface IncidentExposurePort {
  Map<String, Exposure> bySystem();

  record Exposure(long openSerious, long overdueReports) {}
}
