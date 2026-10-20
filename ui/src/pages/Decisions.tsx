import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type Decision,
  type GovernedDemoRun,
} from "../api";
import { hasRealmRole } from "../auth";
import { DecisionBadge } from "../components";
import { Alert, Button, Card, Field, Input, Select } from "@chestertown/design";

type Demo = "bug-triage" | "response-drafter";

const demos: Record<
  Demo,
  { title: string; framework: string; detail: string }
> = {
  "bug-triage": {
    title: "Bug triage",
    framework: "Pydantic AI",
    detail:
      "A governed tool classifies and routes a synthetic checkout incident.",
  },
  "response-drafter": {
    title: "Response drafter",
    framework: "LangGraph",
    detail:
      "A governed graph node drafts a synthetic customer response; delivery only follows ALLOW.",
  },
};

function DemoCard({
  demo,
  systems,
  latest,
}: {
  demo: Demo;
  systems: AiSystem[];
  latest?: GovernedDemoRun;
}) {
  const [systemId, setSystemId] = useState("");
  const [result, setResult] = useState<GovernedDemoRun | null>(latest ?? null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const writeAllowed = hasRealmRole("read-write");
  const meta = demos[demo];

  useEffect(() => setResult(latest ?? null), [latest]);

  const run = async () => {
    if (!systemId) return;
    setBusy(true);
    setError("");
    setResult(null);
    try {
      setResult(await api.runDemo(demo, systemId));
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "The governed runtime could not be started.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <div className="row between">
        <h3 style={{ margin: 0 }}>{meta.title}</h3>
        <span className="badge neutral">{meta.framework}</span>
      </div>
      <p className="sub">{meta.detail}</p>
      <Field
        label="Registered AI system"
        htmlFor="decisions-registered-ai-system"
      >
        <Select
          id="decisions-registered-ai-system"
          value={systemId}
          onChange={(e) => setSystemId(e.target.value)}
          disabled={busy}
        >
          <option value="">Choose an approved system…</option>
          {systems.map((system) => (
            <option key={system.id} value={system.id}>
              {system.name} · {system.approvalStatus}
            </option>
          ))}
        </Select>
      </Field>
      <div className="row between">
        <span className="mut" style={{ fontSize: "var(--fs-sm)" }}>
          PDP → runtime hook → Ernest evidence
        </span>
        <Button
          variant="primary"
          disabled={!writeAllowed || !systemId || busy}
          onClick={run}
        >
          {busy ? "Running…" : "Run demo"}
        </Button>
      </div>
      {!writeAllowed && (
        <p className="mut">
          Read-only visitors can inspect outcomes but cannot start runs.
        </p>
      )}
      {error && <Alert tone="danger">{error}</Alert>}
      {result && (
        <Card
          style={{ marginTop: "var(--sp-4)", background: "var(--bg-subtle)" }}
        >
          <div className="row between">
            <DecisionBadge decision={result.decision} />
            {result.evidence?.status && (
              <span className="mut">Ernest HTTP {result.evidence.status}</span>
            )}
          </div>
          <p className="mut">{result.reason || "Governance gate completed."}</p>
          {result.model && (
            <p className="mut">
              Model: <b>{result.model}</b>
            </p>
          )}
          {result.output && <pre className="code">{result.output}</pre>}
          {result.continued !== undefined && (
            <p className="mut">
              Downstream delivery:{" "}
              {result.continued
                ? "continued"
                : "stopped at the governance gate"}
            </p>
          )}
        </Card>
      )}
    </Card>
  );
}

export default function Decisions() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [latest, setLatest] = useState<Partial<Record<Demo, GovernedDemoRun>>>(
    {},
  );
  const [req, setReq] = useState({
    subject: "alice@bank.eu",
    pipeline: "credit-risk-scoring",
    aiSystemId: "",
    action: "run",
  });
  const [decision, setDecision] = useState<Decision | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .listSystems()
      .then((items) =>
        setSystems(items.filter((item) => item.approvalStatus === "APPROVED")),
      )
      .catch(() => undefined);
    api
      .demoRuns()
      .then(setLatest)
      .catch(() => undefined);
  }, []);

  const set =
    (k: keyof typeof req) => (e: React.ChangeEvent<HTMLInputElement>) =>
      setReq({ ...req, [k]: e.target.value });
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    setDecision(null);
    try {
      setDecision(await api.decide(req));
    } catch {
      setError("PDP call failed (is the backend on :8099?)");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1>Policy decisions</h1>
      <p className="sub">
        Run a real governed framework hook, then inspect the policy verdict and
        evidence delivery without introducing another public UI.
      </p>
      <h2>Governed runs</h2>
      <div className="grid2" style={{ marginBottom: "var(--sp-8)" }}>
        <DemoCard
          demo="bug-triage"
          systems={systems}
          latest={latest["bug-triage"]}
        />
        <DemoCard
          demo="response-drafter"
          systems={systems}
          latest={latest["response-drafter"]}
        />
      </div>
      <h2>Advanced PDP query</h2>
      <p className="sub">
        The raw policy endpoint a workload gate consults. It is normally called
        by a machine identity, not a browser.
      </p>
      <Card style={{ marginBottom: "var(--sp-6)" }}>
        <form onSubmit={submit}>
          <div className="grid2">
            <Field label="Subject" htmlFor="decisions-subject">
              <Input
                id="decisions-subject"
                value={req.subject}
                onChange={set("subject")}
              />
            </Field>
            <Field label="Pipeline" htmlFor="decisions-pipeline">
              <Input
                id="decisions-pipeline"
                value={req.pipeline}
                onChange={set("pipeline")}
              />
            </Field>
          </div>
          <div className="grid2">
            <Field label="AI system id" htmlFor="decisions-ai-system-id">
              <Input
                id="decisions-ai-system-id"
                value={req.aiSystemId}
                onChange={set("aiSystemId")}
                placeholder="paste an id from the registry"
              />
            </Field>
            <Field label="Action" htmlFor="decisions-action">
              <Input
                id="decisions-action"
                value={req.action}
                onChange={set("action")}
              />
            </Field>
          </div>
          <div className="row between">
            {error ? (
              <Alert tone="danger">{error}</Alert>
            ) : (
              <span className="mut" style={{ fontSize: "var(--fs-sm)" }}>
                Lifecycle facts first, then policy authorization.
              </span>
            )}
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? "Deciding…" : "Evaluate"}
            </Button>
          </div>
        </form>
      </Card>
      {decision && (
        <Card>
          <div className="row between">
            <DecisionBadge decision={decision.decision} />
            {decision.approver && (
              <span className="mut">
                approver: <b>{decision.approver}</b>
              </span>
            )}
          </div>
          {decision.reason && (
            <p className="mut" style={{ marginBottom: 0 }}>
              {decision.reason}
            </p>
          )}
        </Card>
      )}
    </>
  );
}
