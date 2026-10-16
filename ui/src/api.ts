// Prudence API client — talks to the Spring Boot backend via the vite /api proxy.
import { getToken } from "./auth";

const json_ct = { "Content-Type": "application/json" };

// A stale access token used to leave the console rendered as authenticated and
// made each page report a misleading backend error. The application shell owns
// the redirect to sign-in; this event lets every API helper notify it uniformly.
const unauthorized = () =>
  window.dispatchEvent(new Event("prudence:unauthorized"));

function withAuth(
  headers: Record<string, string> = {},
): Record<string, string> {
  const t = getToken();
  return t ? { ...headers, Authorization: `Bearer ${t}` } : headers;
}

export type RiskTier = "PROHIBITED" | "HIGH" | "LIMITED" | "MINIMAL";
export type RegulatoryRole =
  | "PROVIDER"
  | "DEPLOYER"
  | "IMPORTER"
  | "DISTRIBUTOR";

export interface AiSystem {
  id: string;
  name: string;
  intendedPurpose: string;
  ownerName: string;
  ownerEmail: string;
  riskTier: RiskTier;
  annexIII: boolean;
  approvalStatus:
    | "DRAFT"
    | "UNDER_ASSESSMENT"
    | "EVIDENCE_PENDING"
    | "PENDING_APPROVAL"
    | "PENDING_SECOND_APPROVAL"
    | "APPROVED"
    | "EXPIRED"
    | "REJECTED"
    | "BLOCKED"
    | "SUSPENDED"
    | "RETIRED";
  regulatoryRole: RegulatoryRole;
  systemVersion: string;
  modelHash: string;
  annexI: boolean;
  operatingGeography: string;
  affectedPopulation: string;
  classificationJustification: string;
  obligations: string[];
  assessedAt: string;
  approvedAt: string | null;
  approvalExpiresAt: string | null;
  firstApprover: string | null;
  firstApprovedAt: string | null;
  renewalFirstApprover: string | null;
  renewalFirstApprovedAt: string | null;
  /// Renewal notice issued: the approval is inside its review window.
  reviewDue: boolean;
}

export type PostureStatus = "GREEN" | "AMBER" | "RED";

export interface SystemPosture {
  id: string;
  name: string;
  riskTier: RiskTier;
  approvalStatus: AiSystem["approvalStatus"];
  status: PostureStatus;
  detail: string;
}

export interface Posture {
  overall: PostureStatus;
  evidenceHealthy: boolean;
  evidenceDetail: string;
  systems: SystemPosture[];
}

export interface DriftFinding {
  aiSystemId: string;
  name: string;
  approvalStatus: string;
  executionCount: number;
  severity: "VIOLATION" | "OK";
  detail: string;
}
/// `complete` false: the evidence scan stopped early, so "no violations" proves nothing.
export interface Drift {
  generatedAt: string;
  hasViolations: boolean;
  complete: boolean;
  scanDetail: string | null;
  findings: DriftFinding[];
}

export interface RegisterInput {
  name: string;
  intendedPurpose: string;
  ownerName: string;
  ownerEmail: string;
  riskTier: RiskTier;
  regulatoryRole: RegulatoryRole;
  systemVersion: string;
  modelHash: string;
  annexI: boolean;
  annexIII: boolean;
  operatingGeography: string;
  affectedPopulation: string;
  classificationJustification: string;
  obligations: string[];
}

export type ComplianceControlStatus =
  | "NOT_STARTED"
  | "EVIDENCE_REQUESTED"
  | "SATISFIED"
  | "EXPIRED"
  | "FAILED"
  | "WAIVED";
