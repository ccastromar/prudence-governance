// Prudence API client — talks to the Spring Boot backend via the vite /api proxy.
import { getToken } from './auth';

const json_ct = { 'Content-Type': 'application/json' };
function withAuth(headers: Record<string, string> = {}): Record<string, string> {
  const t = getToken();
  return t ? { ...headers, Authorization: `Bearer ${t}` } : headers;
}

export type RiskTier = 'PROHIBITED' | 'HIGH' | 'LIMITED' | 'MINIMAL';
export type RegulatoryRole = 'PROVIDER' | 'DEPLOYER' | 'IMPORTER' | 'DISTRIBUTOR';

export interface AiSystem {
  id: string;
  name: string;
  intendedPurpose: string;
  ownerName: string;
  ownerEmail: string;
  riskTier: RiskTier;
  annexIII: boolean;
  approvalStatus: 'DRAFT' | 'UNDER_ASSESSMENT' | 'EVIDENCE_PENDING' | 'PENDING_APPROVAL' | 'PENDING_SECOND_APPROVAL' | 'APPROVED' | 'EXPIRED' | 'REJECTED' | 'BLOCKED' | 'SUSPENDED' | 'RETIRED';
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

export type PostureStatus = 'GREEN' | 'AMBER' | 'RED';

export interface SystemPosture {
  id: string; name: string; riskTier: RiskTier;
  approvalStatus: AiSystem['approvalStatus'];
  status: PostureStatus; detail: string;
}

export interface Posture {
  overall: PostureStatus;
  evidenceHealthy: boolean;
  evidenceDetail: string;
  systems: SystemPosture[];
}

export interface DriftFinding {
  aiSystemId: string; name: string; approvalStatus: string;
  executionCount: number; severity: 'VIOLATION' | 'OK'; detail: string;
}
export interface Drift { generatedAt: string; hasViolations: boolean; findings: DriftFinding[]; }

export interface RegisterInput {
  name: string; intendedPurpose: string; ownerName: string; ownerEmail: string; riskTier: RiskTier;
  regulatoryRole: RegulatoryRole; systemVersion: string; modelHash: string;
  annexI: boolean; annexIII: boolean; operatingGeography: string; affectedPopulation: string;
  classificationJustification: string; obligations: string[];
}

export interface Decision { decision: 'ALLOW' | 'DENY' | 'APPROVAL_REQUIRED'; reason?: string; approver?: string; }

/// Where a piece of governance evidence stands on its way to Ernest. `sealed` (qualified QTSP
/// seal) is independent: a DELIVERED record may be recorded without one.
export type EvidenceDelivery = 'PENDING' | 'DELIVERED' | 'DEAD';

export interface ReviewEvent {
  id: string; aiSystemId: string; action: 'APPROVE' | 'FIRST_APPROVAL' | 'REJECT' | 'RENEW' | 'FIRST_RENEWAL_APPROVAL' | 'REVIEW_DUE' | 'EXPIRE'; reviewer: string;
  reason: string | null; at: string; previousStatus: string; newStatus: string;
  evidence: EvidenceDelivery; sealed: boolean; blockIndex: number | null;
}

export interface AttestationEntry {
  aiSystemId: string; name: string; riskTier: RiskTier;
  approvalStatus: AiSystem['approvalStatus']; posture: PostureStatus; detail: string;
}
export interface AttestationEvidence { sealed: boolean; blockIndex: number | null; modelId: string; detail: string; delivery: EvidenceDelivery; }
export interface Attestation {
  id: string; generatedAt: string; overallPosture: PostureStatus; contentHash: string;
  systems: AttestationEntry[]; evidence: AttestationEvidence;
}
export interface AttestationSummary {
  id: string; generatedAt: string; overallPosture: PostureStatus; contentHash: string;
  sealed: boolean; blockIndex: number | null; delivery: EvidenceDelivery;
}
export type Verdict = 'VERIFIED' | 'ALTERED' | 'PENDING' | 'UNSEALED' | 'UNCONFIRMED';
export interface Verification {
  attestationId: string; hashMatches: boolean; recomputedHash: string; storedHash: string;
  sealed: boolean; blockIndex: number | null; ernestConfirmed: boolean; chainValid: boolean;
  verdict: Verdict; detail: string;
}

export type IncidentCategory = 'DEATH' | 'SERIOUS_HARM_TO_HEALTH' | 'CRITICAL_INFRASTRUCTURE_DISRUPTION'
  | 'FUNDAMENTAL_RIGHTS_INFRINGEMENT' | 'PROPERTY_OR_ENVIRONMENT_HARM' | 'NOT_SERIOUS';
export type CausalLink = 'SUSPECTED' | 'ESTABLISHED' | 'RULED_OUT';
export type ReportingState = 'NOT_REQUIRED' | 'DUE' | 'OVERDUE' | 'REPORTED_ON_TIME' | 'REPORTED_LATE';
export interface IncidentReport { recipient: string; reference: string; submittedAt: string; recordedBy: string; }
export interface IncidentEvent {
  id: string; incidentId: string; aiSystemId: string; type: string; actor: string; at: string; details: string;
  evidence: EvidenceDelivery; sealed: boolean; blockIndex: number | null;
}
export interface Incident {
  id: string; aiSystemId: string; title: string; description: string; occurredAt: string; detectedAt: string;
  reportedBy: string; regulatoryRole: RegulatoryRole; memberStates: string[]; category: IncidentCategory;
  widespreadInfringement: boolean; causalLink: CausalLink;
  status: 'OPEN' | 'INITIAL_REPORT_SUBMITTED' | 'FINAL_REPORT_SUBMITTED' | 'CLOSED';
  reportable: boolean; reportingDeadline: string | null; reportingState: ReportingState;
  reportingBasis: string; reportingRecipient: string;
  initialReport: IncidentReport | null; finalReport: IncidentReport | null;
  rootCause: string | null; correctiveActions: string | null;
  closedAt: string | null; closedBy: string | null; closingNote: string | null;
  timeline: IncidentEvent[];
}
export interface OpenIncidentInput {
  aiSystemId: string; title: string; description: string; occurredAt: string; detectedAt: string;
  memberStates: string[]; category: IncidentCategory; widespreadInfringement: boolean; causalLink: CausalLink;
  suspendSystem: boolean;
}

/// A record the delivery worker gave up on: evidence (decisions/reviews/attestations/incidents)
/// that never reached Ernest, or an alert email that never went out.
export interface DeadLetter {
  target: 'decisions' | 'reviews' | 'attestations' | 'incidents' | 'alerts' | string;
  id: string; aiSystemId: string | null; attempts: number; deadAt: string; lastError: string | null;
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok && res.status >= 500) throw new Error(`${res.status}`);
  return res.json() as Promise<T>;
}

