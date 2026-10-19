package cc.ccmlab.prudence.incident.adapter.in.web;

import cc.ccmlab.prudence.incident.adapter.in.web.dto.IncidentDtos.*;
import cc.ccmlab.prudence.incident.application.port.in.IncidentQueries;
import cc.ccmlab.prudence.incident.application.port.in.IncidentUseCase;
import cc.ccmlab.prudence.incident.domain.Incident;
import cc.ccmlab.prudence.incident.domain.IncidentNotFoundException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/// The AI incident register (AI Act Art. 73 / Art. 26(5)). Recording a filing here does not
/// file anything: it documents a report the organisation made to the authority (or provider).
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

  private final IncidentUseCase incidents;
  private final IncidentQueries queries;

  public IncidentController(IncidentUseCase incidents, IncidentQueries queries) {
    this.incidents = incidents;
    this.queries = queries;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public IncidentResponse open(@Valid @RequestBody OpenIncidentRequest body, Principal principal) {
    return respond(incidents.open(body.toCommand(), actor(principal)));
  }

  @GetMapping
  public List<IncidentResponse> list() {
    return queries.list().stream().map(i -> IncidentResponse.from(i, List.of())).toList();
  }

  @GetMapping("/{id}")
  public IncidentResponse get(@PathVariable String id) {
    return respond(queries.get(id).orElseThrow(() -> new IncidentNotFoundException(id)));
  }

  @PostMapping("/{id}/triage")
  public IncidentResponse triage(
      @PathVariable String id, @Valid @RequestBody TriageRequest body, Principal principal) {
    return respond(
        incidents.triage(
            id,
            body.category(),
            body.widespreadInfringement(),
            body.causalLink(),
            body.note(),
            actor(principal)));
  }

  @PostMapping("/{id}/initial-report")
  public IncidentResponse initialReport(
      @PathVariable String id, @Valid @RequestBody ReportRequest body, Principal principal) {
    return respond(incidents.recordInitialReport(id, body.toReport(), actor(principal)));
  }

  @PostMapping("/{id}/final-report")
  public IncidentResponse finalReport(
      @PathVariable String id, @Valid @RequestBody FinalReportRequest body, Principal principal) {
    return respond(
        incidents.recordFinalReport(
            id,
            new Incident.Report(
                body.recipient(), body.reference(), body.submittedAt().toString(), null),
            body.rootCause(),
            body.correctiveActions(),
            actor(principal)));
  }

  @PostMapping("/{id}/close")
  public IncidentResponse close(
      @PathVariable String id, @Valid @RequestBody CloseRequest body, Principal principal) {
    return respond(incidents.close(id, body.note(), actor(principal)));
  }

  private IncidentResponse respond(Incident i) {
    return IncidentResponse.from(i, queries.timeline(i.id()));
  }

  private static String actor(Principal principal) {
    return principal != null ? principal.getName() : "dev-reviewer";
  }
}