export interface ComplianceControl {
  id: string;
  profileId: string;
  key: string;
  title: string;
  requirement: string;
  blocking: boolean;
  accepts: string;
  expiresAfterDays: number | null;
  expiresOnVersionChange: boolean;
  blocksVersionChange: boolean;
  evidenceSource: string;
  status: ComplianceControlStatus;
  evidenceUri: string | null;
  evidenceSha256: string | null;
  evidenceVersion: string | null;
  evidenceOwner: string | null;
  satisfiedAt: string | null;
  evidenceDatasetVersion: string | null;
  evidenceMetrics: string | null;
  evidenceApprover: string | null;
  evidenceAttestation: string | null;
  ernestReceipt: string | null;
  expiresAt: string | null;
  createdAt: string;
}
export interface ComplianceEvidenceInput {
  evidenceUri: string;
  evidenceSha256: string;
  evidenceVersion: string;
  evidenceOwner: string;
  datasetVersion?: string;
  metricsByGroup?: string;
  approver?: string;
  signedAttestation?: string;
  ernestReceipt?: string;
  expiresAt?: string;
}

export interface Decision {
  decision: "ALLOW" | "DENY" | "APPROVAL_REQUIRED";
  reason?: string;
  approver?: string;
}

/// Where a piece of governance evidence stands on its way to Ernest. `sealed` (qualified QTSP
/// seal) is independent: a DELIVERED record may be recorded without one.
export type EvidenceDelivery = "PENDING" | "DELIVERED" | "DEAD";

export interface ReviewEvent {
  id: string;
  aiSystemId: string;
  action:
    | "APPROVE"
    | "FIRST_APPROVAL"
    | "REJECT"
    | "RENEW"
    | "FIRST_RENEWAL_APPROVAL"
    | "REVIEW_DUE"
    | "EXPIRE";
  reviewer: string;
  reason: string | null;
  at: string;
  previousStatus: string;
  newStatus: string;
  evidence: EvidenceDelivery;
  sealed: boolean;
  blockIndex: number | null;
}

export interface AttestationEntry {
  aiSystemId: string;
  name: string;
  riskTier: RiskTier;
  approvalStatus: AiSystem["approvalStatus"];
  posture: PostureStatus;
  detail: string;
}
export interface AttestationEvidence {
  sealed: boolean;
  blockIndex: number | null;
  modelId: string;
  detail: string;
  delivery: EvidenceDelivery;
}
export interface Attestation {
  id: string;
  generatedAt: string;
  overallPosture: PostureStatus;
  contentHash: string;
  systems: AttestationEntry[];
  evidence: AttestationEvidence;
}
export interface AttestationSummary {
  id: string;
  generatedAt: string;
  overallPosture: PostureStatus;
  contentHash: string;
  sealed: boolean;
  blockIndex: number | null;
  delivery: EvidenceDelivery;
}
export type Verdict =
  | "VERIFIED"
  | "ALTERED"
  | "PENDING"
  | "UNSEALED"
  | "UNCONFIRMED";
export interface Verification {
  attestationId: string;
  hashMatches: boolean;
  recomputedHash: string;
  storedHash: string;
  sealed: boolean;
  blockIndex: number | null;
  ernestConfirmed: boolean;
  chainValid: boolean;
  verdict: Verdict;
  detail: string;
}

export type IncidentCategory =
  | "DEATH"
  | "SERIOUS_HARM_TO_HEALTH"
  | "CRITICAL_INFRASTRUCTURE_DISRUPTION"
  | "FUNDAMENTAL_RIGHTS_INFRINGEMENT"
  | "PROPERTY_OR_ENVIRONMENT_HARM"
  | "NOT_SERIOUS";
export type CausalLink = "SUSPECTED" | "ESTABLISHED" | "RULED_OUT";
export type ReportingState =
  | "NOT_REQUIRED"
  | "DUE"
  | "OVERDUE"
  | "REPORTED_ON_TIME"
  | "REPORTED_LATE";
