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
  approvalStatus: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'BLOCKED';
}

export interface RegisterInput {
  name: string; intendedPurpose: string; ownerName: string; ownerEmail: string; riskTier: RiskTier;
}

export interface Decision { decision: 'ALLOW' | 'DENY' | 'APPROVAL_REQUIRED'; reason?: string; approver?: string; }

async function json<T>(res: Response): Promise<T> {
  if (!res.ok && res.status >= 500) throw new Error(`${res.status}`);
  return res.json() as Promise<T>;
}

export const api = {
  listSystems: () => fetch('/api/registry/systems', { headers: withAuth() }).then(json<AiSystem[]>),
  registerSystem: (body: RegisterInput) =>
    fetch('/api/registry/systems', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<AiSystem>),
  decide: (body: { subject: string; pipeline: string; aiSystemId: string; action: string }) =>
    fetch('/api/policy/decisions', { method: 'POST', headers: withAuth(json_ct), body: JSON.stringify(body) }).then(json<Decision>),
};
