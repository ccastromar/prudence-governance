package com.prudence.incident.application.service;

import com.prudence.alerting.application.port.in.RaiseAlert;
import com.prudence.alerting.application.port.in.RaiseAlert.AlertRequest;
import com.prudence.alerting.application.port.in.RaiseAlert.Audience;
import com.prudence.alerting.domain.AlertKind;
import com.prudence.evidence.delivery.DeliverEvidence;
import com.prudence.incident.application.port.in.IncidentDeadlineUseCase;
import com.prudence.incident.application.port.in.IncidentQueries;
import com.prudence.incident.application.port.in.IncidentUseCase;
import com.prudence.incident.application.port.out.IncidentEventLog;
import com.prudence.incident.application.port.out.IncidentRepository;
import com.prudence.incident.application.port.out.SystemContextPort;
import com.prudence.incident.application.port.out.SystemContextPort.SystemContext;
import com.prudence.incident.domain.CausalLink;
import com.prudence.incident.domain.Incident;
import com.prudence.incident.domain.IncidentCategory;
import com.prudence.incident.domain.IncidentEvent;
import com.prudence.incident.domain.IncidentNotFoundException;
import com.prudence.incident.domain.IncidentStatus;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.AiSystemNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/// The AI incident register. Every change is appended to the incident's timeline (recorded in
/// Ernest after commit) and, where people must act, raises an email alert in the same
/// transaction. Optionally suspends the AI system in the registry, atomically with opening.
@Service
public class IncidentService implements IncidentUseCase, IncidentQueries, IncidentDeadlineUseCase {

  private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
  /// The "report due" reminder goes out this long before the deadline.
  static final Duration DUE_WARNING = Duration.ofHours(24);

  private final IncidentRepository incidents;
  private final IncidentEventLog timeline;
  private final SystemContextPort systems;
  private final ReviewAiSystemUseCase registry;
  private final RaiseAlert alerts;
  private final DeliverEvidence evidence;

  public IncidentService(
      IncidentRepository incidents,
      IncidentEventLog timeline,
      SystemContextPort systems,
      ReviewAiSystemUseCase registry,
      RaiseAlert alerts,
      DeliverEvidence evidence) {
    this.incidents = incidents;
    this.timeline = timeline;
    this.systems = systems;
    this.registry = registry;
    this.alerts = alerts;
    this.evidence = evidence;
  }

  @Override
  @Transactional
  public Incident open(OpenIncidentCommand c, String actor) {
    SystemContext system =
        systems
            .find(c.aiSystemId())
            .orElseThrow(() -> new AiSystemNotFoundException(new AiSystemId(c.aiSystemId())));
    Instant now = Instant.now();
    Incident incident =
        incidents.save(
            Incident.open(
                UUID.randomUUID().toString(),
                system.id(),
                c.title(),
                c.description(),
                c.occurredAt(),
                c.detectedAt(),
                actor,
                system.role(),
                c.memberStates(),
                c.category(),
                c.widespreadInfringement(),
                c.causalLink(),
                now));
    record(incident, "OPENED", actor, summary(incident));
    if (c.suspendSystem()) {
      registry.suspend(
          new AiSystemId(system.id()),
          actor,
          "Incident " + incident.id() + ": " + incident.title());
      record(
          incident,
          "SYSTEM_SUSPENDED",
          actor,
          "AI system " + system.id() + " taken out of service.");
    }
    alertIfSerious(incident, system);
    log.info(
        "incident opened: {} system={} category={} reportable={}",
        incident.id(),
        system.id(),
        incident.category(),
        incident.reportable());
    return incident;
  }

  @Override
  @Transactional
  public Incident triage(
      String id,
      IncidentCategory category,
      boolean widespread,
      CausalLink causalLink,
      String note,
      String actor) {
    if (note == null || note.isBlank())
      throw new IllegalArgumentException("a triage note is required");
    Incident incident = requireForUpdate(id);
    incident.triage(category, widespread, causalLink);
    Incident saved = incidents.save(incident);
    record(saved, "TRIAGED", actor, summary(saved) + " Note: " + note.strip());
    systems.find(saved.aiSystemId()).ifPresent(system -> alertIfSerious(saved, system));
    return saved;
  }

  @Override
  @Transactional
  public Incident recordInitialReport(String id, Incident.Report report, String actor) {
    Incident incident = requireForUpdate(id);
    incident.recordInitialReport(withRecorder(report, actor));
    Incident saved = incidents.save(incident);
    record(saved, "INITIAL_REPORT", actor, reportDetails(saved, saved.initialReport()));
    return saved;
  }

  @Override
  @Transactional
  public Incident recordFinalReport(
      String id, Incident.Report report, String rootCause, String correctiveActions, String actor) {
    Incident incident = requireForUpdate(id);
    incident.recordFinalReport(withRecorder(report, actor), rootCause, correctiveActions);
    Incident saved = incidents.save(incident);
    record(
        saved,
        "FINAL_REPORT",
        actor,
        reportDetails(saved, saved.finalReport())
            + " Root cause: "
            + saved.rootCause()
            + " Corrective actions: "
            + saved.correctiveActions());
    return saved;
  }