export interface IncidentReport {
  recipient: string;
  reference: string;
  submittedAt: string;
  recordedBy: string;
}
export interface IncidentEvent {
  id: string;
  incidentId: string;
  aiSystemId: string;
  type: string;
  actor: string;
  at: string;
  details: string;
  evidence: EvidenceDelivery;
  sealed: boolean;
  blockIndex: number | null;
}
export interface Incident {
  id: string;
  aiSystemId: string;
  title: string;
  description: string;
  occurredAt: string;
  detectedAt: string;
  reportedBy: string;
  regulatoryRole: RegulatoryRole;
  memberStates: string[];
  category: IncidentCategory;
  widespreadInfringement: boolean;
  causalLink: CausalLink;
  status:
    | "OPEN"
    | "INITIAL_REPORT_SUBMITTED"
    | "FINAL_REPORT_SUBMITTED"
    | "CLOSED";
  reportable: boolean;
  reportingDeadline: string | null;
  reportingState: ReportingState;
  reportingBasis: string;
  reportingRecipient: string;
  initialReport: IncidentReport | null;
  finalReport: IncidentReport | null;
  rootCause: string | null;
  correctiveActions: string | null;
  closedAt: string | null;
  closedBy: string | null;
  closingNote: string | null;
  timeline: IncidentEvent[];
}
export interface OpenIncidentInput {
  aiSystemId: string;
  title: string;
  description: string;
  occurredAt: string;
  detectedAt: string;
  memberStates: string[];
  category: IncidentCategory;
  widespreadInfringement: boolean;
  causalLink: CausalLink;
  suspendSystem: boolean;
}

export type GovernanceCaseType =
  | "ASSESSMENT"
  | "CHANGE"
  | "EXCEPTION"
  | "CORRECTIVE_ACTION";
export type GovernanceCaseStatus = "OPEN" | "IN_REVIEW" | "CLOSED";
export interface GovernanceEvidence {
  id: string;
  label: string;
  uri: string;
  sha256: string;
  version: string;
  owner: string;
  addedBy: string;
  addedAt: string;
}
export interface GovernanceCaseEvent {
  id: string;
  type: string;
  actor: string;
  at: string;
  details: string;
}
export interface GovernanceCase {
  id: string;
  aiSystemId: string;
  type: GovernanceCaseType;
  status: GovernanceCaseStatus;
  title: string;
  summary: string;
  owner: string;
  openedBy: string;
  openedAt: string;
  reviewStartedAt: string | null;
  closedAt: string | null;
  closedBy: string | null;
  closingDecision: string | null;
  evidence: GovernanceEvidence[];
  timeline: GovernanceCaseEvent[];
}
export type TransparencyKind =
  | "DIRECT_AI_INTERACTION"
  | "DEEPFAKE"
  | "EMOTION_RECOGNITION"
  | "BIOMETRIC_CATEGORISATION"
  | "PUBLIC_INTEREST_CONTENT";
export interface Fria {
  id: string;
  aiSystemId: string;
  jurisdiction: string;
  templateVersion: string;
  affectedGroups: string;
  assumptions: string;
  mitigations: string;
  dpiaUri: string;
  dpiaSha256: string;
  signedBy: string;
  signedAt: string;
}
export interface ImpactRequirement {
  friaRequired: boolean;
  transparencyRequired: boolean;
}
export interface TransparencyNotice {
  id: string;
  aiSystemId: string;
  kind: TransparencyKind;
  audience: string;
  noticeVersion: string;
  deploymentEvidence: string;
  deployedBy: string;
  deployedAt: string;
}
export type RiskSignal =
  | "INCIDENT_RATE"
  | "HUMAN_OVERRIDE_RATE"
  | "COMPLAINT_RATE"
  | "PERFORMANCE_DEGRADATION"
  | "BIAS_DRIFT";
export type MonitoringStatus = "GREEN" | "AMBER" | "RED";
export interface MonitoringSignal {
  id: string;
  aiSystemId: string;
  signal: RiskSignal;
  value: number;
  amberThreshold: number;
  redThreshold: number;
  status: MonitoringStatus;
  observedAt: string;
  sourceReference: string | null;
}
export interface MonitoringSummary {
  aiSystemId: string;
  status: MonitoringStatus;
  observations: number;
  redSignals: number;
  amberSignals: number;
  recentSignals: MonitoringSignal[];
}
export interface Capa {
  id: string;
  aiSystemId: string;
  signalId: string;
  risk: string;
  mitigation: string;
  owner: string;
  dueAt: string;
  status: string;
  effectivenessEvidence: string | null;
}
export type OversightRole =
  | "OWNER"
  | "SUPERVISOR"
  | "COMPLIANCE"
  | "DPO"
  | "SECURITY"
  | "PROVIDER";
