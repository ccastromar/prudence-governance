// Prudence API client — talks to the Spring Boot backend via the vite /api proxy.
import { getToken } from './auth';

const json_ct = { 'Content-Type': 'application/json' };
function withAuth(headers: Record<string, string> = {}): Record<string, string> {
  const t = getToken();
  return t ? { ...headers, Authorization: `Bearer ${t}` } : headers;
}

export type RiskTier = 'PROHIBITED' | 'HIGH' | 'LIMITED' | 'MINIMAL';

export interface AiSystem {
  id: string;
  name: string;
  intendedPurpose: string;
  ownerName: string;
  ownerEmail: string;
  riskTier: RiskTier;
  annexIII: boolean;
  approvalStatus: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'BLOCKED';
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

export interface RegisterInput {
  name: string; intendedPurpose: string; ownerName: string; ownerEmail: string; riskTier: RiskTier;
}

export interface Decision { decision: 'ALLOW' | 'DENY' | 'APPROVAL_REQUIRED'; reason?: string; approver?: string; }

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
    if (res.status === 403) msg = 'You need the read-write role to review systems.';
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

export const api = {
  listSystems: () => fetch('/api/registry/systems', { headers: withAuth() }).then(json<AiSystem[]>),
  registerSystem: (body: RegisterInput) =>
    fetch('/api/registry/systems', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<AiSystem>),
  decide: (body: { subject: string; pipeline: string; aiSystemId: string; action: string }) =>
    fetch('/api/policy/decisions', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<Decision>),
  approveSystem: (id: string) =>
    fetch(`/api/registry/systems/${id}/approve`, { method: 'POST', headers: withAuth(json_ct) }).then(ok<AiSystem>),
  rejectSystem: (id: string, reason: string) =>
    fetch(`/api/registry/systems/${id}/reject`, { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify({ reason }) }).then(ok<AiSystem>),
  posture: () => fetch('/api/posture', { headers: withAuth() }).then(json<Posture>),
};