  @Override
  @Transactional
  public Incident close(String id, String note, String actor) {
    Incident incident = requireForUpdate(id);
    incident.close(actor, note, Instant.now());
    Incident saved = incidents.save(incident);
    record(saved, "CLOSED", actor, saved.closingNote());
    return saved;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Incident> list() {
    return incidents.findAllNewestFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Incident> get(String id) {
    return incidents.findById(id);
  }

  @Override
  @Transactional(readOnly = true)
  public List<IncidentEvent> timeline(String id) {
    return timeline.findByIncident(id);
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> unreportedIncidentIds() {
    return incidents.findUnclosed().stream()
        .filter(i -> i.status() == IncidentStatus.OPEN && i.reportable())
        .map(Incident::id)
        .toList();
  }

  @Override
  @Transactional
  public void checkDeadline(String incidentId, Instant now) {
    Incident incident = incidents.findByIdForUpdate(incidentId).orElse(null);
    if (incident == null) return;
    Incident.ReportingState state = incident.reportingState(now);
    if (state != Incident.ReportingState.DUE && state != Incident.ReportingState.OVERDUE) return;
    Instant deadline = incident.reportingDeadline();
    String owner = systems.find(incident.aiSystemId()).map(SystemContext::ownerEmail).orElse("");
    if (state == Incident.ReportingState.OVERDUE) {
      alerts.raise(
          new AlertRequest(
              AlertKind.INCIDENT_REPORT_OVERDUE,
              "INCIDENT_OVERDUE:" + incident.id() + ":" + deadline,
              Audience.GOVERNANCE,
              List.of(owner),
              incident.aiSystemId(),
              "Incident report OVERDUE: " + incident.title(),
              "The AI Act report for incident \""
                  + incident.title()
                  + "\" ("
                  + incident.id()
                  + ") was due on "
                  + deadline
                  + " and has not been recorded as filed.\n\n"
                  + obligation(incident)));
    } else if (!now.isBefore(deadline.minus(DUE_WARNING))) {
      alerts.raise(
          new AlertRequest(
              AlertKind.INCIDENT_REPORT_DUE,
              "INCIDENT_DUE:" + incident.id() + ":" + deadline,
              Audience.GOVERNANCE,
              List.of(owner),
              incident.aiSystemId(),
              "Incident report due within 24h: " + incident.title(),
              "The AI Act report for incident \""
                  + incident.title()
                  + "\" ("
                  + incident.id()
                  + ") is due by "
                  + deadline
                  + ".\n\n"
                  + obligation(incident)));
    }
  }

  private void alertIfSerious(Incident incident, SystemContext system) {
    if (!incident.reportable()) return;
    // Keyed by category + causal link: re-triaging to a different serious category re-alerts.
    alerts.raise(
        new AlertRequest(
            AlertKind.SERIOUS_INCIDENT,
            "INCIDENT:"
                + incident.id()
                + ":"
                + incident.category()
                + ":"
                + incident.widespreadInfringement(),
            Audience.GOVERNANCE,
            List.of(system.ownerEmail() == null ? "" : system.ownerEmail()),
            system.id(),
            "Serious incident: " + incident.title() + " (" + system.name() + ")",
            "A serious incident was registered for \""
                + system.name()
                + "\" ("
                + system.id()
                + ", "
                + system.riskTier()
                + " risk).\n\n"
                + incident.description()
                + "\n\n"
                + "Category: "
                + incident.category()
                + (incident.widespreadInfringement() ? " (widespread infringement)" : "")
                + "\nCausal link: "
                + incident.causalLink()
                + "\nAware since: "
                + incident.detectedAt()
                + "\n\n"
                + obligation(incident)));
  }

  private static String obligation(Incident i) {
    return i.reportingBasis()
        + "\nReport to: "
        + i.reportingRecipient()
        + "\nDeadline: "
        + i.reportingDeadline()
        + "\n\n"
        + "Record the filing in Prudence (Incidents) once submitted. Do not alter the AI system in"
        + " a way that could affect the evaluation of the incident's causes before informing the"
        + " authorities (Art. 73(6)).";
  }

  private static String summary(Incident i) {
    return "Category "
        + i.category()
        + (i.widespreadInfringement() ? " (widespread)" : "")
        + ", causal link "
        + i.causalLink()
        + ", aware since "
        + i.detectedAt()
        + ". "
        + i.reportingBasis()
        + (i.reportable() ? " Deadline " + i.reportingDeadline() + "." : "");
  }

  private static String reportDetails(Incident i, Incident.Report r) {
    return "Filed with "
        + r.recipient()
        + " on "
        + r.submittedAt()
        + ", reference "
        + r.reference()
        + " ("
        + i.reportingState(Instant.now())
        + ").";
  }

  private static Incident.Report withRecorder(Incident.Report r, String actor) {
    if (r == null) throw new IllegalArgumentException("report required");
    return new Incident.Report(r.recipient(), r.reference(), r.submittedAt(), actor);
  }

  private Incident requireForUpdate(String id) {
    return incidents.findByIdForUpdate(id).orElseThrow(() -> new IncidentNotFoundException(id));
  }

  /// Append to the timeline in this transaction; deliver it to Ernest once committed.
  private void record(Incident incident, String type, String actor, String details) {
    timeline.append(
        new IncidentEvent(
            UUID.randomUUID().toString(),
            incident.id(),
            incident.aiSystemId(),
            type,
            actor,
            Instant.now().toString(),
            details,
            "PENDING",
            false,
            null));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              evidence.deliverNow("incidents");
            }
          });
    }
  }
}