export interface HumanAssignment {
  id: string;
  aiSystemId: string;
  role: OversightRole;
  person: string;
  overrideAuthority: boolean;
  stopAuthority: boolean;
  trainingEvidence: string;
  trainingExpiresAt: string;
}
export interface HumanOverride {
  id: string;
  aiSystemId: string;
  aosReference: string;
  supervisor: string;
  reason: string;
  outcome: string;
  occurredAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
}
export interface ComplianceSnapshot {
  id: string;
  aiSystemId: string;
  asOf: string;
  sha256: string;
  manifest: Record<string, unknown>;
}
export type ChangeOutcome =
  | "MINOR"
  | "CONTROL_REVIEW"
  | "SUBSTANTIAL"
  | "PROHIBITED";
export interface ChangeAssessment {
  id: string;
  aiSystemId: string;
  proposedVersion: string;
  changedFields: string;
  outcome: ChangeOutcome;
  rationale: string;
  evidenceUri: string;
  evidenceSha256: string;
  assessedAt: string;
  assessedBy: string;
}

/// A record the delivery worker gave up on: evidence (decisions/reviews/attestations/incidents)
/// that never reached Ernest, or an alert email that never went out.
export interface DeadLetter {
  target:
    | "decisions"
    | "reviews"
    | "attestations"
    | "incidents"
    | "alerts"
    | string;
  id: string;
  aiSystemId: string | null;
  attempts: number;
  deadAt: string;
  lastError: string | null;
}

async function json<T>(res: Response): Promise<T> {
  if (res.status === 401) {
    unauthorized();
    throw new Error("Your session has expired. Please sign in again.");
  }
  if (!res.ok && res.status >= 500) throw new Error(`${res.status}`);
  return res.json() as Promise<T>;
}

