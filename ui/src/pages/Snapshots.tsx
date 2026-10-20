import { useEffect, useState } from "react";
import { api, type AiSystem, type ComplianceSnapshot } from "../api";
import { Alert, Button, Card, Page, Select } from "@chestertown/design";
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
    <Page
      title="Compliance snapshots"
      subtitle="Immutable, hash-addressed manifests for audit and authority portability. They contain evidence references, not document copies."
    >
      {error && <Alert tone="danger">{error}</Alert>}
      <div className="ct-row">
        <Select value={id} onChange={(e) => setId(e.target.value)}>
          <option value="">Select AI system…</option>
          {systems.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </Select>
        <Button
          variant="primary"
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
        </Button>
      </div>
      {snapshot && (
        <Card style={{ marginTop: "var(--sp-5)" }}>
          <h2>Snapshot created</h2>
          <p>
            <code>{snapshot.id}</code>
          </p>
          <p className="ct-muted">As of {snapshot.asOf}</p>
          <p>
            SHA-256: <code>{snapshot.sha256}</code>
          </p>
          <details>
            <summary>Manifest</summary>
            <pre style={{ whiteSpace: "pre-wrap", fontSize: "var(--fs-xs)" }}>
              {JSON.stringify(snapshot.manifest, null, 2)}
            </pre>
          </details>
        </Card>
      )}
    </Page>
  );
}
