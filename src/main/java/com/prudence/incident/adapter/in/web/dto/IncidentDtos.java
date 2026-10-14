package com.prudence.incident.adapter.in.web.dto;

import com.prudence.incident.application.port.in.IncidentUseCase.OpenIncidentCommand;
import com.prudence.incident.domain.CausalLink;
import com.prudence.incident.domain.Incident;
import com.prudence.incident.domain.IncidentCategory;
import com.prudence.incident.domain.IncidentEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class IncidentDtos {
  private IncidentDtos() {}

  public record OpenIncidentRequest(
      @NotBlank String aiSystemId,
      @NotBlank String title,
      @NotBlank String description,
      @NotNull Instant occurredAt,
      @NotNull Instant detectedAt,
      @NotEmpty List<@NotBlank String> memberStates,
      @NotNull IncidentCategory category,
      boolean widespreadInfringement,
      @NotNull CausalLink causalLink,
      boolean suspendSystem) {
    public OpenIncidentCommand toCommand() {
      return new OpenIncidentCommand(
          aiSystemId,
          title,
          description,
          occurredAt,
          detectedAt,
          memberStates,
          category,
          widespreadInfringement,
          causalLink,
          suspendSystem);
    }
  }

  public record TriageRequest(
      @NotNull IncidentCategory category,
      boolean widespreadInfringement,
      @NotNull CausalLink causalLink,
      @NotBlank String note) {}

  /// `recipient`: the authority (or provider) it was filed with; `reference`: their case number.
  public record ReportRequest(
      @NotBlank String recipient, @NotBlank String reference, @NotNull Instant submittedAt) {
    public Incident.Report toReport() {
      return new Incident.Report(recipient, reference, submittedAt.toString(), null);
    }
  }

  public record FinalReportRequest(
      @NotBlank String recipient,
      @NotBlank String reference,
      @NotNull Instant submittedAt,
      @NotBlank String rootCause,
      @NotBlank String correctiveActions) {}

  public record CloseRequest(@NotBlank String note) {}

  public record IncidentResponse(
      String id,
      String aiSystemId,
      String title,
      String description,
      String occurredAt,
      String detectedAt,
      String reportedBy,
      String regulatoryRole,
      List<String> memberStates,
      String category,
      boolean widespreadInfringement,
      String causalLink,
      String status,
      boolean reportable,
      String reportingDeadline,
      String reportingState,
      String reportingBasis,
      String reportingRecipient,
      Incident.Report initialReport,
      Incident.Report finalReport,
      String rootCause,
      String correctiveActions,
      String closedAt,
      String closedBy,
      String closingNote,
      List<IncidentEvent> timeline) {

    public static IncidentResponse from(Incident i, List<IncidentEvent> timeline) {
      Instant deadline = i.reportingDeadline();
      return new IncidentResponse(
          i.id(),
          i.aiSystemId(),
          i.title(),
          i.description(),
          i.occurredAt(),
          i.detectedAt(),
          i.reportedBy(),
          i.regulatoryRole().name(),
          i.memberStates(),
          i.category().name(),
          i.widespreadInfringement(),
          i.causalLink().name(),
          i.status().name(),
          i.reportable(),
          deadline == null ? null : deadline.toString(),
          i.reportingState(Instant.now()).name(),
          i.reportingBasis(),
          i.reportingRecipient(),
          i.initialReport(),
          i.finalReport(),
          i.rootCause(),
          i.correctiveActions(),
          i.closedAt(),
          i.closedBy(),
          i.closingNote(),
          timeline);
    }
  }
}
