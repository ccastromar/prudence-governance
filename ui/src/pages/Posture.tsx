import { ernestEvidenceUrl } from "../chestertown";
import { useEffect, useState } from "react";
import {
  api,
  type Posture as PostureData,
  type Drift,
  type ExecutionSources,
  type EvidenceSink,
  type DeadLetter,
} from "../api";
import { RiskBadge, StatusBadge, PostureBadge } from "../components";
import { Alert, Badge, Button, Card, Page, Table } from "@chestertown/design";

/// Oversight dashboard: the step that closes the control loop. Prudence reads Ernest's evidence
/// back and colours each registered system — GREEN only when it is both approved to run and
/// backed by an intact, reachable evidence record — and flags policy drift (ungoverned runs).
export default function Posture() {
  const [data, setData] = useState<PostureData | null>(null);
  const [drift, setDrift] = useState<Drift | null>(null);
  const [sources, setSources] = useState<ExecutionSources | null>(null);
  const [sink, setSink] = useState<EvidenceSink | null>(null);
  const [error, setError] = useState("");
  const [dead, setDead] = useState<DeadLetter[]>([]);
  const [busy, setBusy] = useState("");

  const load = () => {
    api
      .posture()
      .then(setData)
      .catch(() =>
        setError("Could not load posture (is the backend on :8099?)"),
      );
    api
      .drift()
      .then(setDrift)
      .catch(() => {
        /* drift is best-effort */
      });
    api
      .evidenceSink()
      .then(setSink)
      .catch(() => {
        /* best-effort */
      });
    api
      .executionSources()
      .then(setSources)
      .catch(() => {
        /* best-effort */
      });
    api
      .deadLetters()
      .then(setDead)
      .catch(() => {
        /* best-effort */
      });
  };

  const retry = async (d: DeadLetter) => {
    setBusy(d.id);
    setError("");
    try {
      await api.retryDeadLetter(d.target, d.id);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy("");
    }
  };
  useEffect(() => {
    load();
  }, []);

  return (
    <Page
      title="Posture"
      subtitle="The compliance posture of the AI estate — risk tier, approval, and whether the evidence record backs it up."
    >
      {error && <Alert tone="danger">{error}</Alert>}

      {data && (
        <>
          <Card style={{ marginBottom: "var(--sp-6)" }}>
            <div className="ct-row ct-between">
              <div>
                <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  Estate roll-up
                </div>
                <div style={{ fontSize: "var(--fs-lg)", fontWeight: 700 }}>
                  <PostureBadge status={data.overall} />
                </div>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  Evidence plane (Ernest)
                </div>
                <div
                  style={{
                    color: data.evidenceHealthy ? "var(--ok)" : "var(--danger)",
                    fontWeight: 600,
                  }}
                >
                  {data.evidenceHealthy ? "● healthy" : "● unavailable"}
                </div>
                <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  {data.evidenceDetail}
                </div>
              </div>
            </div>
          </Card>

          <Table>
            <thead>
              <tr>
                <th>AI system</th>
                <th>Risk tier</th>
                <th>Approval</th>
                <th>Posture</th>
              </tr>
            </thead>
            <tbody>
              {data.systems.length === 0 && (
                <tr>
                  <td colSpan={4} className="ct-muted">
                    No systems registered yet.
                  </td>
                </tr>
              )}
              {data.systems.map((s) => (
                <tr key={s.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{s.name}</div>
                    <code>{s.id}</code>{" "}
                    <a
                      href={ernestEvidenceUrl(s.id)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                      title="Open this AI system's evidence in Ernest"
                    >
                      evidence ↗
                    </a>
                  </td>
                  <td>
                    <RiskBadge tier={s.riskTier} />
                  </td>
                  <td>
                    <StatusBadge status={s.approvalStatus} />
                  </td>
                  <td>
                    <PostureBadge status={s.status} />
                    <div
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                    >
                      {s.detail}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </>
      )}

      {drift && !drift.complete && (
        <Card
          style={{ marginTop: "var(--sp-6)", borderColor: "var(--danger)" }}
        >
          <Alert tone="danger">
            ● Policy-drift scan incomplete — results below cannot be trusted.
          </Alert>
          <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
            {drift.scanDetail}
          </div>
        </Card>
      )}

      {drift && drift.findings.length > 0 && (
        <Card
          style={{
            marginTop: "var(--sp-6)",
            borderColor: drift.hasViolations
              ? "var(--danger)"
              : "var(--border)",
          }}
        >
          <div className="ct-row ct-between">
            <h2 style={{ marginTop: 0, fontSize: "var(--fs-lg)" }}>
              Policy drift
            </h2>
            <span
              style={{
                fontWeight: 700,
                color: drift.hasViolations ? "var(--danger)" : "var(--ok)",
              }}
            >
              {drift.hasViolations ? "● violations detected" : "● clean"}
            </span>
          </div>
          <p
            className="ct-muted"
            style={{ fontSize: "var(--fs-sm)", marginTop: 0 }}
          >
            Executions in the evidence plane checked against the governance
            record.
          </p>
          <Table>
            <thead>
              <tr>
                <th>AI system</th>
                <th>Approval</th>
                <th>Runs</th>
                <th>Finding</th>
              </tr>
            </thead>
            <tbody>
              {drift.findings.map((f) => (
                <tr key={f.aiSystemId}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{f.name}</div>
                    <code>{f.aiSystemId}</code>{" "}
                    <a
                      href={ernestEvidenceUrl(f.aiSystemId)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                      title="Open this AI system's evidence in Ernest"
                    >
                      evidence ↗
                    </a>
                  </td>
                  <td>
                    <StatusBadge status={f.approvalStatus} />
                  </td>
                  <td className="ct-muted">{f.executionCount}</td>
                  <td>
                    <span
                      style={{
                        fontWeight: 700,
                        color:
                          f.severity === "VIOLATION"
                            ? "var(--danger)"
                            : "var(--ok)",
                      }}
                    >
                      ● {f.severity}
                    </span>
                    <div
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                    >
                      {f.detail}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      {sink && (
        <Card style={{ marginTop: "var(--sp-6)" }}>
          <h2 style={{ marginTop: 0, fontSize: "var(--fs-lg)" }}>
            Evidence store: {sink.label}
          </h2>
          <p
            className="ct-muted"
            style={{ fontSize: "var(--fs-sm)", marginTop: 0 }}
          >
            What the store holding Prudence's evidence can guarantee. Anything
            missing is stated here and in attestations, never implied.
          </p>
          <div
            style={{ display: "flex", flexWrap: "wrap", gap: "var(--sp-2)" }}
          >
            {sink.capabilities.map((c) => (
              <Badge key={c.code} tone="ok" title={c.meaning}>
                ✓ {c.label}
              </Badge>
            ))}
            {sink.missing.map((c) => (
              <Badge key={c.code} title={c.meaning}>
                ✗ {c.label}
              </Badge>
            ))}
          </div>
        </Card>
      )}

      {sources && (!sources.complete || sources.mismatches.length > 0) && (
        <Card style={{ marginTop: "var(--sp-6)", borderColor: "var(--warn)" }}>
          <h2 style={{ marginTop: 0, fontSize: "var(--fs-lg)" }}>
            Execution sources
          </h2>
          <p
            className="ct-muted"
            style={{ fontSize: "var(--fs-sm)", marginTop: 0 }}
          >
            What runtimes reported to Prudence and the execution evidence in
            Ernest should agree (after a {sources.graceMinutes}-minute grace for
            delivery). A mismatch is a warning, not a violation: evidence that
            never arrived, or an execution nobody reported.
          </p>
          {!sources.complete ? (
            <div className="ct-muted">{sources.detail}</div>
          ) : (
            <Table>
              <thead>
                <tr>
                  <th>AI system</th>
                  <th>Last reported</th>
                  <th>Last in Ernest</th>
                  <th>Warning</th>
                </tr>
              </thead>
              <tbody>
                {sources.mismatches.map((m) => (
                  <tr key={m.aiSystemId}>
                    <td>
                      <div style={{ fontWeight: 600 }}>{m.name}</div>
                      <code>{m.aiSystemId}</code>
                    </td>
                    <td className="ct-muted">{m.lastReportedAt ?? "—"}</td>
                    <td className="ct-muted">{m.lastEvidencedAt ?? "—"}</td>
                    <td>
                      <span style={{ fontWeight: 700, color: "var(--warn)" }}>
                        ●{" "}
                        {m.kind === "REPORTED_NOT_EVIDENCED"
                          ? "evidence missing"
                          : "not reported"}
                      </span>
                      <div
                        className="ct-muted"
                        style={{ fontSize: "var(--fs-sm)" }}
                      >
                        {m.detail}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      )}

      {dead.length > 0 && (
        <Card
          style={{ marginTop: "var(--sp-6)", borderColor: "var(--danger)" }}
        >
          <h2 style={{ marginTop: 0, fontSize: "var(--fs-lg)" }}>
            Undelivered records ({dead.length})
          </h2>
          <p
            className="ct-muted"
            style={{ fontSize: "var(--fs-sm)", marginTop: 0 }}
          >
            Evidence that never reached Ernest, or alerts that were never sent.
            Fix the cause (Ernest reachable, credentials, payload), then retry:
            the record is requeued and delivered at once.
          </p>
          <Table>
            <thead>
              <tr>
                <th>Kind</th>
                <th>Record</th>
                <th>Last error</th>
                <th style={{ textAlign: "right" }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {dead.map((d) => (
                <tr key={d.target + d.id}>
                  <td>
                    {d.target}
                    {d.aiSystemId && (
                      <div
                        className="ct-muted"
                        style={{ fontSize: "var(--fs-sm)" }}
                      >
                        <code>{d.aiSystemId}</code>
                      </div>
                    )}
                  </td>
                  <td>
                    <code>{d.id}</code>
                    <div
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                    >
                      {d.attempts} attempt(s) · gave up{" "}
                      {d.deadAt.replace("T", " ").replace(/\.\d+Z$/, "")}
                    </div>
                  </td>
                  <td
                    className="ct-muted"
                    style={{
                      fontSize: "var(--fs-sm)",
                      maxWidth: 360,
                      overflowWrap: "anywhere",
                    }}
                  >
                    {d.lastError}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <Button
                      variant="primary"
                      disabled={busy === d.id}
                      onClick={() => retry(d)}
                    >
                      {busy === d.id ? "…" : "Retry"}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}
    </Page>
  );
}
