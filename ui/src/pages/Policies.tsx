import { useEffect, useState } from "react";
import { Alert, Button, Card, Page } from "@chestertown/design";
import { getToken } from "../auth";

type Rule = { role: string; action: string; pipeline: string; effect: string };
type Definition = { id: string; name: string };
type PolicyEvent = {
  id: string;
  type: string;
  actor: string;
  occurredAt: string;
};
type Publication = {
  id: string;
  digest: string;
  createdBy: string;
  createdAt: string;
  rollbackOf: string | null;
};
type Observation = {
  instanceId: string;
  publicationId: string;
  observedAt: string;
};
type Status = {
  managed: boolean;
  actorMode: string;
  approvalAvailable: boolean;
  roles: string[];
  requiredPublicationId: string | null;
  requiredDigest: string | null;
};
type Probe = { state: string; publicationId: string | null; detail: string };
type Draft = {
  revisionId: string;
  revision: number;
  name: string;
  status: string;
  version: number;
  rules: Rule[];
  createdBy: string;
  approvedBy: string | null;
};

class RequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const REQUIRED_ROLE: Record<string, string> = {
  approve: "policy-reviewer",
};

/** A readable explanation for an HTTP failure; never exposes raw tokens or stack traces. */
function explain(error: unknown, action = ""): string {
  if (!(error instanceof RequestError)) return (error as Error).message;
  if (error.status === 401)
    return "Your session has expired. Sign in again to continue.";
  if (error.status === 403) {
    if (action === "read")
      return 'Your account cannot view policies. Ask an administrator for the "policy-admin" or "policy-reviewer" role.';
    const role = REQUIRED_ROLE[action] ?? "policy-admin";
    return `Your account is not allowed to do this. It needs the "${role}" role in the identity provider${
      action === "approve"
        ? ", and it cannot be the account that authored or edited the revision"
        : ""
    }.`;
  }
  if (error.status === 404 && action === "publish")
    return "Managed policy publication is not enabled on this deployment.";
  return error.message;
}

async function request<T>(
  url: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const token = getToken();
  const response = await fetch(url, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("prudence:unauthorized"));
    const detail = await response
      .json()
      .then((b: { detail?: string; message?: string }) => b.detail ?? b.message)
      .catch(() => undefined);
    throw new RequestError(
      response.status,
      detail
        ? `${detail} (${response.status})`
        : `Request failed (${response.status})`,
    );
  }
  return response.json() as Promise<T>;
}

function activation(status: Status, observations: Observation[]): string {
  const seen = observations.filter(
    (o) => o.publicationId === status.requiredPublicationId,
  );
  return seen.length
    ? `Required publication ${status.requiredPublicationId} was observed active on ${seen
        .map((o) => o.instanceId)
        .join(", ")}.`
    : `Required publication ${status.requiredPublicationId} is requested but not yet observed on any OPA instance; decisions are denied until it is (use "Check OPA now").`;
}

