import { ernestEvidenceUrl } from "../chestertown";
import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type ComplianceControl,
  type ComplianceEvidenceInput,
  type RegisterInput,
  type RegulatoryRole,
  type RiskTier,
} from "../api";
import { RiskBadge, StatusBadge } from "../components";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Field,
  Input,
  Page,
  Select,
  Table,
  Textarea,
} from "@chestertown/design";

const EMPTY: RegisterInput = {
  name: "",
  intendedPurpose: "",
  ownerName: "",
  ownerEmail: "",
  riskTier: "HIGH",
  regulatoryRole: "DEPLOYER",
  systemVersion: "",
  modelHash: "",
  annexI: false,
  annexIII: true,
  operatingGeography: "European Union",
  affectedPopulation: "",
  classificationJustification: "",
  obligations: [""],
};
const TIERS: RiskTier[] = ["PROHIBITED", "HIGH", "LIMITED", "MINIMAL"];
const ROLES: RegulatoryRole[] = [
  "PROVIDER",
  "DEPLOYER",
  "IMPORTER",
  "DISTRIBUTOR",
];

export default function Registry({ focusSystem }: { focusSystem?: string }) {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [form, setForm] = useState<RegisterInput>(EMPTY);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [controlsFor, setControlsFor] = useState<AiSystem | null>(null);
  const [controls, setControls] = useState<ComplianceControl[]>([]);
  const [controlError, setControlError] = useState("");
  const [controlBusy, setControlBusy] = useState("");
  const [evidence, setEvidence] = useState<
    Record<string, ComplianceEvidenceInput>
  >({});

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

  // A deep link (`?system=<id>`) scrolls to that AI system once the list has loaded.
  useEffect(() => {
    if (!focusSystem || systems.length === 0) return;
    document
      .getElementById(`system-${focusSystem}`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [focusSystem, systems]);

  // The controls panel opens below the whole table: bring it into view, or the Controls
  // button looks like it did nothing when the list is long.
  useEffect(() => {
    if (!controlsFor) return;
    document
      .getElementById("compliance-controls")
      ?.scrollIntoView({ block: "start", behavior: "smooth" });
  }, [controlsFor?.id]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api.registerSystem({
        ...form,
        obligations: form.obligations.filter(Boolean),
      });
      setForm(EMPTY);
      await load();
    } catch {
      setError("Registration failed");
    } finally {
      setBusy(false);
    }
  };

  const openControls = async (system: AiSystem) => {
    setControlsFor(system);
    setControls([]);
    setControlError("");
    try {
      setControls(await api.complianceControls(system.id));
    } catch {
      setControlError("Could not load compliance controls");
    }
  };
  const updateEvidence = (
    id: string,
    patch: Partial<ComplianceEvidenceInput>,
  ) =>
    setEvidence((previous) => {
      const current = previous[id] ?? {
        evidenceUri: "",
        evidenceSha256: "",
        evidenceVersion: systemVersion(controlsFor),
        evidenceOwner: controlsFor?.ownerEmail ?? "",
        datasetVersion: "",
        metricsByGroup: "",
        approver: "",
        signedAttestation: "",
        ernestReceipt: "",
      };
      return { ...previous, [id]: { ...current, ...patch } };
    });
  const satisfy = async (control: ComplianceControl) => {
    if (!controlsFor) return;
    const body = evidence[control.id] ?? {
      evidenceUri: "",
      evidenceSha256: "",
      evidenceVersion: systemVersion(controlsFor),
      evidenceOwner: controlsFor.ownerEmail,
      datasetVersion: "",
      metricsByGroup: "",
      approver: "",
      signedAttestation: "",
      ernestReceipt: "",
    };
    setControlBusy(control.id);
    setControlError("");
    try {
      await api.satisfyComplianceControl(controlsFor.id, control.id, body);
      setControls(await api.complianceControls(controlsFor.id));
    } catch (e) {
      setControlError((e as Error).message || "Could not record evidence");
    } finally {
      setControlBusy("");
    }
  };

  return (
    <Page
      title="AI system registry"
      subtitle="Every AI use case, its EU AI Act risk tier, owner and approval state — the governance spine."
    >
      <Card style={{ marginBottom: "var(--sp-6)" }}>
        <form onSubmit={submit}>
          <div className="ct-grid-2">
            <Field label="Name" htmlFor="registry-name">
              <Input
                id="registry-name"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                required
              />
            </Field>
            <Field label="Risk tier" htmlFor="registry-risk-tier">
              <Select
                id="registry-risk-tier"
                value={form.riskTier}
                onChange={(e) =>
                  setForm({ ...form, riskTier: e.target.value as RiskTier })
                }
              >
                {TIERS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="EU AI Act role" htmlFor="registry-eu-ai-act-role">
              <Select
                id="registry-eu-ai-act-role"
                value={form.regulatoryRole}
                onChange={(e) =>
                  setForm({
                    ...form,
                    regulatoryRole: e.target.value as RegulatoryRole,
                  })
                }
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <Field label="Intended purpose" htmlFor="registry-intended-purpose">
            <Input
              id="registry-intended-purpose"
              value={form.intendedPurpose}
              onChange={(e) =>
                setForm({ ...form, intendedPurpose: e.target.value })
              }
              required
            />
          </Field>
          <div className="ct-grid-2">
            <Field label="Owner name" htmlFor="registry-owner-name">
              <Input
                id="registry-owner-name"
                value={form.ownerName}
                onChange={(e) =>
                  setForm({ ...form, ownerName: e.target.value })
                }
                required
              />
            </Field>
            <Field label="Owner email" htmlFor="registry-owner-email">
              <Input
                id="registry-owner-email"
                type="email"
                value={form.ownerEmail}
                onChange={(e) =>
                  setForm({ ...form, ownerEmail: e.target.value })
                }
              />
            </Field>
          </div>
          <div className="ct-grid-2">
            <Field label="System version" htmlFor="registry-system-version">
              <Input
                id="registry-system-version"
                value={form.systemVersion}
                onChange={(e) =>
                  setForm({ ...form, systemVersion: e.target.value })
                }
                required
                placeholder="e.g. 2026.10"
              />
            </Field>
            <Field
              label="Model / build SHA-256"
              htmlFor="registry-model-build-sha-256"
            >
              <Input
                id="registry-model-build-sha-256"
                value={form.modelHash}
                onChange={(e) =>
                  setForm({ ...form, modelHash: e.target.value })
                }
                required
              />
            </Field>
          </div>
          <div className="ct-grid-2">
            <Field
              label="Operating geography"
              htmlFor="registry-operating-geography"
            >
              <Input
                id="registry-operating-geography"
                value={form.operatingGeography}
                onChange={(e) =>
                  setForm({ ...form, operatingGeography: e.target.value })
                }
                required
              />
            </Field>
            <Field
              label="Affected population"
              htmlFor="registry-affected-population"
            >
              <Input
                id="registry-affected-population"
                value={form.affectedPopulation}
                onChange={(e) =>
                  setForm({ ...form, affectedPopulation: e.target.value })
                }
                required
                placeholder="e.g. retail credit applicants"
              />
            </Field>
          </div>
          <div className="ct-grid-2" style={{ marginBottom: "var(--sp-4)" }}>
            <Checkbox
              label="Annex I regulated product"
              checked={form.annexI}
              onChange={(e) => setForm({ ...form, annexI: e.target.checked })}
            />
            <Checkbox
              label="Annex III use case"
              checked={form.annexIII}
              onChange={(e) => setForm({ ...form, annexIII: e.target.checked })}
            />
          </div>
          <Field
            label="Classification justification"
            htmlFor="registry-classification-justification"
          >
            <Textarea
              id="registry-classification-justification"
              value={form.classificationJustification}
              onChange={(e) =>
                setForm({
                  ...form,
                  classificationJustification: e.target.value,
                })
              }
              required
            />
          </Field>
          <Field
            label="Applicable obligations (one per line)"
            htmlFor="registry-applicable-obligations-one-per-line"
          >
            <Textarea
              id="registry-applicable-obligations-one-per-line"
              value={form.obligations.join("\n")}
              onChange={(e) =>
                setForm({ ...form, obligations: e.target.value.split("\n") })
              }
              required
              placeholder={
                "Article 9 risk management\nArticle 10 data governance"
              }
            />
          </Field>
          <div className="ct-row ct-between">
            {error ? (
              <Alert tone="danger">{error}</Alert>
            ) : (
              <span className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                Registration creates an assessment dossier; submit it for review
                before it can be approved.
              </span>
            )}
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? "Registering…" : "Register system"}
            </Button>
          </div>
        </form>
      </Card>

      <Table>
        <thead>
          <tr>
            <th>AI system</th>
            <th>Classification</th>
            <th>Lifecycle</th>
            <th>Compliance</th>
            <th>Owner</th>
          </tr>
        </thead>
        <tbody>
          {systems.length === 0 && (
            <tr>
              <td colSpan={5} className="ct-muted">
                No systems registered yet.
              </td>
            </tr>
          )}
          {systems.map((s) => (
            <tr
              key={s.id}
              id={`system-${s.id}`}
              style={
                s.id === focusSystem
                  ? {
                      outline: "2px solid var(--accent-govern)",
                      outlineOffset: -2,
                    }
                  : undefined
              }
            >
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
                <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
                <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  {s.regulatoryRole} · v{s.systemVersion}
                </div>
              </td>
              <td>
                <StatusBadge status={s.approvalStatus} />
              </td>
              <td>
                <Button onClick={() => openControls(s)}>Controls</Button>
              </td>
              <td className="ct-muted">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </Table>

      {controlsFor && (
        <Card
          id="compliance-controls"
          style={{ marginTop: "var(--sp-6)", scrollMarginTop: "80px" }}
        >
          <div className="ct-row ct-between">
            <div>
              <h2 style={{ fontSize: "var(--fs-lg)", margin: 0 }}>
                Compliance controls
              </h2>
              <p className="ct-muted" style={{ marginBottom: 0 }}>
                {controlsFor.name} · evidence stays in its source system;
                Prudence records its reference and hash.
              </p>
            </div>
            <Button onClick={() => setControlsFor(null)}>Close</Button>
          </div>
          {controlError && <Alert tone="danger">{controlError}</Alert>}
          {controls.length === 0 && !controlError && (
            <p className="ct-muted">
              No profile-derived controls apply to this system.
            </p>
          )}
          {controls.map((control) => {
            const draft = evidence[control.id] ?? {
              evidenceUri: "",
              evidenceSha256: "",
              evidenceVersion: systemVersion(controlsFor),
              evidenceOwner: controlsFor.ownerEmail,
              datasetVersion: "",
              metricsByGroup: "",
              approver: "",
              signedAttestation: "",
              ernestReceipt: "",
              expiresAt: "",
            };
            const done = control.status === "SATISFIED";
            return (
              <div
                key={control.id}
                style={{
                  borderTop: "1px solid var(--line)",
                  padding: "var(--sp-4) 0",
                }}
              >
                <div className="ct-row ct-between">
                  <div>
                    <strong>{control.title}</strong>
                    <div
                      className="ct-muted"
                      style={{ fontSize: "var(--fs-sm)" }}
                    >
                      {control.profileId} · {control.key}
                      {control.blocking ? " · blocks review" : ""}
                    </div>
                  </div>
                  <StatusBadge status={control.status} />
                </div>
                <p className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  {control.requirement}
                  <br />
                  Contract: {control.accepts} · source {control.evidenceSource}{" "}
                  · expires{" "}
                  {control.expiresAfterDays
                    ? `after ${control.expiresAfterDays} days`
                    : "as declared"}
                  {control.expiresOnVersionChange
                    ? " or on version change"
                    : ""}
                  {control.blocksVersionChange
                    ? " · blocks version change"
                    : ""}
                </p>
                {done ? (
                  <p className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                    Evidence:{" "}
                    <a
                      href={control.evidenceUri ?? "#"}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {control.evidenceVersion}
                    </a>{" "}
                    · {control.evidenceOwner}
                    {control.expiresAt ? ` · expires ${control.expiresAt}` : ""}
                  </p>
                ) : (
                  <div className="ct-grid-2">
                    <Field
                      label="Evidence URI"
                      htmlFor={`registry-evidence-uri-${control.id}`}
                    >
                      <Input
                        id={`registry-evidence-uri-${control.id}`}
                        value={draft.evidenceUri}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceUri: e.target.value,
                          })
                        }
                        placeholder="https://…"
                      />
                    </Field>
                    <Field
                      label="SHA-256"
                      htmlFor={`registry-sha-256-${control.id}`}
                    >
                      <Input
                        id={`registry-sha-256-${control.id}`}
                        value={draft.evidenceSha256}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceSha256: e.target.value,
                          })
                        }
                        placeholder="64 hex characters"
                      />
                    </Field>
                    <Field
                      label="Evidence version"
                      htmlFor={`registry-evidence-version-${control.id}`}
                    >
                      <Input
                        id={`registry-evidence-version-${control.id}`}
                        value={draft.evidenceVersion}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceVersion: e.target.value,
                          })
                        }
                      />
                    </Field>
                    <Field
                      label="Evidence owner"
                      htmlFor={`registry-evidence-owner-${control.id}`}
                    >
                      <Input
                        id={`registry-evidence-owner-${control.id}`}
                        value={draft.evidenceOwner}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceOwner: e.target.value,
                          })
                        }
                      />
                    </Field>
                    {control.accepts.includes("dataset-version") && (
                      <>
                        <Field
                          label="Dataset version"
                          htmlFor={`registry-dataset-version-${control.id}`}
                        >
                          <Input
                            id={`registry-dataset-version-${control.id}`}
                            value={draft.datasetVersion}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                datasetVersion: e.target.value,
                              })
                            }
                          />
                        </Field>
                        <Field
                          label="Metrics by group"
                          htmlFor={`registry-metrics-by-group-${control.id}`}
                        >
                          <Input
                            id={`registry-metrics-by-group-${control.id}`}
                            value={draft.metricsByGroup}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                metricsByGroup: e.target.value,
                              })
                            }
                          />
                        </Field>
                        <Field
                          label="Approver"
                          htmlFor={`registry-approver-${control.id}`}
                        >
                          <Input
                            id={`registry-approver-${control.id}`}
                            value={draft.approver}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                approver: e.target.value,
                              })
                            }
                          />
                        </Field>
                      </>
                    )}
                    <Field
                      label="Signed connector attestation"
                      htmlFor={`registry-signed-connector-attestation-${control.id}`}
                    >
                      <Input
                        id={`registry-signed-connector-attestation-${control.id}`}
                        value={draft.signedAttestation}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            signedAttestation: e.target.value,
                          })
                        }
                        placeholder="attestation id or signed payload reference"
                      />
                    </Field>
                    <Field
                      label="Ernest receipt (optional)"
                      htmlFor={`registry-ernest-receipt-optional-${control.id}`}
                    >
                      <Input
                        id={`registry-ernest-receipt-optional-${control.id}`}
                        value={draft.ernestReceipt}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            ernestReceipt: e.target.value,
                          })
                        }
                        placeholder="ernest://…"
                      />
                    </Field>
                    <Field
                      label="Expires at (optional, ISO instant)"
                      htmlFor={`registry-expires-at-optional-iso-instant-${control.id}`}
                    >
                      <Input
                        id={`registry-expires-at-optional-iso-instant-${control.id}`}
                        value={draft.expiresAt ?? ""}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            expiresAt: e.target.value,
                          })
                        }
                        placeholder="derived automatically if blank"
                      />
                    </Field>
                    <div
                      style={{ alignSelf: "end", marginBottom: "var(--sp-4)" }}
                    >
                      <Button
                        variant="primary"
                        disabled={controlBusy === control.id}
                        onClick={() => satisfy(control)}
                      >
                        {controlBusy === control.id
                          ? "Recording…"
                          : "Record evidence"}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </Card>
      )}
    </Page>
  );
}

function systemVersion(system: AiSystem | null) {
  return system?.systemVersion ?? "";
}
