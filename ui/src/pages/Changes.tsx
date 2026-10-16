import { useEffect, useState } from "react";
import { api, type AiSystem, type ChangeAssessment } from "../api";
const fields = [
  "MODEL_VERSION",
  "DATA_OR_EMBEDDINGS",
  "GPAI_PROVIDER_MODEL",
  "PROMPT_OR_TOOLS",
  "INTENDED_PURPOSE",
  "AFFECTED_POPULATION",
  "AUTOMATION",
  "INTEGRATION_ENVIRONMENT",
  "SECURITY_CONFIGURATION",
  "PROHIBITED_USE",
];
export default function Changes() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [form, setForm] = useState({
    aiSystemId: "",
    proposedVersion: "",
    changedFields: [] as string[],
    evidenceUri: "",
    evidenceSha256: "",
  });
  const [result, setResult] = useState<ChangeAssessment | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    api
      .listSystems()
      .then(setSystems)
      .catch((e) => setError((e as Error).message));
  }, []);
  const toggle = (x: string) =>
    setForm({
      ...form,
      changedFields: form.changedFields.includes(x)
        ? form.changedFields.filter((y) => y !== x)
        : [...form.changedFields, x],
    });
  return (
    <>
      <h1>Change assessment</h1>
      <p className="sub">
        AOS/MLOps submits the evidence; Prudence classifies the governance
        consequence and applies the gate.
      </p>
      {error && <p className="err">{error}</p>}
      <div className="card">
        <div className="grid2">
          <select
            value={form.aiSystemId}
            onChange={(e) => setForm({ ...form, aiSystemId: e.target.value })}
          >
            <option value="">Select system…</option>
            {systems.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <input
            placeholder="Proposed version"
            value={form.proposedVersion}
            onChange={(e) =>
              setForm({ ...form, proposedVersion: e.target.value })
            }
          />
        </div>
        <p className="mut">Changed dimensions</p>
        {fields.map((x) => (
          <label key={x}>
            <input
              type="checkbox"
              checked={form.changedFields.includes(x)}
              onChange={() => toggle(x)}
            />{" "}
            {x.replaceAll("_", " ")}
          </label>
        ))}
        <input
          placeholder="Evidence URI"
          value={form.evidenceUri}
          onChange={(e) => setForm({ ...form, evidenceUri: e.target.value })}
        />
        <input
          placeholder="Evidence SHA-256"
          value={form.evidenceSha256}
          onChange={(e) => setForm({ ...form, evidenceSha256: e.target.value })}
        />
        <button
          className="btn btn-primary"
          onClick={async () => {
            try {
              setResult(await api.assessChange(form));
            } catch (e) {
              setError((e as Error).message);
            }
          }}
        >
          Assess change
        </button>
      </div>
      {result && (
        <section className="card" style={{ marginTop: "var(--sp-5)" }}>
          <h2>{result.outcome.replaceAll("_", " ")}</h2>
          <p>{result.rationale}</p>
          <p className="mut">
            {result.assessedAt} · {result.assessedBy}
          </p>
        </section>
      )}
    </>
  );
}
