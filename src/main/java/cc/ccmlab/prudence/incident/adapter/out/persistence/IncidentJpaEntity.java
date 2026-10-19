package cc.ccmlab.prudence.incident.adapter.out.persistence;

import cc.ccmlab.prudence.incident.domain.CausalLink;
import cc.ccmlab.prudence.incident.domain.Incident;
import cc.ccmlab.prudence.incident.domain.IncidentCategory;
import cc.ccmlab.prudence.incident.domain.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/// JPA mapping of an incident; kept apart from the domain model (hexagonal boundary).
@Entity
@Table(name = "incident")
public class IncidentJpaEntity {
  @Id private String id;
  private String aiSystemId;

  @Column(length = 500)
  private String title;

  @Column(length = 4000)
  private String description;

  private String occurredAt;
  private String detectedAt;
  private String reportedBy;

  @Enumerated(EnumType.STRING)
  private Incident.RegulatoryRole regulatoryRole;

  private String memberStates; // comma-separated

  @Enumerated(EnumType.STRING)
  private IncidentCategory category;

  private boolean widespreadInfringement;

  @Enumerated(EnumType.STRING)
  private CausalLink causalLink;

  @Enumerated(EnumType.STRING)
  private IncidentStatus status;

  private String initialReportRecipient;
  private String initialReportReference;
  private String initialReportSubmittedAt;
  private String initialReportRecordedBy;
  private String finalReportRecipient;
  private String finalReportReference;
  private String finalReportSubmittedAt;
  private String finalReportRecordedBy;

  @Column(length = 4000)
  private String rootCause;

  @Column(length = 4000)
  private String correctiveActions;

  private String closedAt;
  private String closedBy;

  @Column(length = 2000)
  private String closingNote;

  protected IncidentJpaEntity() {}

  static IncidentJpaEntity from(Incident i) {
    IncidentJpaEntity e = new IncidentJpaEntity();
    e.id = i.id();
    e.aiSystemId = i.aiSystemId();
    e.title = i.title();
    e.description = i.description();
    e.occurredAt = i.occurredAt();
    e.detectedAt = i.detectedAt();
    e.reportedBy = i.reportedBy();
    e.regulatoryRole = i.regulatoryRole();
    e.memberStates = String.join(",", i.memberStates());
    e.category = i.category();
    e.widespreadInfringement = i.widespreadInfringement();
    e.causalLink = i.causalLink();
    e.status = i.status();
    if (i.initialReport() != null) {
      e.initialReportRecipient = i.initialReport().recipient();
      e.initialReportReference = i.initialReport().reference();
      e.initialReportSubmittedAt = i.initialReport().submittedAt();
      e.initialReportRecordedBy = i.initialReport().recordedBy();
    }
    if (i.finalReport() != null) {
      e.finalReportRecipient = i.finalReport().recipient();
      e.finalReportReference = i.finalReport().reference();
      e.finalReportSubmittedAt = i.finalReport().submittedAt();
      e.finalReportRecordedBy = i.finalReport().recordedBy();
    }
    e.rootCause = i.rootCause();
    e.correctiveActions = i.correctiveActions();
    e.closedAt = i.closedAt();
    e.closedBy = i.closedBy();
    e.closingNote = i.closingNote();
    return e;
  }

  Incident toDomain() {
    Incident.Report initial =
        initialReportReference == null
            ? null
            : new Incident.Report(
                initialReportRecipient,
                initialReportReference,
                initialReportSubmittedAt,
                initialReportRecordedBy);
    Incident.Report fin =
        finalReportReference == null
            ? null
            : new Incident.Report(
                finalReportRecipient,
                finalReportReference,
                finalReportSubmittedAt,
                finalReportRecordedBy);
    return Incident.rehydrate(
        id,
        aiSystemId,
        title,
        description,
        occurredAt,
        detectedAt,
        reportedBy,
        regulatoryRole,
        memberStates == null || memberStates.isBlank()
            ? List.of()
            : List.of(memberStates.split(",")),
        category,
        widespreadInfringement,
        causalLink,
        status,
        initial,
        fin,
        rootCause,
        correctiveActions,
        closedAt,
        closedBy,
        closingNote);
  }
}
