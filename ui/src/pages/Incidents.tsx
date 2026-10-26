import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type CausalLink,
  type Incident,
  type IncidentCategory,
  type OpenIncidentInput,
  type ReportingState,
} from "../api";
import { EvidenceState, PERMANENT_EVIDENCE_HINT } from "../components";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Field,
  Input,
  Page,
  Select,
  Table,
  Textarea,
} from "@chestertown/design";

const CATEGORIES: { value: IncidentCategory; label: string }[] = [
  { value: "DEATH", label: "Death of a person — Art. 3(49)(a)" },
  {
    value: "SERIOUS_HARM_TO_HEALTH",
    label: "Serious harm to health — Art. 3(49)(a)",
  },
  {
    value: "CRITICAL_INFRASTRUCTURE_DISRUPTION",
    label: "Critical infrastructure disruption — Art. 3(49)(b)",
  },
  {
    value: "FUNDAMENTAL_RIGHTS_INFRINGEMENT",
    label: "Fundamental-rights infringement — Art. 3(49)(c)",
  },
  {
    value: "PROPERTY_OR_ENVIRONMENT_HARM",
    label: "Serious harm to property or environment — Art. 3(49)(d)",
  },
  { value: "NOT_SERIOUS", label: "Not a serious incident" },
];
const LINKS: CausalLink[] = ["SUSPECTED", "ESTABLISHED", "RULED_OUT"];
const stateColor: Record<ReportingState, string> = {
  NOT_REQUIRED: "var(--text-muted)",
  DUE: "var(--warn)",
  OVERDUE: "var(--danger)",
  REPORTED_ON_TIME: "var(--ok)",
  REPORTED_LATE: "var(--danger)",
};
const when = (iso: string | null) =>
  (iso ?? "")
    .replace("T", " ")
    .replace(/\.\d+Z?$/, "")
    .replace("Z", "");
/// `<input type="datetime-local">` speaks local time without a zone; the API speaks UTC instants.
const toIso = (local: string) => new Date(local).toISOString();
const nowLocal = () => {
  const d = new Date();
  d.setSeconds(0, 0);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};

const EMPTY = (): Omit<
  OpenIncidentInput,
  "occurredAt" | "detectedAt" | "memberStates"
> & { occurredAt: string; detectedAt: string; memberStates: string } => ({
  aiSystemId: "",
  title: "",
  description: "",
  occurredAt: nowLocal(),
  detectedAt: nowLocal(),
  memberStates: "",
  category: "NOT_SERIOUS",
  widespreadInfringement: false,
  causalLink: "SUSPECTED",
  suspendSystem: false,
});

function Countdown({ incident }: { incident: Incident }) {
  if (!incident.reportingDeadline) return <span className="ct-muted">—</span>;
  const ms = new Date(incident.reportingDeadline).getTime() - Date.now();
  const hours = Math.round(Math.abs(ms) / 3600000);
  const text =
    incident.reportingState === "DUE"
      ? `due in ${hours < 48 ? `${hours}h` : `${Math.round(hours / 24)}d`}`
      : incident.reportingState === "OVERDUE"
        ? `overdue by ${hours < 48 ? `${hours}h` : `${Math.round(hours / 24)}d`}`
        : incident.reportingState.replaceAll("_", " ").toLowerCase();
  return (
    <span
      style={{ color: stateColor[incident.reportingState], fontWeight: 600 }}
    >
      ● {text}
    </span>
  );
}

