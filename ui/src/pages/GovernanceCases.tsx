import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type GovernanceCase,
  type GovernanceCaseType,
} from "../api";
import { StatusBadge } from "../components";
import {
  Alert,
  Button,
  Card,
  Field,
  Input,
  Page,
  Select,
  Table,
  Textarea,
} from "@chestertown/design";

const TYPES: GovernanceCaseType[] = [
  "ASSESSMENT",
  "CHANGE",
  "EXCEPTION",
  "CORRECTIVE_ACTION",
];
const empty = {
  aiSystemId: "",
  type: "ASSESSMENT" as GovernanceCaseType,
  title: "",
  summary: "",
  owner: "",
};
const when = (iso: string) =>
  iso
    .replace("T", " ")
    .replace(/\.\d+Z?$/, "")
    .replace("Z", "");

/// A governed decision dossier: links and hashes, never uploaded document content.
export default function GovernanceCases() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [cases, setCases] = useState<GovernanceCase[]>([]);
  const [selected, setSelected] = useState<GovernanceCase | null>(null);
  const [form, setForm] = useState(empty);
  const [evidence, setEvidence] = useState({
    label: "",
    uri: "",
    sha256: "",
    version: "",
    owner: "",
  });
  const [decision, setDecision] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = async () => {
    try {
      const [s, c] = await Promise.all([
        api.listSystems(),
        api.governanceCases(),
      ]);
      setSystems(s);
      setCases(c);
      if (selected) setSelected(await api.governanceCase(selected.id));
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const act = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setError("");
    try {
      await work();
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const systemName = (id: string) =>
    systems.find((s) => s.id === id)?.name ?? id;
  return (
    <Page
      title="Governance cases"
      subtitle="Decision dossiers for assessments, changes, exceptions and corrective actions. Prudence stores the process, owners and SHA-256-backed references — never the document itself."
    >
      {error && <Alert tone="danger">{error}</Alert>}
      <Card style={{ marginBottom: "var(--sp-6)" }}>
        <h2 style={{ marginTop: 0, fontSize: "var(--fs-lg)" }}>Open a case</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            act(async () => {
              await api.openGovernanceCase(form);
              setForm(empty);
            });
          }}
        >
          <div className="ct-grid-2">
            <Field label="AI system" htmlFor="governancecases-ai-system">
              <Select
                id="governancecases-ai-system"
                required
                value={form.aiSystemId}
                onChange={(e) =>
                  setForm({ ...form, aiSystemId: e.target.value })
                }
              >
                <option value="">Select…</option>
                {systems.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Case type" htmlFor="governancecases-case-type">
              <Select
                id="governancecases-case-type"
                value={form.type}
                onChange={(e) =>
                  setForm({
                    ...form,
                    type: e.target.value as GovernanceCaseType,
                  })
                }
              >
                {TYPES.map((t) => (
                  <option key={t}>{t.replaceAll("_", " ")}</option>
                ))}
              </Select>
            </Field>
            <Field label="Title" htmlFor="governancecases-title">
              <Input
                id="governancecases-title"
                required
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
              />
            </Field>
            <Field
              label="Accountable owner"
              htmlFor="governancecases-accountable-owner"
            >
              <Input
                id="governancecases-accountable-owner"
                required
                value={form.owner}
                onChange={(e) => setForm({ ...form, owner: e.target.value })}
                placeholder="governance@company.eu"
              />
            </Field>
          </div>
          <Field
            label="Decision context"
            htmlFor="governancecases-decision-context"
          >
            <Textarea
              id="governancecases-decision-context"
              required
              value={form.summary}
              onChange={(e) => setForm({ ...form, summary: e.target.value })}
            />
          </Field>
          <Button type="submit" variant="primary" disabled={busy}>
            Open case
          </Button>
        </form>
      </Card>
      <Table>
        <thead>
          <tr>
            <th>Case</th>
            <th>AI system</th>
            <th>Owner</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {cases.length === 0 && (
            <tr>
              <td colSpan={4} className="ct-muted">
                No governance cases.
              </td>
            </tr>
          )}
          {cases.map((c) => (
            <tr
              key={c.id}
              onClick={async () => {
                try {
                  setSelected(await api.governanceCase(c.id));
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
              style={{ cursor: "pointer" }}
            >
              <td>
                <b>{c.title}</b>
                <div className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                  {c.type.replaceAll("_", " ")} · {when(c.openedAt)}
                </div>
              </td>
              <td>{systemName(c.aiSystemId)}</td>
              <td>{c.owner}</td>
              <td>
                <StatusBadge status={c.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </Table>
      {selected && (
        <Card style={{ marginTop: "var(--sp-6)" }}>
          <div className="ct-row ct-between">
            <div>
              <h2 style={{ margin: 0, fontSize: "var(--fs-lg)" }}>
                {selected.title}
              </h2>
              <p className="ct-muted">
                {selected.type.replaceAll("_", " ")} · owner {selected.owner} ·{" "}
                {systemName(selected.aiSystemId)}
              </p>
            </div>
            <Button onClick={() => setSelected(null)}>Close</Button>
          </div>
          <p>{selected.summary}</p>
          {selected.status === "OPEN" && (
            <Button
              disabled={busy}
              onClick={() => act(() => api.startGovernanceReview(selected.id))}
            >
              Start review
            </Button>
          )}
          {selected.status !== "CLOSED" && (
            <Card style={{ marginTop: "var(--sp-4)" }}>
              <h3 style={{ marginTop: 0 }}>Reference evidence</h3>
              <p className="ct-muted" style={{ fontSize: "var(--fs-sm)" }}>
                Link to the source record and its exact SHA-256; no file is
                copied here.
              </p>
              <div className="ct-grid-2">
                <Field label="Label" htmlFor="governancecases-label">
                  <Input
                    id="governancecases-label"
                    value={evidence.label}
                    onChange={(e) =>
                      setEvidence({ ...evidence, label: e.target.value })
                    }
                  />
                </Field>
                <Field label="URI" htmlFor="governancecases-uri">
                  <Input
                    id="governancecases-uri"
                    value={evidence.uri}
                    onChange={(e) =>
                      setEvidence({ ...evidence, uri: e.target.value })
                    }
                  />
                </Field>
                <Field label="SHA-256" htmlFor="governancecases-sha-256">
                  <Input
                    id="governancecases-sha-256"
                    value={evidence.sha256}
                    onChange={(e) =>
                      setEvidence({ ...evidence, sha256: e.target.value })
                    }
                  />
                </Field>
                <Field label="Version" htmlFor="governancecases-version">
                  <Input
                    id="governancecases-version"
                    value={evidence.version}
                    onChange={(e) =>
                      setEvidence({ ...evidence, version: e.target.value })
                    }
                  />
                </Field>
                <Field
                  label="Evidence owner"
                  htmlFor="governancecases-evidence-owner"
                >
                  <Input
                    id="governancecases-evidence-owner"
                    value={evidence.owner}
                    onChange={(e) =>
                      setEvidence({ ...evidence, owner: e.target.value })
                    }
                  />
                </Field>
              </div>
              <Button
                variant="primary"
                disabled={busy}
                onClick={() =>
                  act(async () => {
                    await api.addGovernanceEvidence(selected.id, evidence);
                    setEvidence({
                      label: "",
                      uri: "",
                      sha256: "",
                      version: "",
                      owner: "",
                    });
                  })
                }
              >
                Record reference
              </Button>
            </Card>
          )}
          <h3>Evidence references</h3>
          {selected.evidence.length === 0 ? (
            <p className="ct-muted">No evidence references yet.</p>
          ) : (
            <ul>
              {selected.evidence.map((e) => (
                <li key={e.id}>
                  <a href={e.uri} target="_blank" rel="noreferrer">
                    {e.label}
                  </a>{" "}
                  · {e.version} · <code>{e.sha256}</code> · {e.owner}
                </li>
              ))}
            </ul>
          )}
          {selected.status !== "CLOSED" && (
            <Field
              label="Closing decision"
              htmlFor="governancecases-closing-decision"
            >
              <Textarea
                id="governancecases-closing-decision"
                value={decision}
                onChange={(e) => setDecision(e.target.value)}
                placeholder="Decision, rationale and any follow-up owner"
              />
              <Button
                disabled={busy || !decision}
                onClick={() =>
                  act(() => api.closeGovernanceCase(selected.id, decision))
                }
              >
                Close case
              </Button>
            </Field>
          )}
          <h3>Timeline</h3>
          <ul style={{ listStyle: "none", padding: 0 }}>
            {selected.timeline.map((e) => (
              <li
                key={e.id}
                style={{
                  borderBottom: "1px solid var(--border)",
                  padding: "var(--sp-2) 0",
                }}
              >
                <b>{e.type.replaceAll("_", " ")}</b>
                <span className="ct-muted">
                  {" "}
                  · {when(e.at)} · {e.actor}
                </span>
                <div className="ct-muted">{e.details}</div>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </Page>
  );
}
