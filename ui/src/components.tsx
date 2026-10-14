import type { EvidenceDelivery, RiskTier } from "./api";

const riskStyle: Record<RiskTier, React.CSSProperties> = {
  PROHIBITED: {
    background: "var(--risk-prohibited-bg)",
    color: "var(--risk-prohibited)",
  },
  HIGH: { background: "var(--risk-high-bg)", color: "var(--risk-high)" },
  LIMITED: {
    background: "var(--risk-limited-bg)",
    color: "var(--risk-limited)",
  },
  MINIMAL: {
    background: "var(--risk-minimal-bg)",
    color: "var(--risk-minimal)",
  },
};

/// Evidence receipt: queued, recorded (with or without a qualified seal), or failed for good.
export function EvidenceState({
  delivery,
  sealed,
  blockIndex,
}: {
  delivery: EvidenceDelivery;
  sealed: boolean;
  blockIndex: number | null;
}) {
  const block = blockIndex != null ? ` · Ernest block ${blockIndex}` : "";
  if (delivery === "PENDING")
    return <span style={{ color: "var(--warn)" }}>● queued for Ernest</span>;
  if (delivery === "DEAD")
    return (
      <span style={{ color: "var(--danger)" }}>
        ● not recorded — delivery failed
      </span>
    );
  return sealed ? (
    <span style={{ color: "var(--ok)" }}>● qualified-sealed{block}</span>
  ) : (
    <span style={{ color: "var(--ok)" }}>
      ● recorded, no qualified seal{block}
    </span>
  );
}

export function RiskBadge({
  tier,
  annexIII,
}: {
  tier: RiskTier;
  annexIII?: boolean;
}) {
  return (
    <span className="badge" style={riskStyle[tier]}>
      {tier}
      {annexIII ? " · Annex III" : ""}
    </span>
  );
}

const statusStyle: Record<string, React.CSSProperties> = {
  APPROVED: { background: "var(--ok-bg)", color: "var(--ok)" },
  EXPIRED: { background: "var(--danger-bg)", color: "var(--danger)" },
  PENDING_APPROVAL: { background: "var(--warn-bg)", color: "var(--warn)" },
  PENDING_SECOND_APPROVAL: {
    background: "var(--warn-bg)",
    color: "var(--warn)",
  },
  REJECTED: { background: "var(--danger-bg)", color: "var(--danger)" },
  BLOCKED: { background: "var(--danger-bg)", color: "var(--danger)" },
  DRAFT: { background: "var(--surface-muted)", color: "var(--text-muted)" },
  UNDER_ASSESSMENT: {
    background: "var(--surface-muted)",
    color: "var(--text-muted)",
  },
  EVIDENCE_PENDING: { background: "var(--warn-bg)", color: "var(--warn)" },
  SUSPENDED: { background: "var(--danger-bg)", color: "var(--danger)" },
  RETIRED: { background: "var(--surface-muted)", color: "var(--text-muted)" },
};
export function StatusBadge({ status }: { status: string }) {
  return (
    <span className="badge" style={statusStyle[status] ?? statusStyle.DRAFT}>
      {status.replace("_", " ")}
    </span>
  );
}

const postureStyle: Record<string, React.CSSProperties> = {
  GREEN: { background: "var(--ok-bg)", color: "var(--ok)" },
  AMBER: { background: "var(--warn-bg)", color: "var(--warn)" },
  RED: { background: "var(--danger-bg)", color: "var(--danger)" },
};
export function PostureBadge({ status }: { status: string }) {
  return (
    <span className="badge" style={postureStyle[status] ?? postureStyle.AMBER}>
      ● {status}
    </span>
  );
}

const decisionStyle: Record<string, React.CSSProperties> = {
  ALLOW: { background: "var(--ok-bg)", color: "var(--ok)" },
  APPROVAL_REQUIRED: { background: "var(--warn-bg)", color: "var(--warn)" },
  DENY: { background: "var(--danger-bg)", color: "var(--danger)" },
};
export function DecisionBadge({ decision }: { decision: string }) {
  return (
    <span
      className="badge"
      style={decisionStyle[decision] ?? decisionStyle.DENY}
    >
      ● {decision.replace("_", " ")}
    </span>
  );
}