export default function Policies() {
  const [definitions, setDefinitions] = useState<Definition[]>([]);
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [pipeline, setPipeline] = useState("");
  const [effect, setEffect] = useState("ALLOW");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [rules, setRules] = useState<Rule[]>([]);
  const [status, setStatus] = useState<Status | null>(null);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [publications, setPublications] = useState<Publication[] | null>(null);
  const [observations, setObservations] = useState<Observation[]>([]);
  const [history, setHistory] = useState<{
    revision: string;
    events: PolicyEvent[];
  } | null>(null);
  const load = async () => {
    const current = await request<Status>("/api/policy-admin/status");
    setStatus(current);
    if (current.managed)
      setObservations(
        await request<Observation[]>(
          "/api/policy-admin/publications/observations",
        ),
      );
    const items = await request<Definition[]>("/api/policy-admin/definitions");
    setDefinitions(items);
    const revisions = await Promise.all(
      items.map((item) =>
        request<Draft[]>(
          `/api/policy-admin/definitions/${encodeURIComponent(item.id)}/revisions`,
        ),
      ),
    );
    setDrafts(revisions.flat());
  };
  useEffect(() => {
    void load().catch((e: unknown) => setError(explain(e, "read")));
  }, []);
  const run = async (work: () => Promise<void>, action = "") => {
    setBusy(true);
    setError("");
    try {
      await work();
      await load();
    } catch (e) {
      setError(explain(e, action));
    } finally {
      setBusy(false);
    }
  };
  const create = () =>
    run(async () => {
      await request("/api/policy-admin/drafts", "POST", {
        name,
        rules: [{ role, pipeline, effect, action: "pipeline.run" }],
      });
      setName("");
    });
  const transition = (draft: Draft, operation: string) =>
    run(async () => {
      await request(
        `/api/policy-admin/drafts/${draft.revisionId}/${operation}`,
        "POST",
        { version: draft.version },
      );
    }, operation);
  const simulate = (draft: Draft) =>
    run(async () => {
      const result = await request<{ decision: string; reason: string }>(
        `/api/policy-admin/drafts/${draft.revisionId}/simulate`,
        "POST",
        { roles: [role], pipeline, action: "pipeline.run" },
      );
      setPreview(
        `${result.decision}: ${result.reason}. Preview only; not an authorization.`,
      );
    });
  const saveRules = (draft: Draft) =>
    run(async () => {
      await request(`/api/policy-admin/drafts/${draft.revisionId}`, "PUT", {
        version: draft.version,
        rules,
      });
      setEditing(null);
    });
  const showHistory = (draft: Draft) =>
    run(async () => {
      const events = await request<PolicyEvent[]>(
        `/api/policy-admin/drafts/${draft.revisionId}/events`,
      );
      setHistory({ revision: draft.revisionId, events });
    });
  const showPublications = async () => {
    setPublications(
      await request<Publication[]>("/api/policy-admin/publications"),
    );
    setObservations(
      await request<Observation[]>(
        "/api/policy-admin/publications/observations",
      ),
    );
  };
  const publish = (draft: Draft) =>
    run(async () => {
      await request("/api/policy-admin/publications", "POST", {
        revisionId: draft.revisionId,
        version: draft.version,
      });
      await showPublications();
    }, "publish");
  const rollback = (publication: Publication) =>
    run(async () => {
      await request("/api/policy-admin/publications/rollback", "POST", {
        publicationId: publication.id,
      });
      await showPublications();
    }, "publish");
  const checkOpa = () =>
    run(async () => {
      setProbe(
        await request<Probe>("/api/policy-admin/publications/probe", "POST"),
      );
      await showPublications();
    }, "publish");
  return (
    <Page>
      <h1>Policy drafts</h1>
      <p>
        Drafts and approvals do not affect the active policy. A publication is
        only observed after an OPA instance evaluates it.
      </p>
      {error && <Alert tone="danger">{error}</Alert>}
      {status && (
        <Card>
          <h2>Status</h2>
          <ul>
            <li>
              Your policy roles:{" "}
              {status.roles.length ? status.roles.join(", ") : "none"}
            </li>
            <li>
              Publication:{" "}
              {status.managed
                ? "managed (Prudence delivers bundles to OPA)"
                : "not enabled; drafts stay inert and OPA keeps its static policy"}
            </li>
            <li>
              Approval:{" "}
              {status.approvalAvailable
                ? "requires a different reviewer, identified by issuer and subject"
                : "unavailable until issuer-subject identities are enabled"}
            </li>
            {status.managed && (
              <li>
                {status.requiredPublicationId
                  ? activation(status, observations)
                  : "Nothing is published: managed decisions are denied until an approved policy is published."}
              </li>
            )}
          </ul>
        </Card>
      )}
      <Card>
        <h2>Publications</h2>
        <p>
          Observations show the last verified decision per OPA instance, not the
          health of the whole deployment. A publication is requested, not
          active, until OPA is observed serving it.
        </p>
        <Button
          disabled={busy || !status?.managed}
          onClick={() => void run(showPublications)}
        >
          Load publications
        </Button>{" "}
        {status?.managed && (
          <Button
            disabled={busy || !status.roles.includes("policy-admin")}
            onClick={() => void checkOpa()}
          >
            Check OPA now
          </Button>
        )}
        {probe && (
          <p role="status">
            {probe.state}: {probe.detail}
          </p>
        )}
        {publications?.map((publication) => (
          <div key={publication.id}>
            <p>
              {publication.id} · {publication.digest} · {publication.createdBy}{" "}
              · {publication.createdAt}
              {publication.rollbackOf &&
                ` · rollback of ${publication.rollbackOf}`}
            </p>
            <Button
              disabled={
                busy ||
                !status?.managed ||
                !status.roles.includes("policy-admin")
              }
              onClick={() => void rollback(publication)}
            >
              Publish this snapshot as rollback
            </Button>
          </div>
        ))}
        {observations.map((observation) => (
          <p key={observation.instanceId}>
            OPA {observation.instanceId}: observed {observation.publicationId}{" "}
            at {observation.observedAt}
          </p>
        ))}
      </Card>
      <Card>
        <h2>Create a draft</h2>
        <label>
          Policy name{" "}
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>{" "}
        <label>
          Role <input value={role} onChange={(e) => setRole(e.target.value)} />
        </label>{" "}
        <label>
          Pipeline{" "}
          <input
            value={pipeline}
            onChange={(e) => setPipeline(e.target.value)}
          />
        </label>{" "}
        <label>
          Effect{" "}
          <select value={effect} onChange={(e) => setEffect(e.target.value)}>
            <option>ALLOW</option>
            <option>DENY</option>
          </select>
        </label>{" "}
        <Button
          disabled={
            busy ||
            !status?.roles.includes("policy-admin") ||
            !name.trim() ||
            !role.trim() ||
            !pipeline.trim()
          }
          onClick={() => void create()}
        >
          Create draft
        </Button>
      </Card>
      {preview && <p role="status">{preview}</p>}
      <p>{definitions.length} policies</p>
      {drafts.map((draft) => (
        <Card key={draft.revisionId}>
          <h2>{draft.name}</h2>
          <p>
            {draft.status} · revision {draft.revision} · version {draft.version}{" "}
            · author {draft.createdBy}
          </p>
          <ul>
            {draft.rules.map((rule, i) => (
              <li key={i}>
                {rule.effect} {rule.role} → {rule.action} · {rule.pipeline}
              </li>
            ))}
          </ul>
          {draft.status === "DRAFT" && (
            <>
              {editing === draft.revisionId ? (
                <>
                  {rules.map((rule, index) => (
                    <div key={index}>
                      <label>
                        Role{" "}
                        <input
                          value={rule.role}
                          onChange={(e) =>
                            setRules(
                              rules.map((r, i) =>
                                i === index
                                  ? { ...r, role: e.target.value }
                                  : r,
                              ),
                            )
                          }
                        />
                      </label>{" "}
                      <label>
                        Pipeline{" "}
                        <input
                          value={rule.pipeline}
                          onChange={(e) =>
                            setRules(
                              rules.map((r, i) =>
                                i === index
                                  ? { ...r, pipeline: e.target.value }
                                  : r,
                              ),
                            )
                          }
                        />
                      </label>{" "}
                      <label>
                        Effect{" "}
                        <select
                          value={rule.effect}
                          onChange={(e) =>
                            setRules(
                              rules.map((r, i) =>
                                i === index
                                  ? { ...r, effect: e.target.value }
                                  : r,
                              ),
                            )
                          }
                        >
                          <option>ALLOW</option>
                          <option>DENY</option>
                        </select>
                      </label>{" "}
                      <Button
                        disabled={rules.length === 1}
                        onClick={() =>
                          setRules(rules.filter((_, i) => i !== index))
                        }
                      >
                        Remove
                      </Button>
                    </div>
                  ))}
                  <Button
                    disabled={rules.length >= 100}
                    onClick={() =>
                      setRules([
                        ...rules,
                        {
                          role: "",
                          pipeline: "",
                          action: "pipeline.run",
                          effect: "ALLOW",
                        },
                      ])
                    }
                  >
                    Add rule
                  </Button>{" "}
                  <Button
                    disabled={
                      busy ||
                      rules.some((r) => !r.role.trim() || !r.pipeline.trim())
                    }
                    onClick={() => void saveRules(draft)}
                  >
                    Save
                  </Button>{" "}
                  <Button onClick={() => setEditing(null)}>Cancel</Button>
                </>
              ) : (
                <Button
                  disabled={busy || !status?.roles.includes("policy-admin")}
                  onClick={() => {
                    setEditing(draft.revisionId);
                    setRules(draft.rules.map((r) => ({ ...r })));
                  }}
                >
                  Edit rules
                </Button>
              )}{" "}
              <Button
                disabled={
                  busy ||
                  !status?.roles.includes("policy-admin") ||
                  editing === draft.revisionId
                }
                onClick={() => void transition(draft, "validate")}
              >
                Validate
              </Button>{" "}
              <Button
                disabled={
                  busy ||
                  !status?.roles.includes("policy-admin") ||
                  !role.trim() ||
                  !pipeline.trim()
                }
                onClick={() => void simulate(draft)}
              >
                Simulate using role and pipeline above
              </Button>
            </>
          )}
          {draft.status === "VALIDATED" && (
            <>
              <Button
                disabled={
                  busy ||
                  !status?.approvalAvailable ||
                  !status.roles.includes("policy-reviewer")
                }
                onClick={() => void transition(draft, "approve")}
              >
                Approve
              </Button>{" "}
              <Button
                disabled={busy || !status?.roles.includes("policy-admin")}
                onClick={() => void transition(draft, "reopen")}
              >
                Return to draft
              </Button>
            </>
          )}
          {["APPROVED", "ACTIVE", "SUPERSEDED", "FAILED"].includes(
            draft.status,
          ) && (
            <Button
              disabled={busy || !status?.roles.includes("policy-admin")}
              onClick={() => void transition(draft, "fork")}
            >
              Create new revision
            </Button>
          )}
          {draft.approvedBy && <p>Approved by {draft.approvedBy}</p>}
          {draft.status === "APPROVED" && (
            <Button
              disabled={
                busy ||
                !status?.managed ||
                !status.roles.includes("policy-admin")
              }
              onClick={() => void publish(draft)}
            >
              Publish approved revision
            </Button>
          )}{" "}
          <Button disabled={busy} onClick={() => void showHistory(draft)}>
            Show local history
          </Button>
          {history?.revision === draft.revisionId && (
            <ul>
              {history.events.map((event) => (
                <li key={event.id}>
                  {event.type} · {event.actor} · {event.occurredAt}
                </li>
              ))}
            </ul>
          )}
        </Card>
      ))}
    </Page>
  );
}