/// Strict variant: any non-2xx is an error, carrying the ProblemDetail `detail` when present.
/// Used for actions (approve/reject) where 403/409 must surface, unlike the PDP decide call
/// which deliberately reads 202/403 bodies.
async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `${res.status}`;
    try { const b = await res.json(); if (b?.detail) msg = b.detail; } catch { /* ignore */ }
    if (res.status === 403) msg = 'You need the read-write role for this action.';
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

const post = <T,>(url: string, body: unknown) =>
  fetch(url, { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(ok<T>);

export const api = {
  deadLetters: () => fetch('/api/evidence/dead-letters', { headers: withAuth() }).then(ok<DeadLetter[]>),
  retryDeadLetter: (target: string, id: string) =>
    fetch(`/api/evidence/dead-letters/${target}/${id}/retry`, { method: 'POST', headers: withAuth(json_ct) })
      .then(async r => { if (!r.ok) await ok<unknown>(r); }),
  incidents: () => fetch('/api/incidents', { headers: withAuth() }).then(ok<Incident[]>),
  incident: (id: string) => fetch(`/api/incidents/${id}`, { headers: withAuth() }).then(ok<Incident>),
  openIncident: (body: OpenIncidentInput) => post<Incident>('/api/incidents', body),
  triageIncident: (id: string, body: { category: IncidentCategory; widespreadInfringement: boolean; causalLink: CausalLink; note: string }) =>
    post<Incident>(`/api/incidents/${id}/triage`, body),
  initialReport: (id: string, body: { recipient: string; reference: string; submittedAt: string }) =>
    post<Incident>(`/api/incidents/${id}/initial-report`, body),
  finalReport: (id: string, body: { recipient: string; reference: string; submittedAt: string; rootCause: string; correctiveActions: string }) =>
    post<Incident>(`/api/incidents/${id}/final-report`, body),
  closeIncident: (id: string, note: string) => post<Incident>(`/api/incidents/${id}/close`, { note }),
  listSystems: () => fetch('/api/registry/systems', { headers: withAuth() }).then(json<AiSystem[]>),
  registerSystem: (body: RegisterInput) =>
    fetch('/api/registry/systems', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<AiSystem>),
  decide: (body: { subject: string; pipeline: string; aiSystemId: string; action: string }) =>
    fetch('/api/policy/decisions', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<Decision>),
  approveSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/approve`, { method: 'POST', headers: withAuth(json_ct) }).then(ok<AiSystem>),
  rejectSystem: (id: string, reason: string) =>
    fetch(`/api/registry/systems/${id}/reject`, { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify({ reason }) }).then(ok<AiSystem>),
  renewSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/renew`, { method: 'POST', headers: withAuth(json_ct) }).then(ok<AiSystem>),
  submitSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/submit`, { method: 'POST', headers: withAuth(json_ct) }).then(ok<AiSystem>),
  posture: () => fetch('/api/posture', { headers: withAuth() }).then(json<Posture>),
  systemReviews: (id: string) => fetch(`/api/registry/systems/${id}/reviews`, { headers: withAuth() }).then(json<ReviewEvent[]>),
  drift: () => fetch('/api/posture/drift', { headers: withAuth() }).then(json<Drift>),
  listAttestations: () => fetch('/api/attestations', { headers: withAuth() }).then(json<AttestationSummary[]>),
  getAttestation: (id: string) => fetch(`/api/attestations/${id}`, { headers: withAuth() }).then(json<Attestation>),
  verifyAttestation: (id: string) => fetch(`/api/attestations/${id}/verify`, { headers: withAuth() }).then(json<Verification>),
  generateAttestation: () =>
    fetch('/api/attestations', { method: 'POST', headers: withAuth(json_ct) }).then(ok<Attestation>),
};
