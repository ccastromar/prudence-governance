import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type GovernedDemo,
  type GovernedDemoRun,
} from "../api";
import { ssoEnabled } from "../auth";
import { DecisionBadge } from "../components";
import {
  Alert,
  Badge,
  Button,
  Card,
  Field,
  Page,
  Select,
} from "@chestertown/design";

const POLL_MS = 1000;
const POLL_LIMIT = 240; // a run the runner did not finish in four minutes is reported as such

const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));

/// Poll a run until it finishes. An allowed run's execution report can reach Prudence a moment
/// after the agent ends, so an ALLOW without one is fetched a few more times.
async function follow(
  runId: string,
  onUpdate: (run: GovernedDemoRun) => void,
): Promise<void> {
  let settle = 3;
  for (let i = 0; i < POLL_LIMIT; i++) {
    const run = await api.demoRun(runId);
    onUpdate(run);
    const waitingForReport =
      run.status === "done" && run.continued && !run.prudence?.execution;
    if (run.status !== "running" && (!waitingForReport || --settle <= 0))
      return;
    await sleep(POLL_MS);
  }
  throw new Error("The governed run did not finish in time.");
}

function PrudenceRecord({ run }: { run: GovernedDemoRun }) {
  const decision = run.prudence?.decision;
  const execution = run.prudence?.execution;
  if (!decision && !execution) {
    return (
      <p className="ct-muted">
        Prudence has no decision for this run (the runtime never reached the
        PDP).
      </p>
    );
  }
  return (
    <div>
      <p className="ct-muted" style={{ marginBottom: "var(--sp-1)" }}>
        <b>Recorded by Prudence</b>
      </p>
      {decision && (
        <p className="ct-muted">
          Decision {decision.verdict} for <code>{decision.subject}</code>
          {decision.reason ? ` · ${decision.reason}` : ""}
        </p>
      )}
      <p className="ct-muted">
        {execution
          ? `Execution reported by ${execution.runtime}, citing the ${execution.decisionStatus} decision`
          : "No execution reported"}
      </p>
    </div>
  );
}

function DemoCard({
  demo,
  systems,
  latest,
}: {
  demo: GovernedDemo;
  systems: AiSystem[];
  latest?: GovernedDemoRun;
}) {
  const [systemId, setSystemId] = useState("");
  const [result, setResult] = useState<GovernedDemoRun | null>(latest ?? null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [writeAllowed, setWriteAllowed] = useState(false);

  useEffect(() => setResult(latest ?? null), [latest]);

  useEffect(() => {
    if (!ssoEnabled) {
      setWriteAllowed(true);
      return;
    }
    api
      .me()
      .then((me) => setWriteAllowed(me.functions.includes("system.declare")))
      .catch(() => setWriteAllowed(false));
  }, []);

  const run = async () => {
    if (!systemId) return;
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const started = await api.runDemo(demo.id, systemId);
      setResult(started);
      await follow(started.runId, setResult);
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
      <div className="ct-row ct-between">
        <h3 style={{ margin: 0 }}>{demo.title}</h3>
        <Badge>{demo.framework}</Badge>
      </div>
      <p className="sub">{demo.detail}</p>
      <Field
        label="Registered AI system"
        htmlFor={`decisions-system-${demo.id}`}
      >
        <Select
          id={`decisions-system-${demo.id}`}
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
      <div className="ct-row ct-between">
        <span className="ct-muted ct-text-sm">
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
        <p className="ct-muted">
          Only authors and administrators can start runs.
        </p>
      )}
      {error && <Alert tone="danger">{error}</Alert>}
      {result && (
        <Card
          style={{ marginTop: "var(--sp-4)", background: "var(--bg-subtle)" }}
        >
          {result.status === "running" && (
            <p className="ct-muted">The governed agent is running…</p>
          )}
          {result.status === "failed" && (
            <Alert tone="danger">
              {result.error || "The governed runtime failed."}
            </Alert>
          )}
          {result.status === "done" && result.decision && (
            <>
              <DecisionBadge
                decision={
                  result.decision === "ERROR" ? "DENY" : result.decision
                }
              />
              {result.model && (
                <p className="ct-muted">
                  Model: <b>{result.model}</b>
                </p>
              )}
              {result.output && <pre className="ct-code">{result.output}</pre>}
              <p className="ct-muted">
                The tool{" "}
                {result.continued
                  ? "ran"
                  : "did not run: it stopped at the governance gate"}
                .
              </p>
              <PrudenceRecord run={result} />
            </>
          )}
        </Card>
      )}
    </Card>
  );
}

export default function Integrations() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [demos, setDemos] = useState<GovernedDemo[] | null>(null);
  const [latest, setLatest] = useState<Record<string, GovernedDemoRun>>({});

  useEffect(() => {
    api
      .listSystems()
      .then((items) =>
        setSystems(items.filter((item) => item.approvalStatus === "APPROVED")),
      )
      .catch(() => undefined);
    api
      .demos()
      .then(setDemos)
      .catch(() => setDemos([]));
    api
      .demoRuns()
      .then(setLatest)
      .catch(() => undefined);
  }, []);

  return (
    <Page
      title="Integrations"
      subtitle="Run a real agent of each framework, governed by its Prudence adapter, and inspect what Prudence recorded for the run."
    >
      <h2>Governed runs</h2>
      {demos !== null && demos.length === 0 && (
        <p className="sub ct-mb-8">
          No governed demo runner is configured, or none is answering.
        </p>
      )}
      <div className="ct-grid-2 ct-mb-8">
        {(demos ?? []).map((demo) => (
          <DemoCard
            key={demo.id}
            demo={demo}
            systems={systems}
            latest={latest[demo.id]}
          />
        ))}
      </div>
    </Page>
  );
}
