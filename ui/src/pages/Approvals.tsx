import { useEffect, useState } from "react";
import { api, type AiSystem, type ReviewEvent } from "../api";
import { EvidenceState, RiskBadge, StatusBadge } from "../components";
import {
  Alert,
  Button,
  Card,
  Field,
  Input,
  Modal,
  Page,
  Table,
} from "@chestertown/design";

const actionColor: Record<string, string> = {
  APPROVE: "var(--ok)",
  FIRST_APPROVAL: "var(--ok)",
  RENEW: "var(--ok)",
  FIRST_RENEWAL_APPROVAL: "var(--ok)",
  REJECT: "var(--danger)",
  EXPIRE: "var(--danger)",
  REVIEW_DUE: "var(--warn)",
};
const when = (iso: string) =>
  iso
    .replace("T", " ")
    .replace(/\.\d+Z?$/, "")
    .replace("Z", "");

/// The reviewer's inbox: every system awaiting a governance decision. Approving one here flips
/// the PDP verdict to ALLOW, so the AOS gate lets its pipeline run — the human link in the loop.
export default function Approvals() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [error, setError] = useState("");
  const [busyId, setBusyId] = useState("");
  const [rejecting, setRejecting] = useState<AiSystem | null>(null);
  const [reason, setReason] = useState("");
  const [trailFor, setTrailFor] = useState<AiSystem | null>(null);
  const [trail, setTrail] = useState<ReviewEvent[]>([]);

  const load = () =>
    api
      .listSystems()
      .then(setSystems)
      .catch(() =>
        setError("Could not load systems (is the backend on :8099?)"),
      );
  useEffect(() => {
    load();
  }, []);

  const openTrail = async (s: AiSystem) => {
    setTrailFor(s);
    setTrail([]);
    try {
      setTrail(await api.systemReviews(s.id));
    } catch {
      setError("Could not load the audit trail");
    }
  };

  const pending = systems.filter(
    (s) =>
      s.approvalStatus === "PENDING_APPROVAL" ||
      s.approvalStatus === "PENDING_SECOND_APPROVAL",
  );
  // Everything the backend lets back into review (AiSystem.submitForReview): a suspended system
  // only returns to service through a fresh submission and full review.
  const assessed = systems.filter(
    (s) =>
      s.approvalStatus === "UNDER_ASSESSMENT" ||
      s.approvalStatus === "REJECTED" ||
      s.approvalStatus === "EXPIRED" ||
      s.approvalStatus === "SUSPENDED",
  );
  const renewalDue = systems.filter(
    (s) => s.approvalStatus === "APPROVED" && s.reviewDue,
  );
  const recent = systems.filter(
    (s) =>
      s.approvalStatus === "APPROVED" ||
      s.approvalStatus === "REJECTED" ||
      s.approvalStatus === "EXPIRED",
  );

  const approve = async (s: AiSystem) => {
    setBusyId(s.id);
    setError("");
    try {
      await api.approveSystem(s.id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId("");
    }
  };

  const renew = async (s: AiSystem) => {
    setBusyId(s.id);
    setError("");
    try {
      await api.renewSystem(s.id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId("");
    }
  };

  const submit = async (s: AiSystem) => {
    setBusyId(s.id);
    setError("");
    try {
      await api.submitSystem(s.id);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId("");
    }
  };

  const confirmReject = async () => {
    if (!rejecting) return;
    setBusyId(rejecting.id);
    setError("");
    try {
      await api.rejectSystem(rejecting.id, reason);
      setRejecting(null);
      setReason("");
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId("");
    }
  };

  return (
    <Page
      title="Approvals"
      subtitle="High-risk and prohibited systems may not go live without an explicit governance decision. Review the queue below."
    >
      {error && (
        <Alert tone="danger" className="ct-mb-6">
          {error}
        </Alert>
      )}

      <h2 className="ct-text-lg">
        Ready to submit — assessed, rejected, expired or suspended (
        {assessed.length})
      </h2>
      <div className="ct-mb-8">
        <Table>
          <thead>
            <tr>
              <th>AI system</th>
              <th>Classification</th>
              <th>Evidence</th>
              <th className="ct-num">Action</th>
            </tr>
          </thead>
          <tbody>
            {assessed.length === 0 && (
              <tr>
                <td colSpan={4} className="ct-muted">
                  Nothing awaiting (re)submission.
                </td>
              </tr>
            )}
            {assessed.map((s) => (
              <tr key={s.id}>
                <td>
                  <div className="ct-strong">{s.name}</div>
                  <code>{s.id}</code>
                </td>
                <td>
                  <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
                  <div className="ct-muted ct-text-sm">
                    {s.regulatoryRole} · v{s.systemVersion}
                  </div>
                </td>
                <td className="ct-muted ct-text-sm">
                  {s.obligations.length} obligations ·{" "}
                  {s.annexI ? "Annex I" : "No Annex I"}
                </td>
                <td className="ct-num">
                  <Button
                    variant="primary"
                    disabled={busyId === s.id}
                    onClick={() => submit(s)}
                  >
                    {busyId === s.id ? "…" : "Submit for review"}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      </div>

      <h2 className="ct-text-lg">Awaiting decision ({pending.length})</h2>
      <div className="ct-mb-8">
        <Table>
          <thead>
            <tr>
              <th>AI system</th>
              <th>Risk tier</th>
              <th>Owner</th>
              <th className="ct-num">Decision</th>
            </tr>
          </thead>
          <tbody>
            {pending.length === 0 && (
              <tr>
                <td colSpan={4} className="ct-muted">
                  Nothing awaiting approval.
                </td>
              </tr>
            )}
            {pending.map((s) => (
              <tr key={s.id}>
                <td>
                  <div className="ct-strong">{s.name}</div>
                  <code>{s.id}</code>
                  <div className="ct-muted ct-text-sm">{s.intendedPurpose}</div>
                </td>
                <td>
                  <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
                </td>
                <td className="ct-muted">
                  {s.ownerName}
                  {s.firstApprover && (
                    <div className="ct-text-sm">
                      First sign-off: {s.firstApprover}
                    </div>
                  )}
                </td>
                <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                  <Button
                    variant="primary"
                    disabled={busyId === s.id}
                    onClick={() => approve(s)}
                    style={{ marginRight: "var(--sp-2)" }}
                  >
                    {busyId === s.id ? "…" : "Approve"}
                  </Button>
                  <Button
                    disabled={busyId === s.id}
                    onClick={() => {
                      setRejecting(s);
                      setReason("");
                    }}
                  >
                    Reject
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      </div>

      <h2 className="ct-text-lg">Renewal due ({renewalDue.length})</h2>
      <p className="ct-muted ct-text-sm ct-mt-0">
        Periodic review: renew before expiry and the system keeps running.
        High-risk renewals need two independent sign-offs.
      </p>
      <div className="ct-mb-8">
        <Table>
          <thead>
            <tr>
              <th>AI system</th>
              <th>Risk tier</th>
              <th>Expires</th>
              <th className="ct-num">Review</th>
            </tr>
          </thead>
          <tbody>
            {renewalDue.length === 0 && (
              <tr>
                <td colSpan={4} className="ct-muted">
                  No approvals inside their review window.
                </td>
              </tr>
            )}
            {renewalDue.map((s) => (
              <tr key={s.id}>
                <td>
                  <div className="ct-strong">{s.name}</div>
                  <code>{s.id}</code>
                </td>
                <td>
                  <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
                </td>
                <td className="ct-muted">
                  {s.approvalExpiresAt && when(s.approvalExpiresAt)}
                  {s.renewalFirstApprover && (
                    <div className="ct-text-sm">
                      First renewal sign-off: {s.renewalFirstApprover}
                    </div>
                  )}
                </td>
                <td className="ct-num">
                  <Button
                    variant="primary"
                    disabled={busyId === s.id}
                    onClick={() => renew(s)}
                  >
                    {busyId === s.id ? "…" : "Renew"}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      </div>

      <h2 className="ct-text-lg">Recently decided</h2>
      <p className="ct-muted ct-text-sm ct-mt-0">
        Click a system to see its sealed audit trail.
      </p>
      <Table>
        <thead>
          <tr>
            <th>AI system</th>
            <th>Risk tier</th>
            <th>Outcome</th>
            <th>Owner</th>
          </tr>
        </thead>
        <tbody>
          {recent.length === 0 && (
            <tr>
              <td colSpan={4} className="ct-muted">
                No decisions yet.
              </td>
            </tr>
          )}
          {recent.map((s) => (
            <tr
              key={s.id}
              onClick={() => openTrail(s)}
              style={{ cursor: "pointer" }}
              className={trailFor?.id === s.id ? "ct-selected" : ""}
            >
              <td>
                <div className="ct-strong">{s.name}</div>
                <code>{s.id}</code>
              </td>
              <td>
                <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
              </td>
              <td>
                <StatusBadge status={s.approvalStatus} />
                {s.approvalExpiresAt && (
                  <div className="ct-muted ct-text-sm">
                    Expires {when(s.approvalExpiresAt)}
                  </div>
                )}
              </td>
              <td className="ct-muted">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </Table>

      {trailFor && (
        <Card className="ct-mt-6">
          <div className="ct-row ct-between">
            <h2 className="ct-section-title">Audit trail — {trailFor.name}</h2>
            <Button size="sm" onClick={() => setTrailFor(null)}>
              Close
            </Button>
          </div>
          {trail.length === 0 ? (
            <p className="ct-muted">
              No review events recorded for this system.
            </p>
          ) : (
            <ul className="ct-plain-list">
              {trail.map((e) => (
                <li
                  key={e.id}
                  style={{
                    padding: "var(--sp-3) 0",
                    borderBottom: "1px solid var(--border)",
                  }}
                >
                  <div className="ct-row ct-between">
                    <span
                      style={{ fontWeight: 700, color: actionColor[e.action] }}
                    >
                      ● {e.action}
                    </span>
                    <span className="ct-muted ct-text-sm">{when(e.at)}</span>
                  </div>
                  <div className="ct-muted ct-text-sm">
                    {e.previousStatus} → {e.newStatus} · by <b>{e.reviewer}</b>
                    {" · "}
                    <EvidenceState
                      delivery={e.evidence}
                      sealed={e.sealed}
                      blockIndex={e.blockIndex}
                    />
                  </div>
                  {e.reason && (
                    <div className="ct-muted ct-text-sm">“{e.reason}”</div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}

      {rejecting && (
        <Modal
          title={`Reject “${rejecting.name}”?`}
          onClose={() => setRejecting(null)}
          width={440}
          actions={
            <>
              <Button onClick={() => setRejecting(null)}>Cancel</Button>
              <Button
                variant="danger"
                disabled={busyId === rejecting.id}
                onClick={confirmReject}
              >
                {busyId === rejecting.id ? "Rejecting…" : "Confirm reject"}
              </Button>
            </>
          }
        >
          <p className="ct-muted ct-mt-0">
            The system stays off-limits to the PDP until it is re-submitted or
            reclassified.
          </p>
          <Field label="Reason" htmlFor="approvals-reason">
            <Input
              id="approvals-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="why this is being declined"
              autoFocus
            />
          </Field>
        </Modal>
      )}
    </Page>
  );
}
