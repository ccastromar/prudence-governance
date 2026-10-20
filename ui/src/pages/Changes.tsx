import { useEffect, useState } from "react";
import { api, type AiSystem, type ChangeAssessment } from "../api";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  Page,
  Select,
} from "@chestertown/design";
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
    <Page
      title="Change assessment"
      subtitle="AOS/MLOps submits the evidence; Prudence classifies the governance consequence and applies the gate."
    >
      {error && <Alert tone="danger">{error}</Alert>}
      <Card>
        <div className="ct-grid-2">
          <Select
            value={form.aiSystemId}
            onChange={(e) => setForm({ ...form, aiSystemId: e.target.value })}
          >
            <option value="">Select system…</option>
            {systems.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          <Input
            placeholder="Proposed version"
            value={form.proposedVersion}
            onChange={(e) =>
              setForm({ ...form, proposedVersion: e.target.value })
            }
          />
        </div>
        <p className="ct-muted">Changed dimensions</p>
        {fields.map((x) => (
          <Checkbox
            key={x}
            label={x.replaceAll("_", " ")}
            checked={form.changedFields.includes(x)}
            onChange={() => toggle(x)}
          />
        ))}
        <Input
          placeholder="Evidence URI"
          value={form.evidenceUri}
          onChange={(e) => setForm({ ...form, evidenceUri: e.target.value })}
        />
        <Input
          placeholder="Evidence SHA-256"
          value={form.evidenceSha256}
          onChange={(e) => setForm({ ...form, evidenceSha256: e.target.value })}
        />
        <Button
          variant="primary"
          onClick={async () => {
            try {
              setResult(await api.assessChange(form));
            } catch (e) {
              setError((e as Error).message);
            }
          }}
        >
          Assess change
        </Button>
      </Card>
      {result && (
        <Card style={{ marginTop: "var(--sp-5)" }}>
          <h2>{result.outcome.replaceAll("_", " ")}</h2>
          <p>{result.rationale}</p>
          <p className="ct-muted">
            {result.assessedAt} · {result.assessedBy}
          </p>
        </Card>
      )}
    </Page>
  );
}