/// The AI incident register (AI Act Art. 73). Prudence does not file anything: it computes
/// what is owed to whom and by when, alerts before the deadline, and records each filing.
export default function Incidents() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [form, setForm] = useState(EMPTY());
  const [report, setReport] = useState({
    recipient: "",
    reference: "",
    submittedAt: nowLocal(),
    rootCause: "",
    correctiveActions: "",
  });
  const [note, setNote] = useState("");
  const [triage, setTriage] = useState<{
    category: IncidentCategory;
    causalLink: CausalLink;
    widespreadInfringement: boolean;
  }>({
    category: "NOT_SERIOUS",
    causalLink: "SUSPECTED",
    widespreadInfringement: false,
  });
  const select = (i: Incident) => {
    setSelected(i);
    setTriage({
      category: i.category,
      causalLink: i.causalLink,
      widespreadInfringement: i.widespreadInfringement,
    });
  };
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      const [s, i] = await Promise.all([api.listSystems(), api.incidents()]);
      setSystems(s);
      setIncidents(i);
      if (selected) select(await api.incident(selected.id));
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    load();
  }, []);

  const act = async (fn: () => Promise<Incident>) => {
    setBusy(true);
    setError("");
    try {
      const updated = await fn();
      select(updated);
      setNote("");
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    act(async () => {
      const created = await api.openIncident({
        ...form,
        occurredAt: toIso(form.occurredAt),
        detectedAt: toIso(form.detectedAt),
        memberStates: form.memberStates
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean),
      });
      setForm(EMPTY());
      return created;
    });
  };

  const open = async (i: Incident) => {
    setError("");
    try {
      select(await api.incident(i.id));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const systemName = (id: string) =>
    systems.find((s) => s.id === id)?.name ?? id;
  const reportBody = () => ({
    recipient: report.recipient,
    reference: report.reference,
    submittedAt: toIso(report.submittedAt),
  });

  return (
    <Page
      title="Incidents"
      subtitle="The AI incident register. Serious incidents (AI Act Art. 3(49)) must be reported within 2, 10 or 15 days of awareness (Art. 73). Prudence tracks the deadline, alerts the owners, and records each filing — it does not file with the authority itself."
    >
      {error && (
        <Alert tone="danger" className="ct-mb-6">
          {error}
        </Alert>
      )}

      <Card className="ct-mb-6">
        <h2 className="ct-section-title">Register an incident</h2>
        <form onSubmit={submit}>
          <div className="ct-grid-2">
            <Field label="AI system" htmlFor="incidents-ai-system">
              <Select
                id="incidents-ai-system"
                value={form.aiSystemId}
                onChange={(e) =>
                  setForm({ ...form, aiSystemId: e.target.value })
                }
                required
              >
                <option value="">Select…</option>
                {systems.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.regulatoryRole})
                  </option>
                ))}
              </Select>
            </Field>
            <Field
              label="Title"
              htmlFor="incidents-title"
              hint={PERMANENT_EVIDENCE_HINT}
            >
              <Input
                id="incidents-title"
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
                required
              />
            </Field>
          </div>
          <Field
            label="What happened"
            htmlFor="incidents-what-happened"
            hint={PERMANENT_EVIDENCE_HINT}
          >
            <Textarea
              id="incidents-what-happened"
              value={form.description}
              onChange={(e) =>
                setForm({ ...form, description: e.target.value })
              }
              required
            />
          </Field>
          <div className="ct-grid-2">
            <Field label="Occurred at" htmlFor="incidents-occurred-at">
              <Input
                id="incidents-occurred-at"
                type="datetime-local"
                value={form.occurredAt}
                onChange={(e) =>
                  setForm({ ...form, occurredAt: e.target.value })
                }
                required
              />
            </Field>
            <Field
              label="Became aware at (deadline runs from here)"
              htmlFor="incidents-became-aware-at-deadline-runs-from-here"
            >
              <Input
                id="incidents-became-aware-at-deadline-runs-from-here"
                type="datetime-local"
                value={form.detectedAt}
                onChange={(e) =>
                  setForm({ ...form, detectedAt: e.target.value })
                }
                required
              />
            </Field>
          </div>
          <div className="ct-grid-2">
            <Field label="Classification" htmlFor="incidents-classification">
              <Select
                id="incidents-classification"
                value={form.category}
                onChange={(e) =>
                  setForm({
                    ...form,
                    category: e.target.value as IncidentCategory,
                  })
                }
              >
                {CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field
              label="Causal link to the AI system"
              htmlFor="incidents-causal-link-to-the-ai-system"
            >
              <Select
                id="incidents-causal-link-to-the-ai-system"
                value={form.causalLink}
                onChange={(e) =>
                  setForm({ ...form, causalLink: e.target.value as CausalLink })
                }
              >
                {LINKS.map((l) => (
                  <option key={l} value={l}>
                    {l}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="ct-grid-2">
            <Field
              label="Member State(s) where it occurred"
              htmlFor="incidents-member-state-s-where-it-occurred"
            >
              <Input
                id="incidents-member-state-s-where-it-occurred"
                value={form.memberStates}
                onChange={(e) =>
                  setForm({ ...form, memberStates: e.target.value })
                }
                required
                placeholder="e.g. ES, PT"
              />
            </Field>
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                gap: "var(--sp-2)",
                marginBottom: "var(--sp-4)",
              }}
            >
              <Checkbox
                label="Widespread infringement (2-day deadline)"
                checked={form.widespreadInfringement}
                onChange={(e) =>
                  setForm({
                    ...form,
                    widespreadInfringement: e.target.checked,
                  })
                }
              />
              <Checkbox
                label="Suspend the AI system now"
                checked={form.suspendSystem}
                onChange={(e) =>
                  setForm({ ...form, suspendSystem: e.target.checked })
                }
              />
            </div>
          </div>
          <div className="ct-row ct-between">
            <span className="ct-muted ct-text-sm">
              A serious incident alerts governance and the system owner
              immediately.
            </span>
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? "Saving…" : "Register incident"}
            </Button>
          </div>
        </form>
      </Card>

      <div className="ct-mb-6">
        <Table>
          <thead>
            <tr>
              <th>Incident</th>
              <th>AI system</th>
              <th>Classification</th>
              <th>Status</th>
              <th>Report</th>
            </tr>
          </thead>
          <tbody>
            {incidents.length === 0 && (
              <tr>
                <td colSpan={5} className="ct-muted">
                  No incidents registered.
                </td>
              </tr>
            )}
            {incidents.map((i) => (
              <tr
                key={i.id}
                onClick={() => open(i)}
                style={{ cursor: "pointer" }}
                className={selected?.id === i.id ? "ct-selected" : ""}
              >
                <td>
                  <div className="ct-strong">{i.title}</div>
                  <span className="ct-muted ct-text-sm">
                    aware {when(i.detectedAt)}
                  </span>
                </td>
                <td>{systemName(i.aiSystemId)}</td>
                <td className="ct-muted ct-text-sm">
                  {i.category.replaceAll("_", " ")}
                  {i.widespreadInfringement ? " · widespread" : ""}
                  <div>link {i.causalLink.toLowerCase()}</div>
                </td>
                <td className="ct-muted">{i.status.replaceAll("_", " ")}</td>
                <td>
                  <Countdown incident={i} />
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      </div>

      {selected && (
        <Card>
          <div className="ct-row ct-between">
            <h2 className="ct-section-title">{selected.title}</h2>
            <Button size="sm" onClick={() => setSelected(null)}>
              Close
            </Button>
          </div>
          <p>{selected.description}</p>
          <div className="ct-muted ct-text-sm ct-mb-4">
            <div>{selected.reportingBasis}</div>
            {selected.reportable && (
              <div>
                Report to: <b>{selected.reportingRecipient}</b>
              </div>
            )}
            {selected.reportingDeadline && (
              <div>
                Deadline: <b>{when(selected.reportingDeadline)} UTC</b> ·{" "}
                <Countdown incident={selected} />
              </div>
            )}
            {selected.initialReport && (
              <div>
                Initial report: {selected.initialReport.recipient}, ref{" "}
                <code>{selected.initialReport.reference}</code>,{" "}
                {when(selected.initialReport.submittedAt)} (recorded by{" "}
                {selected.initialReport.recordedBy})
              </div>
            )}
            {selected.finalReport && (
              <div>
                Final report: {selected.finalReport.recipient}, ref{" "}
                <code>{selected.finalReport.reference}</code>,{" "}
                {when(selected.finalReport.submittedAt)} — root cause:{" "}
                {selected.rootCause}; corrective actions:{" "}
                {selected.correctiveActions}
              </div>
            )}
            {selected.closedAt && (
              <div>
                Closed {when(selected.closedAt)} by {selected.closedBy}:{" "}
                {selected.closingNote}
              </div>
            )}
          </div>

          {selected.status !== "CLOSED" && (
            <Card className="ct-mb-4">
              {selected.reportable &&
                (selected.status === "OPEN" ||
                  selected.status === "INITIAL_REPORT_SUBMITTED") && (
                  <>
                    <h3 className="ct-mt-0">Record a filing</h3>
                    <div className="ct-grid-2">
                      <Field label="Filed with" htmlFor="incidents-filed-with">
                        <Input
                          id="incidents-filed-with"
                          value={report.recipient}
                          onChange={(e) =>
                            setReport({ ...report, recipient: e.target.value })
                          }
                          placeholder="e.g. AESIA (ES)"
                        />
                      </Field>
                      <Field
                        label="Their reference"
                        htmlFor="incidents-their-reference"
                      >
                        <Input
                          id="incidents-their-reference"
                          value={report.reference}
                          onChange={(e) =>
                            setReport({ ...report, reference: e.target.value })
                          }
                        />
                      </Field>
                    </div>
                    <Field
                      label="Submitted at"
                      htmlFor="incidents-submitted-at"
                    >
                      <Input
                        id="incidents-submitted-at"
                        type="datetime-local"
                        value={report.submittedAt}
                        onChange={(e) =>
                          setReport({ ...report, submittedAt: e.target.value })
                        }
                      />
                    </Field>
                    <div className="ct-grid-2">
                      <Field
                        label="Root cause (final report)"
                        htmlFor="incidents-root-cause-final-report"
                        hint={PERMANENT_EVIDENCE_HINT}
                      >
                        <Textarea
                          id="incidents-root-cause-final-report"
                          value={report.rootCause}
                          onChange={(e) =>
                            setReport({ ...report, rootCause: e.target.value })
                          }
                        />
                      </Field>
                      <Field
                        label="Corrective actions (final report)"
                        htmlFor="incidents-corrective-actions-final-report"
                        hint={PERMANENT_EVIDENCE_HINT}
                      >
                        <Textarea
                          id="incidents-corrective-actions-final-report"
                          value={report.correctiveActions}
                          onChange={(e) =>
                            setReport({
                              ...report,
                              correctiveActions: e.target.value,
                            })
                          }
                        />
                      </Field>
                    </div>
                    <div className="ct-mb-4">
                      {selected.status === "OPEN" && (
                        <Button
                          disabled={busy}
                          style={{ marginRight: "var(--sp-2)" }}
                          onClick={() =>
                            act(() =>
                              api.initialReport(selected.id, reportBody()),
                            )
                          }
                        >
                          Record initial report
                        </Button>
                      )}
                      <Button
                        variant="primary"
                        disabled={busy}
                        onClick={() =>
                          act(() =>
                            api.finalReport(selected.id, {
                              ...reportBody(),
                              rootCause: report.rootCause,
                              correctiveActions: report.correctiveActions,
                            }),
                          )
                        }
                      >
                        Record final report
                      </Button>
                    </div>
                  </>
                )}
              <Field
                label="Note (required to re-triage or close)"
                htmlFor="incidents-note-required-to-re-triage-or-close"
                hint={PERMANENT_EVIDENCE_HINT}
              >
                <Textarea
                  id="incidents-note-required-to-re-triage-or-close"
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                />
              </Field>
              {selected.status === "OPEN" && (
                <div
                  className="ct-grid-2"
                  style={{ marginBottom: "var(--sp-3)" }}
                >
                  <Select
                    value={triage.category}
                    onChange={(e) =>
                      setTriage({
                        ...triage,
                        category: e.target.value as IncidentCategory,
                      })
                    }
                  >
                    {CATEGORIES.map((c) => (
                      <option key={c.value} value={c.value}>
                        {c.label}
                      </option>
                    ))}
                  </Select>
                  <Select
                    value={triage.causalLink}
                    onChange={(e) =>
                      setTriage({
                        ...triage,
                        causalLink: e.target.value as CausalLink,
                      })
                    }
                  >
                    {LINKS.map((l) => (
                      <option key={l} value={l}>
                        {l}
                      </option>
                    ))}
                  </Select>
                  <Checkbox
                    label="Widespread infringement"
                    checked={triage.widespreadInfringement}
                    onChange={(e) =>
                      setTriage({
                        ...triage,
                        widespreadInfringement: e.target.checked,
                      })
                    }
                  />
                </div>
              )}
              {selected.status === "OPEN" && (
                <Button
                  disabled={busy || !note}
                  style={{ marginRight: "var(--sp-2)" }}
                  onClick={() =>
                    act(() =>
                      api.triageIncident(selected.id, { ...triage, note }),
                    )
                  }
                >
                  Re-triage
                </Button>
              )}
              <Button
                disabled={busy || !note}
                onClick={() => act(() => api.closeIncident(selected.id, note))}
              >
                Close incident
              </Button>
            </Card>
          )}

          <h3>Timeline</h3>
          <ul className="ct-plain-list">
            {selected.timeline.map((e) => (
              <li
                key={e.id}
                style={{
                  padding: "var(--sp-2) 0",
                  borderBottom: "1px solid var(--border)",
                }}
              >
                <div className="ct-row ct-between">
                  <b>{e.type.replaceAll("_", " ")}</b>
                  <span className="ct-muted ct-text-sm">
                    {when(e.at)} · {e.actor}
                  </span>
                </div>
                <div className="ct-muted ct-text-sm">
                  {e.details} ·{" "}
                  <EvidenceState
                    delivery={e.evidence}
                    sealed={e.sealed}
                    blockIndex={e.blockIndex}
                  />
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </Page>
  );
}
