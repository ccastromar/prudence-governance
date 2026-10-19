package cc.ccmlab.prudence.incident.domain;

import cc.ccmlab.prudence.shared.exception.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// An entry in the AI incident register. Prudence does not file anything with authorities: it
/// records what happened, when the organisation became aware of it, what the AI Act requires
/// in response, and who filed which report when — so a missed deadline is visible (and
/// alerted) before it is missed, and the register itself is evidence.
///
/// Deadlines follow Regulation (EU) 2024/1689, Art. 73, counted from awareness (`detectedAt`).
/// This is governance support, not legal advice: the classification is a human judgement.
public final class Incident {

  /// Who the organisation must notify, which depends on its role for the system.
  public enum RegulatoryRole {
    PROVIDER,
    DEPLOYER,
    IMPORTER,
    DISTRIBUTOR
  }

  /// A report filed with an authority (or, for a deployer, the provider).
  public record Report(String recipient, String reference, String submittedAt, String recordedBy) {}

  public enum ReportingState {
    NOT_REQUIRED,
    DUE,
    OVERDUE,
    REPORTED_ON_TIME,
    REPORTED_LATE
  }

  private final String id;
  private final String aiSystemId;
  private final String title;
  private final String description;
  private final String occurredAt;
  private final String detectedAt;
  private final String reportedBy;
  private final RegulatoryRole regulatoryRole;
  private final List<String> memberStates;
  private IncidentCategory category;
  private boolean widespreadInfringement;
  private CausalLink causalLink;
  private IncidentStatus status;
  private Report initialReport;
  private Report finalReport;
  private String rootCause;
  private String correctiveActions;
  private String closedAt;
  private String closedBy;
  private String closingNote;

  private Incident(
      String id,
      String aiSystemId,
      String title,
      String description,
      String occurredAt,
      String detectedAt,
      String reportedBy,
      RegulatoryRole regulatoryRole,
      List<String> memberStates,
      IncidentCategory category,
      boolean widespreadInfringement,
      CausalLink causalLink,
      IncidentStatus status,
      Report initialReport,
      Report finalReport,
      String rootCause,
      String correctiveActions,
      String closedAt,
      String closedBy,
      String closingNote) {
    this.id = id;
    this.aiSystemId = aiSystemId;
    this.title = title;
    this.description = description;
    this.occurredAt = occurredAt;
    this.detectedAt = detectedAt;
    this.reportedBy = reportedBy;
    this.regulatoryRole = regulatoryRole;
    this.memberStates = List.copyOf(memberStates);
    this.category = category;
    this.widespreadInfringement = widespreadInfringement;
    this.causalLink = causalLink;
    this.status = status;
    this.initialReport = initialReport;
    this.finalReport = finalReport;
    this.rootCause = rootCause;
    this.correctiveActions = correctiveActions;
    this.closedAt = closedAt;
    this.closedBy = closedBy;
    this.closingNote = closingNote;
  }

