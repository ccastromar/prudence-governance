import { Badge, type Tone } from "@chestertown/design";
import type { EvidenceDelivery } from "./api";

// Risk tiers and PDP decisions look the same in all three modules: the shared components.
export { DecisionBadge, RiskBadge } from "@chestertown/design";

/// Evidence receipt: queued, recorded (with or without a qualified seal), or failed for good.
/** Under every free-text field whose words travel, as written, to the append-only evidence store:
 * people there are pseudonyms, but nothing can pseudonymise what someone types. */
export const PERMANENT_EVIDENCE_HINT =
  "Kept for good as evidence, as written: no names or other personal data.";

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

const STATUS_TONE: Record<string, Tone> = {
  APPROVED: "ok",
  EXPIRED: "danger",
  PENDING_APPROVAL: "warn",
  PENDING_SECOND_APPROVAL: "warn",
  REJECTED: "danger",
  BLOCKED: "danger",
  DRAFT: "neutral",
  UNDER_ASSESSMENT: "neutral",
  EVIDENCE_PENDING: "warn",
  SUSPENDED: "danger",
  RETIRED: "neutral",
};
export function StatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={STATUS_TONE[status] ?? "neutral"}>
      {status.replace("_", " ")}
    </Badge>
  );
}

const POSTURE_TONE: Record<string, Tone> = {
  GREEN: "ok",
  AMBER: "warn",
  RED: "danger",
};
export function PostureBadge({ status }: { status: string }) {
  return (
    <Badge tone={POSTURE_TONE[status] ?? "warn"} dot>
      {status}
    </Badge>
  );
}