/// Strict variant: any non-2xx is an error, carrying the ProblemDetail `detail` when present.
/// Used for actions (approve/reject) where 403/409 must surface, unlike the PDP decide call
/// which deliberately reads 202/403 bodies.
async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) {
    if (res.status === 401) {
      unauthorized();
      throw new Error("Your session has expired. Please sign in again.");
    }
    let msg = `${res.status}`;
    try {
      const b = await res.json();
      if (b?.detail) msg = b.detail;
    } catch {
      /* ignore */
    }
    if (res.status === 403)
      msg = "You need the read-write role for this action.";
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

const post = <T>(url: string, body: unknown) =>
  fetch(url, {
    method: "POST",
    headers: withAuth(json_ct),
    body: JSON.stringify(body),
  }).then(ok<T>);

export const api = {
  assessChange: (body: {
    aiSystemId: string;
    proposedVersion: string;
    changedFields: string[];
    evidenceUri: string;
    evidenceSha256: string;
  }) => post<ChangeAssessment>("/api/governance/change-assessments", body),
  createSnapshot: (id: string) =>
    post<ComplianceSnapshot>(`/api/compliance-snapshots/systems/${id}`, {}),
  snapshot: (id: string) =>
    fetch(`/api/compliance-snapshots/${id}`, { headers: withAuth() }).then(
      ok<ComplianceSnapshot>,
    ),
  assignments: (id: string) =>
    fetch(`/api/oversight/systems/${id}/assignments`, {
      headers: withAuth(),
    }).then(ok<HumanAssignment[]>),
  addAssignment: (
    id: string,
    body: Omit<HumanAssignment, "id" | "aiSystemId">,
  ) => post<HumanAssignment>(`/api/oversight/systems/${id}/assignments`, body),
  overrides: (id: string) =>
    fetch(`/api/oversight/systems/${id}/overrides`, {
      headers: withAuth(),
    }).then(ok<HumanOverride[]>),
  addOverride: (
    id: string,
    body: {
      aosReference: string;
      supervisor: string;
      reason: string;
      outcome: string;
    },
  ) => post<HumanOverride>(`/api/oversight/systems/${id}/overrides`, body),
  reviewOverride: (id: string, body: { reviewer: string; note: string }) =>
    post<HumanOverride>(`/api/oversight/overrides/${id}/review`, body),
  monitoringSummary: (id: string) =>
    fetch(`/api/monitoring/signals/summary/${id}`, {
      headers: withAuth(),
    }).then(ok<MonitoringSummary>),
  addSignal: (body: {
    aiSystemId: string;
    signal: RiskSignal;
    value: number;
    amberThreshold: number;
    redThreshold: number;
    sourceReference: string;
  }) => post<MonitoringSignal>("/api/monitoring/signals", body),
  capas: (id: string) =>
    fetch(`/api/monitoring/capas/systems/${id}`, { headers: withAuth() }).then(
      ok<Capa[]>,
    ),
  openCapa: (body: {
    signalId: string;
    risk: string;
    mitigation: string;
    owner: string;
    dueAt: string;
  }) => post<Capa>("/api/monitoring/capas", body),
  implementCapa: (id: string) =>
    post<Capa>(`/api/monitoring/capas/${id}/implement`, {}),
  verifyCapa: (id: string, evidenceReference: string) =>
    post<Capa>(`/api/monitoring/capas/${id}/verify`, { evidenceReference }),
  impactRequirements: (id: string) =>
    fetch(`/api/impact/systems/${id}/requirements`, {
      headers: withAuth(),
    }).then(ok<ImpactRequirement>),
  setImpactRequirements: (id: string, body: ImpactRequirement) =>
    post<ImpactRequirement>(`/api/impact/systems/${id}/requirements`, body),
  frias: (id: string) =>
    fetch(`/api/impact/systems/${id}/fria`, { headers: withAuth() }).then(
      ok<Fria[]>,
    ),
  createFria: (body: Omit<Fria, "id" | "signedAt">) =>
    post<Fria>("/api/impact/fria", body),
  transparencyNotices: (id: string) =>
    fetch(`/api/impact/systems/${id}/transparency-notices`, {
      headers: withAuth(),
    }).then(ok<TransparencyNotice[]>),
  createTransparencyNotice: (
    body: Omit<TransparencyNotice, "id" | "deployedAt">,
  ) => post<TransparencyNotice>("/api/impact/transparency-notices", body),
  governanceCases: () =>
    fetch("/api/governance/cases", { headers: withAuth() }).then(
      ok<GovernanceCase[]>,
    ),
  governanceCase: (id: string) =>
    fetch(`/api/governance/cases/${id}`, { headers: withAuth() }).then(
      ok<GovernanceCase>,
    ),
  openGovernanceCase: (body: {
    aiSystemId: string;
    type: GovernanceCaseType;
    title: string;
    summary: string;
    owner: string;
  }) => post<GovernanceCase>("/api/governance/cases", body),
  startGovernanceReview: (id: string) =>
    post<GovernanceCase>(`/api/governance/cases/${id}/review`, {}),
  addGovernanceEvidence: (
    id: string,
    body: {
      label: string;
      uri: string;
      sha256: string;
      version: string;
      owner: string;
    },
  ) => post<GovernanceEvidence>(`/api/governance/cases/${id}/evidence`, body),
  closeGovernanceCase: (id: string, decision: string) =>
    post<GovernanceCase>(`/api/governance/cases/${id}/close`, { decision }),
  deadLetters: () =>
    fetch("/api/evidence/dead-letters", { headers: withAuth() }).then(
      ok<DeadLetter[]>,
    ),
  retryDeadLetter: (target: string, id: string) =>
    fetch(`/api/evidence/dead-letters/${target}/${id}/retry`, {
      method: "POST",
      headers: withAuth(json_ct),
    }).then(async (r) => {
      if (!r.ok) await ok<unknown>(r);
    }),
  incidents: () =>
    fetch("/api/incidents", { headers: withAuth() }).then(ok<Incident[]>),
  incident: (id: string) =>
    fetch(`/api/incidents/${id}`, { headers: withAuth() }).then(ok<Incident>),
  openIncident: (body: OpenIncidentInput) =>
    post<Incident>("/api/incidents", body),
  triageIncident: (
    id: string,
    body: {
      category: IncidentCategory;
      widespreadInfringement: boolean;
      causalLink: CausalLink;
      note: string;
    },
  ) => post<Incident>(`/api/incidents/${id}/triage`, body),
  initialReport: (
    id: string,
    body: { recipient: string; reference: string; submittedAt: string },
  ) => post<Incident>(`/api/incidents/${id}/initial-report`, body),
  finalReport: (
    id: string,
    body: {
      recipient: string;
      reference: string;
      submittedAt: string;
      rootCause: string;
      correctiveActions: string;
    },
  ) => post<Incident>(`/api/incidents/${id}/final-report`, body),
  closeIncident: (id: string, note: string) =>
    post<Incident>(`/api/incidents/${id}/close`, { note }),
  listSystems: () =>
    fetch("/api/registry/systems", { headers: withAuth() }).then(
      json<AiSystem[]>,
    ),
  registerSystem: (body: RegisterInput) =>
    fetch("/api/registry/systems", {
      method: "POST",
      headers: withAuth(json_ct),
      body: JSON.stringify(body),
    }).then(json<AiSystem>),
  decide: (body: {
    subject: string;
    pipeline: string;
    aiSystemId: string;
    action: string;
  }) =>
    fetch("/api/policy/decisions", {
      method: "POST",
      headers: withAuth(json_ct),
      body: JSON.stringify(body),
    }).then(json<Decision>),
  approveSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/approve`, {
      method: "POST",
      headers: withAuth(json_ct),
    }).then(ok<AiSystem>),
  rejectSystem: (id: string, reason: string) =>
    fetch(`/api/registry/systems/${id}/reject`, {
      method: "POST",
      headers: withAuth(json_ct),
      body: JSON.stringify({ reason }),
    }).then(ok<AiSystem>),
  renewSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/renew`, {
      method: "POST",
      headers: withAuth(json_ct),
    }).then(ok<AiSystem>),
  submitSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/submit`, {
      method: "POST",
      headers: withAuth(json_ct),
    }).then(ok<AiSystem>),
  complianceControls: (id: string) =>
    fetch(`/api/registry/systems/${id}/compliance-controls`, {
      headers: withAuth(),
    }).then(ok<ComplianceControl[]>),
  satisfyComplianceControl: (
    systemId: string,
    controlId: string,
    body: ComplianceEvidenceInput,
  ) =>
    post<ComplianceControl>(
      `/api/registry/systems/${systemId}/compliance-controls/${controlId}/satisfy`,
      body,
    ),
  posture: () =>
    fetch("/api/posture", { headers: withAuth() }).then(json<Posture>),
  systemReviews: (id: string) =>
    fetch(`/api/registry/systems/${id}/reviews`, { headers: withAuth() }).then(
      json<ReviewEvent[]>,
    ),
  drift: () =>
    fetch("/api/posture/drift", { headers: withAuth() }).then(json<Drift>),
  listAttestations: () =>
    fetch("/api/attestations", { headers: withAuth() }).then(
      json<AttestationSummary[]>,
    ),
  getAttestation: (id: string) =>
    fetch(`/api/attestations/${id}`, { headers: withAuth() }).then(
      json<Attestation>,
    ),
  verifyAttestation: (id: string) =>
    fetch(`/api/attestations/${id}/verify`, { headers: withAuth() }).then(
      json<Verification>,
    ),
  generateAttestation: () =>
    fetch("/api/attestations", {
      method: "POST",
      headers: withAuth(json_ct),
    }).then(ok<Attestation>),
};
