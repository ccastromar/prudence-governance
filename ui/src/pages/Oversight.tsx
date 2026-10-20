import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Field,
  Input,
  Select,
} from "@chestertown/design";
import {
  api,
  type AiSystem,
  type HumanAssignment,
  type HumanOverride,
  type OversightRole,
} from "../api";
const roles: OversightRole[] = [
  "OWNER",
  "SUPERVISOR",
  "COMPLIANCE",
  "DPO",
  "SECURITY",
  "PROVIDER",
];
export default function Oversight() {
  const [systems, setSystems] = useState<AiSystem[]>([]),
    [id, setId] = useState(""),
    [assignments, setAssignments] = useState<HumanAssignment[]>([]),
    [overrides, setOverrides] = useState<HumanOverride[]>([]),
    [error, setError] = useState("");
  const [a, setA] = useState({
    role: "SUPERVISOR" as OversightRole,
    person: "",
    overrideAuthority: true,
    stopAuthority: true,
    trainingEvidence: "",
    trainingExpiresAt: "",
  });
  const [o, setO] = useState({
    aosReference: "",
    supervisor: "",
    reason: "",
    outcome: "",
  });
  const [review, setReview] = useState({ reviewer: "", note: "" });
  const load = async (x = id) => {
    try {
      setSystems(await api.listSystems());
      if (x) {
        const [a, o] = await Promise.all([
          api.assignments(x),
          api.overrides(x),
        ]);
        setAssignments(a);
        setOverrides(o);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    load();
  }, []);
  return (
    <>
      <h1>Human oversight</h1>
      <p className="sub">
        Named authority, current training and auditable override decisions.
        Prudence governs capability; AOS executes the intervention.
      </p>
      {error && <Alert tone="danger">{error}</Alert>}
      <Field label="AI system" htmlFor="oversight-ai-system">
        <Select
          id="oversight-ai-system"
          value={id}
          onChange={(e) => {
            setId(e.target.value);
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
        <div className="grid2">
          <Card>
            <h2>Assignments</h2>
            {assignments.map((x) => (
              <p key={x.id}>
                <b>{x.person}</b> · {x.role} ·{" "}
                {x.overrideAuthority ? "override " : ""}
                {x.stopAuthority ? "stop " : ""} · training expires{" "}
                {x.trainingExpiresAt}
              </p>
            ))}
            <Select
              value={a.role}
              onChange={(e) =>
                setA({ ...a, role: e.target.value as OversightRole })
              }
            >
              {roles.map((r) => (
                <option key={r}>{r}</option>
              ))}
            </Select>
            <Input
              placeholder="Person"
              value={a.person}
              onChange={(e) => setA({ ...a, person: e.target.value })}
            />
            <Input
              placeholder="Training evidence URI"
              value={a.trainingEvidence}
              onChange={(e) => setA({ ...a, trainingEvidence: e.target.value })}
            />
            <Input
              placeholder="Training expiry ISO instant"
              value={a.trainingExpiresAt}
              onChange={(e) =>
                setA({ ...a, trainingExpiresAt: e.target.value })
              }
            />
            <Checkbox
              label="Override authority"
              checked={a.overrideAuthority}
              onChange={(e) =>
                setA({ ...a, overrideAuthority: e.target.checked })
              }
            />
            <Checkbox
              label="Stop authority"
              checked={a.stopAuthority}
              onChange={(e) => setA({ ...a, stopAuthority: e.target.checked })}
            />
            <Button
              variant="primary"
              onClick={async () => {
                try {
                  await api.addAssignment(id, a);
                  load();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              Assign
            </Button>
          </Card>
          <Card>
            <h2>Overrides from AOS</h2>
            {overrides.map((x) => (
              <div
                key={x.id}
                style={{
                  borderBottom: "1px solid var(--border)",
                  padding: "var(--sp-3) 0",
                }}
              >
                <b>{x.supervisor}</b> · {x.reason}
                <div className="mut">
                  {x.outcome} ·{" "}
                  {x.reviewedBy
                    ? `reviewed by ${x.reviewedBy}`
                    : "review pending"}
                </div>
                {!x.reviewedBy && (
                  <div className="row">
                    <Input
                      placeholder="Reviewer"
                      value={review.reviewer}
                      onChange={(e) =>
                        setReview({ ...review, reviewer: e.target.value })
                      }
                    />
                    <Input
                      placeholder="Review note"
                      value={review.note}
                      onChange={(e) =>
                        setReview({ ...review, note: e.target.value })
                      }
                    />
                    <Button
                      onClick={async () => {
                        try {
                          await api.reviewOverride(x.id, review);
                          setReview({ reviewer: "", note: "" });
                          load();
                        } catch (e) {
                          setError((e as Error).message);
                        }
                      }}
                    >
                      Review
                    </Button>
                  </div>
                )}
              </div>
            ))}
            <h3>Record override</h3>
            <Input
              placeholder="AOS reference"
              value={o.aosReference}
              onChange={(e) => setO({ ...o, aosReference: e.target.value })}
            />
            <Input
              placeholder="Supervisor"
              value={o.supervisor}
              onChange={(e) => setO({ ...o, supervisor: e.target.value })}
            />
            <Input
              placeholder="Reason"
              value={o.reason}
              onChange={(e) => setO({ ...o, reason: e.target.value })}
            />
            <Input
              placeholder="Outcome"
              value={o.outcome}
              onChange={(e) => setO({ ...o, outcome: e.target.value })}
            />
            <Button
              variant="primary"
              onClick={async () => {
                try {
                  await api.addOverride(id, o);
                  load();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              Record override
            </Button>
          </Card>
        </div>
      )}
    </>
  );
}
