import { useEffect, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  Field,
  Input,
  Page,
  Select,
} from "@chestertown/design";
import {
  api,
  type AiSystem,
  type Capa,
  type MonitoringSummary,
  type RiskSignal,
} from "../api";

const signals: RiskSignal[] = [
  "INCIDENT_RATE",
  "HUMAN_OVERRIDE_RATE",
  "COMPLAINT_RATE",
  "PERFORMANCE_DEGRADATION",
  "BIAS_DRIFT",
];
const emptyCapa = {
  signalId: "",
  risk: "",
  mitigation: "",
  owner: "",
  dueAt: "",
};

export default function Monitoring() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [id, setId] = useState("");
  const [summary, setSummary] = useState<MonitoringSummary | null>(null);
  const [capas, setCapas] = useState<Capa[]>([]);
  const [error, setError] = useState("");
  const [form, setForm] = useState({
    signal: "INCIDENT_RATE" as RiskSignal,
    value: 0,
    amberThreshold: 1,
    redThreshold: 2,
    sourceReference: "",
  });
  const [capa, setCapa] = useState(emptyCapa);
  const [verification, setVerification] = useState<Record<string, string>>({});

  const load = async (systemId = id) => {
    try {
      setError("");
      setSystems(await api.listSystems());
      if (systemId) {
        const [nextSummary, nextCapas] = await Promise.all([
          api.monitoringSummary(systemId),
          api.capas(systemId),
        ]);
        setSummary(nextSummary);
        setCapas(nextCapas);
        setCapa((current) =>
          current.signalId
            ? current
            : { ...current, signalId: nextSummary.recentSignals[0]?.id ?? "" },
        );
      }
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const recordSignal = async () => {
    try {
      await api.addSignal({ ...form, aiSystemId: id });
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const createCapa = async () => {
    try {
      await api.openCapa(capa);
      setCapa(emptyCapa);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const implement = async (capaId: string) => {
    try {
      await api.implementCapa(capaId);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const verify = async (capaId: string) => {
    try {
      await api.verifyCapa(capaId, verification[capaId] ?? "");
      setVerification((current) => ({ ...current, [capaId]: "" }));
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <Page
      title="Post-market monitoring"
      subtitle="Aggregated signals only. Ernest remains the evidence plane for execution logs."
    >
      {error && <Alert tone="danger">{error}</Alert>}
      <Field label="AI system" htmlFor="monitoring-ai-system">
        <Select
          id="monitoring-ai-system"
          value={id}
          onChange={(e) => {
            setId(e.target.value);
            setCapa(emptyCapa);
            load(e.target.value);
          }}
        >
          <option value="">Select…</option>
          {systems.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </Select>
      </Field>
      {id && (
        <>
          <Card style={{ marginBottom: "var(--sp-5)" }}>
            <h2>Risk posture {summary && <Badge>{summary.status}</Badge>}</h2>
            <p className="ct-muted">
              {summary?.observations ?? 0} observations ·{" "}
              {summary?.amberSignals ?? 0} amber · {summary?.redSignals ?? 0}{" "}
              red
            </p>
            <div className="ct-grid-2">
              <Select
                value={form.signal}
                onChange={(e) =>
                  setForm({ ...form, signal: e.target.value as RiskSignal })
                }
              >
                {signals.map((signal) => (
                  <option key={signal}>{signal.replaceAll("_", " ")}</option>
                ))}
              </Select>
              <Input
                type="number"
                aria-label="Signal value"
                value={form.value}
                onChange={(e) => setForm({ ...form, value: +e.target.value })}
              />
              <Input
                type="number"
                value={form.amberThreshold}
                onChange={(e) =>
                  setForm({ ...form, amberThreshold: +e.target.value })
                }
                placeholder="Amber threshold"
                aria-label="Amber threshold"
              />
              <Input
                type="number"
                value={form.redThreshold}
                onChange={(e) =>
                  setForm({ ...form, redThreshold: +e.target.value })
                }
                placeholder="Red threshold"
                aria-label="Red threshold"
              />
            </div>
            <Input
              placeholder="Ernest aggregate / source reference"
              value={form.sourceReference}
              onChange={(e) =>
                setForm({ ...form, sourceReference: e.target.value })
              }
            />
            <Button variant="primary" onClick={recordSignal}>
              Record signal
            </Button>
          </Card>
          <Card>
            <h2>Corrective and preventive actions</h2>
            <p className="ct-muted">
              Create an action from an observed signal, then retain evidence
              that its mitigation worked.
            </p>
            <div className="ct-grid-2">
              <Select
                value={capa.signalId}
                onChange={(e) => setCapa({ ...capa, signalId: e.target.value })}
                aria-label="Source signal"
              >
                <option value="">Select source signal…</option>
                {summary?.recentSignals.map((signal) => (
                  <option key={signal.id} value={signal.id}>
                    {signal.signal.replaceAll("_", " ")} · {signal.status}
                  </option>
                ))}
              </Select>
              <Input
                placeholder="Risk"
                value={capa.risk}
                onChange={(e) => setCapa({ ...capa, risk: e.target.value })}
              />
              <Input
                placeholder="Mitigation"
                value={capa.mitigation}
                onChange={(e) =>
                  setCapa({ ...capa, mitigation: e.target.value })
                }
              />
              <Input
                placeholder="Owner"
                value={capa.owner}
                onChange={(e) => setCapa({ ...capa, owner: e.target.value })}
              />
              <Input
                type="datetime-local"
                value={capa.dueAt}
                onChange={(e) => setCapa({ ...capa, dueAt: e.target.value })}
                aria-label="Due date"
              />
            </div>
            <Button
              variant="primary"
              disabled={
                !capa.signalId ||
                !capa.risk ||
                !capa.mitigation ||
                !capa.owner ||
                !capa.dueAt
              }
              onClick={createCapa}
            >
              Open CAPA
            </Button>
            {capas.length === 0 ? (
              <p className="ct-muted">No CAPA open.</p>
            ) : (
              capas.map((item) => (
                <div key={item.id} className="capa-item">
                  <b>{item.risk}</b>
                  <div className="ct-muted">
                    {item.mitigation} · {item.owner} · due {item.dueAt} ·{" "}
                    {item.status}
                  </div>
                  {item.status === "OPEN" && (
                    <Button onClick={() => implement(item.id)}>
                      Mark implemented
                    </Button>
                  )}
                  {item.status === "IMPLEMENTED" && (
                    <div className="ct-row">
                      <Input
                        placeholder="Effectiveness evidence reference"
                        value={verification[item.id] ?? ""}
                        onChange={(e) =>
                          setVerification((current) => ({
                            ...current,
                            [item.id]: e.target.value,
                          }))
                        }
                      />
                      <Button
                        variant="primary"
                        disabled={!(verification[item.id] ?? "").trim()}
                        onClick={() => verify(item.id)}
                      >
                        Verify effectiveness
                      </Button>
                    </div>
                  )}
                  {item.status === "VERIFIED" && (
                    <div className="ct-muted">
                      Effectiveness evidence: {item.effectivenessEvidence}
                    </div>
                  )}
                </div>
              ))
            )}
          </Card>
        </>
      )}
    </Page>
  );
}
