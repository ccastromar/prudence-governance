import { useEffect, useState } from "react";
import { api, type AiSystem, type ComplianceSnapshot } from "../api";
export default function Snapshots() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [id, setId] = useState("");
  const [snapshot, setSnapshot] = useState<ComplianceSnapshot | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    api
      .listSystems()
      .then(setSystems)
      .catch((e) => setError((e as Error).message));
  }, []);
  return (
    <>
      <h1>Compliance snapshots</h1>
      <p className="sub">
        Immutable, hash-addressed manifests for audit and authority portability.
        They contain evidence references, not document copies.
      </p>
      {error && <p className="err">{error}</p>}
      <div className="row">
        <select value={id} onChange={(e) => setId(e.target.value)}>
          <option value="">Select AI system…</option>
          {systems.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        <button
          className="btn btn-primary"
          disabled={!id}
          onClick={async () => {
            try {
              setSnapshot(await api.createSnapshot(id));
            } catch (e) {
              setError((e as Error).message);
            }
          }}
        >
          Generate snapshot
        </button>
      </div>
      {snapshot && (
        <section className="card" style={{ marginTop: "var(--sp-5)" }}>
          <h2>Snapshot created</h2>
          <p>
            <code>{snapshot.id}</code>
          </p>
          <p className="mut">As of {snapshot.asOf}</p>
          <p>
            SHA-256: <code>{snapshot.sha256}</code>
          </p>
          <details>
            <summary>Manifest</summary>
            <pre style={{ whiteSpace: "pre-wrap", fontSize: "var(--fs-xs)" }}>
              {JSON.stringify(snapshot.manifest, null, 2)}
            </pre>
          </details>
        </section>
      )}
    </>
  );
}
