import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type GovernanceCase,
  type GovernanceCaseType,
} from "../api";
import { StatusBadge } from "../components";
import { Alert, Button, Card } from "@chestertown/design";

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
    <>
      <h1>Governance cases</h1>
      <p className="sub">
        Decision dossiers for assessments, changes, exceptions and corrective
        actions. Prudence stores the process, owners and SHA-256-backed
        references — never the document itself.
      </p>
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
          <div className="grid2">
            <div className="field">
              <label>AI system</label>
              <select
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
              </select>
            </div>
            <div className="field">
              <label>Case type</label>
              <select
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
              </select>
            </div>
            <div className="field">
              <label>Title</label>
              <input
                required
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
              />
            </div>
            <div className="field">
              <label>Accountable owner</label>
              <input
                required
                value={form.owner}
                onChange={(e) => setForm({ ...form, owner: e.target.value })}
                placeholder="governance@company.eu"
              />
            </div>
          </div>
          <div className="field">
            <label>Decision context</label>
            <textarea
              required
              value={form.summary}
              onChange={(e) => setForm({ ...form, summary: e.target.value })}
            />
          </div>
          <Button type="submit" variant="primary" disabled={busy}>
            Open case
          </Button>
        </form>
      </Card>
      <table>
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
              <td colSpan={4} className="mut">
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
                <div className="mut" style={{ fontSize: "var(--fs-sm)" }}>
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
      </table>
      {selected && (
        <Card style={{ marginTop: "var(--sp-6)" }}>
          <div className="row between">
            <div>
              <h2 style={{ margin: 0, fontSize: "var(--fs-lg)" }}>
                {selected.title}
              </h2>
              <p className="mut">
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
              <p className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                Link to the source record and its exact SHA-256; no file is
                copied here.
              </p>
              <div className="grid2">
                <div className="field">
                  <label>Label</label>
                  <input
                    value={evidence.label}
                    onChange={(e) =>
                      setEvidence({ ...evidence, label: e.target.value })
                    }
                  />
                </div>
                <div className="field">
                  <label>URI</label>
                  <input
                    value={evidence.uri}
                    onChange={(e) =>
                      setEvidence({ ...evidence, uri: e.target.value })
                    }
                  />
                </div>
                <div className="field">
                  <label>SHA-256</label>
                  <input
                    value={evidence.sha256}
                    onChange={(e) =>
                      setEvidence({ ...evidence, sha256: e.target.value })
                    }
                  />
                </div>
                <div className="field">
                  <label>Version</label>
                  <input
                    value={evidence.version}
                    onChange={(e) =>
                      setEvidence({ ...evidence, version: e.target.value })
                    }
                  />
                </div>
                <div className="field">
                  <label>Evidence owner</label>
                  <input
                    value={evidence.owner}
                    onChange={(e) =>
                      setEvidence({ ...evidence, owner: e.target.value })
                    }
                  />
                </div>
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
            <p className="mut">No evidence references yet.</p>
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
            <div className="field">
              <label>Closing decision</label>
              <textarea
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
            </div>
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
                <span className="mut">
                  {" "}
                  · {when(e.at)} · {e.actor}
                </span>
                <div className="mut">{e.details}</div>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </>
  );
}
