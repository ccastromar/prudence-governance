import { useEffect, useState } from "react";
import { Alert, Button, Card } from "@chestertown/design";
import {
  api,
  type Attestation,
  type AttestationSummary,
  type Verification,
} from "../api";
import {
  EvidenceState,
  RiskBadge,
  StatusBadge,
  PostureBadge,
} from "../components";

const verdictColor: Record<string, string> = {
  VERIFIED: "var(--ok)",
  ALTERED: "var(--danger)",
  PENDING: "var(--warn)",
  UNSEALED: "var(--warn)",
  UNCONFIRMED: "var(--warn)",
  // Delivered to a custody-only store: not a failure, but not verifiable either.
  CUSTODY_ONLY: "var(--text-muted)",
};

/// Attestations: generate a tamper-evident report of the estate and see it sealed in Ernest.
/// The report's content hash is what gets qualify-sealed — the report proves itself.
export default function Attestations() {
  const [list, setList] = useState<AttestationSummary[]>([]);
  const [selected, setSelected] = useState<Attestation | null>(null);
  const [verification, setVerification] = useState<Verification | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = () =>
    api
      .listAttestations()
      .then(setList)
      .catch(() =>
        setError("Could not load attestations (is the backend on :8099?)"),
      );
  useEffect(() => {
    load();
  }, []);

  const generate = async () => {
    setBusy(true);
    setError("");
    try {
      const a = await api.generateAttestation();
      setSelected(a);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const open = async (id: string) => {
    setError("");
    setVerification(null);
    try {
      setSelected(await api.getAttestation(id));
    } catch {
      setError("Could not load that attestation");
    }
  };

  const doVerify = async () => {
    if (!selected) return;
    setError("");
    try {
      setVerification(await api.verifyAttestation(selected.id));
    } catch {
      setError("Verification call failed");
    }
  };

  const shortHash = (h: string) => `${h.slice(0, 12)}…`;
  const when = (iso: string) =>
    iso
      .replace("T", " ")
      .replace(/\.\d+Z?$/, "")
      .replace("Z", "");

  return (
    <>
      <div className="row between" style={{ alignItems: "flex-end" }}>
        <div>
          <h1>Attestations</h1>
          <p className="sub">
            A point-in-time report of the estate, hashed and sealed in Ernest —
            so the report itself is tamper-evident.
          </p>
        </div>
        <Button variant="primary" onClick={generate} disabled={busy}>
          {busy ? "Generating…" : "Generate attestation"}
        </Button>
      </div>

      {error && (
        <Alert tone="danger" style={{ marginBottom: "var(--sp-6)" }}>
          {error}
        </Alert>
      )}

      <table style={{ marginBottom: "var(--sp-8)" }}>
        <thead>
          <tr>
            <th>Issued</th>
            <th>Estate</th>
            <th>Content hash</th>
            <th>Evidence</th>
          </tr>
        </thead>
        <tbody>
          {list.length === 0 && (
            <tr>
              <td colSpan={4} className="mut">
                No attestations issued yet.
              </td>
            </tr>
          )}
          {list.map((a) => (
            <tr
              key={a.id}
              onClick={() => open(a.id)}
              style={{ cursor: "pointer" }}
              className={selected?.id === a.id ? "active-row" : ""}
            >
              <td>{when(a.generatedAt)}</td>
              <td>
                <PostureBadge status={a.overallPosture} />
              </td>
              <td>
                <code>{shortHash(a.contentHash)}</code>
              </td>
              <td>
                <EvidenceState
                  delivery={a.delivery}
                  sealed={a.sealed}
                  blockIndex={a.blockIndex}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected && (
        <Card>
          <div className="row between">
            <h2 style={{ marginTop: 0 }}>
              Attestation {selected.id.slice(0, 8)}
            </h2>
            <div
              className="row"
              style={{ gap: "var(--sp-3)", alignItems: "center" }}
            >
              <Button
                onClick={doVerify}
                style={{ fontSize: "var(--fs-sm)", padding: "4px 10px" }}
                title="Recompute the hash and confirm the seal in Ernest"
              >
                Verify
              </Button>
              <a
                className="ct-btn ct-btn-outline"
                href={`/api/attestations/${selected.id}/oscal`}
                target="_blank"
                rel="noreferrer"
                style={{ fontSize: "var(--fs-sm)", padding: "4px 10px" }}
                title="NIST OSCAL Assessment Results (machine-readable compliance)"
              >
                OSCAL
              </a>
              <PostureBadge status={selected.overallPosture} />
            </div>
          </div>

          {verification && (
            <Card
              style={{
                marginBottom: "var(--sp-4)",
                borderColor: verdictColor[verification.verdict],
              }}
            >
              <div
                style={{
                  fontWeight: 700,
                  color: verdictColor[verification.verdict],
                }}
              >
                ● {verification.verdict}
              </div>
              <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                {verification.detail}
              </div>
              <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                hash recomputed{" "}
                {verification.hashMatches ? "✓ matches" : "✗ differs"}
                {(verification.verdict === "VERIFIED" ||
                  verification.verdict === "UNCONFIRMED") && (
                  <>
                    {" "}
                    · qualified seal {verification.sealed ? "✓" : "✗"} · Ernest{" "}
                    {verification.ernestConfirmed
                      ? "✓ confirmed"
                      : "✗ not found"}{" "}
                    · chain {verification.chainValid ? "✓ valid" : "✗ invalid"}
                    {verification.blockIndex != null && (
                      <> · block {verification.blockIndex}</>
                    )}
                  </>
                )}
              </div>
            </Card>
          )}
          <div
            className="mut"
            style={{ fontSize: "var(--fs-sm)", marginBottom: "var(--sp-4)" }}
          >
            <div>
              Issued: <b>{when(selected.generatedAt)}</b>
            </div>
            <div>
              Content hash: <code>{selected.contentHash}</code>
            </div>
            <div>
              Evidence:{" "}
              <EvidenceState
                delivery={selected.evidence.delivery}
                sealed={selected.evidence.sealed}
                blockIndex={selected.evidence.blockIndex}
              />
              {" — "}
              {selected.evidence.detail} (model{" "}
              <code>{selected.evidence.modelId}</code>)
            </div>
          </div>

          <table>
            <thead>
              <tr>
                <th>AI system</th>
                <th>Risk tier</th>
                <th>Approval</th>
                <th>Posture</th>
              </tr>
            </thead>
            <tbody>
              {selected.systems.length === 0 && (
                <tr>
                  <td colSpan={4} className="mut">
                    No systems in this attestation.
                  </td>
                </tr>
              )}
              {selected.systems.map((s) => (
                <tr key={s.aiSystemId}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{s.name}</div>
                    <code>{s.aiSystemId}</code>
                  </td>
                  <td>
                    <RiskBadge tier={s.riskTier} />
                  </td>
                  <td>
                    <StatusBadge status={s.approvalStatus} />
                  </td>
                  <td>
                    <PostureBadge status={s.posture} />
                    <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                      {s.detail}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}
