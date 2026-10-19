package cc.ccmlab.prudence.posture.adapter.out.incident;

import cc.ccmlab.prudence.incident.application.port.out.IncidentRepository;
import cc.ccmlab.prudence.incident.domain.Incident;
import cc.ccmlab.prudence.posture.application.port.out.IncidentExposurePort;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/// ACL adapter: projects unclosed incidents down to per-system exposure.
@Component
public class IncidentExposureAdapter implements IncidentExposurePort {

  private final IncidentRepository incidents;

  public IncidentExposureAdapter(IncidentRepository incidents) {
    this.incidents = incidents;
  }

  @Override
  public Map<String, Exposure> bySystem() {
    Instant now = Instant.now();
    Map<String, Exposure> exposure = new HashMap<>();
    for (Incident i : incidents.findUnclosed()) {
      if (!i.reportable()) continue;
      long overdue = i.reportingState(now) == Incident.ReportingState.OVERDUE ? 1 : 0;
      exposure.merge(
          i.aiSystemId(),
          new Exposure(1, overdue),
          (a, b) ->
              new Exposure(
                  a.openSerious() + b.openSerious(), a.overdueReports() + b.overdueReports()));
    }
    return exposure;
  }
}
