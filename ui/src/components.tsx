import type { RiskTier } from './api';

const riskStyle: Record<RiskTier, React.CSSProperties> = {
  PROHIBITED: { background: 'var(--risk-prohibited-bg)', color: 'var(--risk-prohibited)' },
  HIGH: { background: 'var(--risk-high-bg)', color: 'var(--risk-high)' },
  LIMITED: { background: 'var(--risk-limited-bg)', color: 'var(--risk-limited)' },
  MINIMAL: { background: 'var(--risk-minimal-bg)', color: 'var(--risk-minimal)' },
};

export function RiskBadge({ tier, annexIII }: { tier: RiskTier; annexIII?: boolean }) {
  return <span className="badge" style={riskStyle[tier]}>{tier}{annexIII ? ' · Annex III' : ''}</span>;
}

const statusStyle: Record<string, React.CSSProperties> = {
  APPROVED: { background: 'var(--ok-bg)', color: 'var(--ok)' },
  PENDING_APPROVAL: { background: 'var(--warn-bg)', color: 'var(--warn)' },
  BLOCKED: { background: 'var(--danger-bg)', color: 'var(--danger)' },
  DRAFT: { background: 'var(--surface-muted)', color: 'var(--text-muted)' },
};
export function StatusBadge({ status }: { status: string }) {
  return <span className="badge" style={statusStyle[status] ?? statusStyle.DRAFT}>{status.replace('_', ' ')}</span>;
}

const decisionStyle: Record<string, React.CSSProperties> = {
  ALLOW: { background: 'var(--ok-bg)', color: 'var(--ok)' },
  APPROVAL_REQUIRED: { background: 'var(--warn-bg)', color: 'var(--warn)' },
  DENY: { background: 'var(--danger-bg)', color: 'var(--danger)' },
};
export function DecisionBadge({ decision }: { decision: string }) {
  return <span className="badge" style={decisionStyle[decision] ?? decisionStyle.DENY}>● {decision.replace('_', ' ')}</span>;
}
