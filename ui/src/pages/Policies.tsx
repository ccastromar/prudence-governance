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
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response.json() as Promise<T>;
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
  const [publications, setPublications] = useState<Publication[] | null>(null);
  const [observations, setObservations] = useState<Observation[]>([]);
  const [history, setHistory] = useState<{
    revision: string;
    events: PolicyEvent[];
  } | null>(null);
  const load = async () => {
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
    void load().catch((e: Error) => setError(e.message));
  }, []);
  const run = async (work: () => Promise<void>) => {
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
    });
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
    });
  const rollback = (publication: Publication) =>
    run(async () => {
      await request("/api/policy-admin/publications/rollback", "POST", {
        publicationId: publication.id,
      });
      await showPublications();
    });
  return (
    <Page>
      <h1>Policy drafts</h1>
      <p>
        Drafts and approvals do not affect the active policy. A publication is
        only observed after an OPA instance evaluates it.
      </p>
      {error && <Alert tone="danger">{error}</Alert>}
      <Card>
        <h2>Publications</h2>
        <p>
          Managed publication must be enabled by the operator. Observations show
          the last verified decision per OPA instance, not the health of the
          whole deployment.
        </p>
        <Button disabled={busy} onClick={() => void run(showPublications)}>
          Load publications
        </Button>
        {publications?.map((publication) => (
          <div key={publication.id}>
            <p>
              {publication.id} · {publication.digest} · {publication.createdBy}{" "}
              · {publication.createdAt}
              {publication.rollbackOf &&
                ` · rollback of ${publication.rollbackOf}`}
            </p>
            <Button disabled={busy} onClick={() => void rollback(publication)}>
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
          disabled={busy || !name.trim() || !role.trim() || !pipeline.trim()}
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
                  disabled={busy}
                  onClick={() => {
                    setEditing(draft.revisionId);
                    setRules(draft.rules.map((r) => ({ ...r })));
                  }}
                >
                  Edit rules
                </Button>
              )}{" "}
              <Button
                disabled={busy || editing === draft.revisionId}
                onClick={() => void transition(draft, "validate")}
              >
                Validate
              </Button>{" "}
              <Button
                disabled={busy || !role.trim() || !pipeline.trim()}
                onClick={() => void simulate(draft)}
              >
                Simulate using role and pipeline above
              </Button>
            </>
          )}
          {draft.status === "VALIDATED" && (
            <>
              <Button
                disabled={busy}
                onClick={() => void transition(draft, "approve")}
              >
                Approve
              </Button>{" "}
              <Button
                disabled={busy}
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
              disabled={busy}
              onClick={() => void transition(draft, "fork")}
            >
              Create new revision
            </Button>
          )}
          {draft.approvedBy && <p>Approved by {draft.approvedBy}</p>}
          {draft.status === "APPROVED" && (
            <Button disabled={busy} onClick={() => void publish(draft)}>
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