  public static Incident open(
      String id,
      String aiSystemId,
      String title,
      String description,
      Instant occurredAt,
      Instant detectedAt,
      String reportedBy,
      RegulatoryRole role,
      List<String> memberStates,
      IncidentCategory category,
      boolean widespreadInfringement,
      CausalLink causalLink,
      Instant now) {
    require(title, "title");
    require(description, "description");
    if (occurredAt == null || detectedAt == null)
      throw new IllegalArgumentException("occurredAt and detectedAt required");
    if (detectedAt.isBefore(occurredAt))
      throw new IllegalArgumentException("detectedAt cannot precede occurredAt");
    if (detectedAt.isAfter(now))
      throw new IllegalArgumentException("detectedAt cannot be in the future");
    if (category == null || causalLink == null || role == null)
      throw new IllegalArgumentException("category, causal link and role required");
    if (memberStates == null
        || memberStates.isEmpty()
        || memberStates.stream().anyMatch(s -> s == null || s.isBlank())) {
      throw new IllegalArgumentException(
          "at least one Member State where the incident occurred is required");
    }
    return new Incident(
        id,
        aiSystemId,
        title.strip(),
        description.strip(),
        occurredAt.toString(),
        detectedAt.toString(),
        reportedBy,
        role,
        memberStates.stream().map(String::strip).toList(),
        category,
        widespreadInfringement,
        causalLink,
        IncidentStatus.OPEN,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static Incident rehydrate(
      String id,
      String aiSystemId,
      String title,
      String description,
      String occurredAt,
      String detectedAt,
      String reportedBy,
      RegulatoryRole role,
      List<String> memberStates,
      IncidentCategory category,
      boolean widespreadInfringement,
      CausalLink causalLink,
      IncidentStatus status,
      Report initialReport,
      Report finalReport,
      String rootCause,
      String correctiveActions,
      String closedAt,
      String closedBy,
      String closingNote) {
    return new Incident(
        id,
        aiSystemId,
        title,
        description,
        occurredAt,
        detectedAt,
        reportedBy,
        role,
        memberStates,
        category,
        widespreadInfringement,
        causalLink,
        status,
        initialReport,
        finalReport,
        rootCause,
        correctiveActions,
        closedAt,
        closedBy,
        closingNote);
  }

  /// Serious incident whose causal link to the AI system is not ruled out → Art. 73 applies.
  public boolean reportable() {
    return category.isSerious() && causalLink != CausalLink.RULED_OUT;
  }

  /// The latest date the (initial) report may be filed, or null if no report is required.
  /// Art. 73(4): death → 10 days; Art. 73(3): widespread infringement or critical
  /// infrastructure → 2 days; Art. 73(2): otherwise 15 days. All counted from awareness.
  public Instant reportingDeadline() {
    if (!reportable()) return null;
    int days =
        (widespreadInfringement || category == IncidentCategory.CRITICAL_INFRASTRUCTURE_DISRUPTION)
            ? 2
            : category == IncidentCategory.DEATH ? 10 : 15;
    return Instant.parse(detectedAt).plus(Duration.ofDays(days));
  }

  /// The Art. 73 basis for the deadline, for people reading the register.
  public String reportingBasis() {
    if (!category.isSerious())
      return "Not a serious incident (Art. 3(49)) — no Art. 73 report required.";
    if (causalLink == CausalLink.RULED_OUT)
      return "Causal link to the AI system ruled out — no Art. 73 report required.";
    if (widespreadInfringement || category == IncidentCategory.CRITICAL_INFRASTRUCTURE_DISRUPTION) {
      return "Art. 73(3): widespread infringement or critical-infrastructure disruption — report"
          + " within 2 days of awareness.";
    }
    if (category == IncidentCategory.DEATH)
      return "Art. 73(4): death of a person — report within 10 days of awareness.";
    return "Art. 73(2): serious incident — report within 15 days of awareness.";
  }

  /// Who must be told, by the organisation's role for the system.
  public String reportingRecipient() {
    String states = String.join(", ", memberStates);
    return switch (regulatoryRole) {
      case PROVIDER -> "Market surveillance authority of " + states + " (Art. 73(1)).";
      case DEPLOYER ->
          "The provider immediately, then the importer/distributor and the market surveillance "
              + "authority of "
              + states
              + " (Art. 26(5)).";
      case IMPORTER, DISTRIBUTOR ->
          "The provider and the market surveillance authority of " + states + ".";
    };
  }

  public ReportingState reportingState(Instant now) {
    Instant deadline = reportingDeadline();
    if (deadline == null) return ReportingState.NOT_REQUIRED;
    String firstReport =
        initialReport != null
            ? initialReport.submittedAt()
            : finalReport != null ? finalReport.submittedAt() : null;
    if (firstReport != null) {
      return Instant.parse(firstReport).isAfter(deadline)
          ? ReportingState.REPORTED_LATE
          : ReportingState.REPORTED_ON_TIME;
    }
    return now.isAfter(deadline) ? ReportingState.OVERDUE : ReportingState.DUE;
  }

  /// Re-assess the incident while nothing has been filed yet. A deadline may move earlier;
  /// it never resets, because it always runs from the original awareness date.
  public void triage(
      IncidentCategory category, boolean widespreadInfringement, CausalLink causalLink) {
    if (status != IncidentStatus.OPEN) {
      throw new DomainException(
          "an incident can only be re-triaged before any report is filed (was " + status + ")");
    }
    if (category == null || causalLink == null)
      throw new IllegalArgumentException("category and causal link required");
    this.category = category;
    this.widespreadInfringement = widespreadInfringement;
    this.causalLink = causalLink;
  }

  /// Art. 73(5): an initial, possibly incomplete report, followed later by the complete one.
  public void recordInitialReport(Report report) {
    requireReport(report);
    if (!reportable()) throw new DomainException("this incident carries no reporting obligation");
    if (status != IncidentStatus.OPEN)
      throw new DomainException("an initial report was already recorded (was " + status + ")");
    this.initialReport = report;
    this.status = IncidentStatus.INITIAL_REPORT_SUBMITTED;
  }

  public void recordFinalReport(Report report, String rootCause, String correctiveActions) {
    requireReport(report);
    require(rootCause, "root cause");
    require(correctiveActions, "corrective actions");
    if (!reportable()) throw new DomainException("this incident carries no reporting obligation");
    if (status != IncidentStatus.OPEN && status != IncidentStatus.INITIAL_REPORT_SUBMITTED) {
      throw new DomainException("the final report was already recorded (was " + status + ")");
    }
    this.finalReport = report;
    this.rootCause = rootCause.strip();
    this.correctiveActions = correctiveActions.strip();
    this.status = IncidentStatus.FINAL_REPORT_SUBMITTED;
  }

  /// A reportable incident closes only after its complete report; any other closes with a
  /// note explaining why no report was needed.
  public void close(String actor, String note, Instant now) {
    require(note, "closing note");
    if (status == IncidentStatus.CLOSED) throw new DomainException("incident already closed");
    if (reportable() && status != IncidentStatus.FINAL_REPORT_SUBMITTED) {
      throw new DomainException(
          "a reportable incident can only be closed after its final report is recorded");
    }
    this.status = IncidentStatus.CLOSED;
    this.closedAt = now.toString();
    this.closedBy = actor;
    this.closingNote = note.strip();
  }

  private static void requireReport(Report report) {
    if (report == null) throw new IllegalArgumentException("report required");
    require(report.recipient(), "report recipient");
    require(report.reference(), "report reference");
    require(report.submittedAt(), "report submission time");
    Instant.parse(report.submittedAt()); // must be an ISO-8601 instant
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
  }

  public String id() {
    return id;
  }

  public String aiSystemId() {
    return aiSystemId;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public String occurredAt() {
    return occurredAt;
  }

  public String detectedAt() {
    return detectedAt;
  }

  public String reportedBy() {
    return reportedBy;
  }

  public RegulatoryRole regulatoryRole() {
    return regulatoryRole;
  }

  public List<String> memberStates() {
    return memberStates;
  }

  public IncidentCategory category() {
    return category;
  }

  public boolean widespreadInfringement() {
    return widespreadInfringement;
  }

  public CausalLink causalLink() {
    return causalLink;
  }

  public IncidentStatus status() {
    return status;
  }

  public Report initialReport() {
    return initialReport;
  }

  public Report finalReport() {
    return finalReport;
  }

  public String rootCause() {
    return rootCause;
  }

  public String correctiveActions() {
    return correctiveActions;
  }

  public String closedAt() {
    return closedAt;
  }

  public String closedBy() {
    return closedBy;
  }

  public String closingNote() {
    return closingNote;
  }
}
