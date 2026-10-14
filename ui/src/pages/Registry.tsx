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

export default function Registry() {
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
    <>
      <h1>AI system registry</h1>
      <p className="sub">
        Every AI use case, its EU AI Act risk tier, owner and approval state —
        the governance spine.
      </p>

      <div className="card" style={{ marginBottom: "var(--sp-6)" }}>
        <form onSubmit={submit}>
          <div className="grid2">
            <div className="field">
              <label>Name</label>
              <input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                required
              />
            </div>
            <div className="field">
              <label>Risk tier</label>
              <select
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
              </select>
            </div>
            <div className="field">
              <label>EU AI Act role</label>
              <select
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
              </select>
            </div>
          </div>
          <div className="field">
            <label>Intended purpose</label>
            <input
              value={form.intendedPurpose}
              onChange={(e) =>
                setForm({ ...form, intendedPurpose: e.target.value })
              }
              required
            />
          </div>
          <div className="grid2">
            <div className="field">
              <label>Owner name</label>
              <input
                value={form.ownerName}
                onChange={(e) =>
                  setForm({ ...form, ownerName: e.target.value })
                }
                required
              />
            </div>
            <div className="field">
              <label>Owner email</label>
              <input
                type="email"
                value={form.ownerEmail}
                onChange={(e) =>
                  setForm({ ...form, ownerEmail: e.target.value })
                }
              />
            </div>
          </div>
          <div className="grid2">
            <div className="field">
              <label>System version</label>
              <input
                value={form.systemVersion}
                onChange={(e) =>
                  setForm({ ...form, systemVersion: e.target.value })
                }
                required
                placeholder="e.g. 2026.10"
              />
            </div>
            <div className="field">
              <label>Model / build SHA-256</label>
              <input
                value={form.modelHash}
                onChange={(e) =>
                  setForm({ ...form, modelHash: e.target.value })
                }
                required
              />
            </div>
          </div>
          <div className="grid2">
            <div className="field">
              <label>Operating geography</label>
              <input
                value={form.operatingGeography}
                onChange={(e) =>
                  setForm({ ...form, operatingGeography: e.target.value })
                }
                required
              />
            </div>
            <div className="field">
              <label>Affected population</label>
              <input
                value={form.affectedPopulation}
                onChange={(e) =>
                  setForm({ ...form, affectedPopulation: e.target.value })
                }
                required
                placeholder="e.g. retail credit applicants"
              />
            </div>
          </div>
          <div className="grid2">
            <label className="field">
              <input
                type="checkbox"
                checked={form.annexI}
                onChange={(e) => setForm({ ...form, annexI: e.target.checked })}
              />{" "}
              Annex I regulated product
            </label>
            <label className="field">
              <input
                type="checkbox"
                checked={form.annexIII}
                onChange={(e) =>
                  setForm({ ...form, annexIII: e.target.checked })
                }
              />{" "}
              Annex III use case
            </label>
          </div>
          <div className="field">
            <label>Classification justification</label>
            <textarea
              value={form.classificationJustification}
              onChange={(e) =>
                setForm({
                  ...form,
                  classificationJustification: e.target.value,
                })
              }
              required
            />
          </div>
          <div className="field">
            <label>Applicable obligations (one per line)</label>
            <textarea
              value={form.obligations.join("\n")}
              onChange={(e) =>
                setForm({ ...form, obligations: e.target.value.split("\n") })
              }
              required
              placeholder="Article 9 risk management\nArticle 10 data governance"
            />
          </div>
          <div className="row between">
            {error ? (
              <span className="err">{error}</span>
            ) : (
              <span className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                Registration creates an assessment dossier; submit it for review
                before it can be approved.
              </span>
            )}
            <button className="btn btn-primary" disabled={busy}>
              {busy ? "Registering…" : "Register system"}
            </button>
          </div>
        </form>
      </div>

      <table>
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
              <td colSpan={5} className="mut">
                No systems registered yet.
              </td>
            </tr>
          )}
          {systems.map((s) => (
            <tr key={s.id}>
              <td>
                <div style={{ fontWeight: 600 }}>{s.name}</div>
                <code>{s.id}</code>
              </td>
              <td>
                <RiskBadge tier={s.riskTier} annexIII={s.annexIII} />
                <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                  {s.regulatoryRole} · v{s.systemVersion}
                </div>
              </td>
              <td>
                <StatusBadge status={s.approvalStatus} />
              </td>
              <td>
                <button
                  className="btn btn-outline"
                  onClick={() => openControls(s)}
                >
                  Controls
                </button>
              </td>
              <td className="mut">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {controlsFor && (
        <section className="card" style={{ marginTop: "var(--sp-6)" }}>
          <div className="row between">
            <div>
              <h2 style={{ fontSize: "var(--fs-lg)", margin: 0 }}>
                Compliance controls
              </h2>
              <p className="mut" style={{ marginBottom: 0 }}>
                {controlsFor.name} · evidence stays in its source system;
                Prudence records its reference and hash.
              </p>
            </div>
            <button
              className="btn btn-outline"
              onClick={() => setControlsFor(null)}
            >
              Close
            </button>
          </div>
          {controlError && <p className="err">{controlError}</p>}
          {controls.length === 0 && !controlError && (
            <p className="mut">
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
                <div className="row between">
                  <div>
                    <strong>{control.title}</strong>
                    <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                      {control.profileId} · {control.key}
                      {control.blocking ? " · blocks review" : ""}
                    </div>
                  </div>
                  <StatusBadge status={control.status} />
                </div>
                <p className="mut" style={{ fontSize: "var(--fs-sm)" }}>
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
                  <p className="mut" style={{ fontSize: "var(--fs-sm)" }}>
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
                  <div className="grid2">
                    <div className="field">
                      <label>Evidence URI</label>
                      <input
                        value={draft.evidenceUri}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceUri: e.target.value,
                          })
                        }
                        placeholder="https://…"
                      />
                    </div>
                    <div className="field">
                      <label>SHA-256</label>
                      <input
                        value={draft.evidenceSha256}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceSha256: e.target.value,
                          })
                        }
                        placeholder="64 hex characters"
                      />
                    </div>
                    <div className="field">
                      <label>Evidence version</label>
                      <input
                        value={draft.evidenceVersion}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceVersion: e.target.value,
                          })
                        }
                      />
                    </div>
                    <div className="field">
                      <label>Evidence owner</label>
                      <input
                        value={draft.evidenceOwner}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            evidenceOwner: e.target.value,
                          })
                        }
                      />
                    </div>
                    {control.accepts.includes("dataset-version") && (
                      <>
                        <div className="field">
                          <label>Dataset version</label>
                          <input
                            value={draft.datasetVersion}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                datasetVersion: e.target.value,
                              })
                            }
                          />
                        </div>
                        <div className="field">
                          <label>Metrics by group</label>
                          <input
                            value={draft.metricsByGroup}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                metricsByGroup: e.target.value,
                              })
                            }
                          />
                        </div>
                        <div className="field">
                          <label>Approver</label>
                          <input
                            value={draft.approver}
                            onChange={(e) =>
                              updateEvidence(control.id, {
                                approver: e.target.value,
                              })
                            }
                          />
                        </div>
                      </>
                    )}
                    <div className="field">
                      <label>Signed connector attestation</label>
                      <input
                        value={draft.signedAttestation}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            signedAttestation: e.target.value,
                          })
                        }
                        placeholder="attestation id or signed payload reference"
                      />
                    </div>
                    <div className="field">
                      <label>Ernest receipt (optional)</label>
                      <input
                        value={draft.ernestReceipt}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            ernestReceipt: e.target.value,
                          })
                        }
                        placeholder="ernest://…"
                      />
                    </div>
                    <div className="field">
                      <label>Expires at (optional, ISO instant)</label>
                      <input
                        value={draft.expiresAt ?? ""}
                        onChange={(e) =>
                          updateEvidence(control.id, {
                            expiresAt: e.target.value,
                          })
                        }
                        placeholder="derived automatically if blank"
                      />
                    </div>
                    <div className="field" style={{ alignSelf: "end" }}>
                      <button
                        className="btn btn-primary"
                        disabled={controlBusy === control.id}
                        onClick={() => satisfy(control)}
                      >
                        {controlBusy === control.id
                          ? "Recording…"
                          : "Record evidence"}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </section>
      )}
    </>
  );
}

function systemVersion(system: AiSystem | null) {
  return system?.systemVersion ?? "";
}
