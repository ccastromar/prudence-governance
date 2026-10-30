import { useState } from "react";
import { api, type Decision } from "../api";
import { DecisionBadge } from "../components";
import { Alert, Button, Card, Field, Input, Page } from "@chestertown/design";

export default function Decisions() {
  const [req, setReq] = useState({
    subject: "alice@bank.eu",
    pipeline: "credit-risk-scoring",
    aiSystemId: "",
    action: "run",
  });
  const [decision, setDecision] = useState<Decision | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

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
    <Page
      title="Policy decisions"
      subtitle="Ask the policy decision point directly and inspect the verdict."
    >
      <h2>PDP query</h2>
      <p className="sub">
        The raw policy endpoint a workload gate consults. It is normally called
        by a machine identity, not a browser.
      </p>
      <Card className="ct-mb-6">
        <form onSubmit={submit}>
          <div className="ct-grid-2">
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
          <div className="ct-grid-2">
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
          <div className="ct-row ct-between">
            {error ? (
              <Alert tone="danger">{error}</Alert>
            ) : (
              <span className="ct-muted ct-text-sm">
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
          <div className="ct-row ct-between">
            <DecisionBadge decision={decision.decision} />
            {decision.approver && (
              <span className="ct-muted">
                approver: <b>{decision.approver}</b>
              </span>
            )}
          </div>
          {decision.reason && (
            <p className="ct-muted ct-mb-0">{decision.reason}</p>
          )}
        </Card>
      )}
    </Page>
  );
}
