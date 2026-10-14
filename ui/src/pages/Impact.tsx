import { useEffect, useState } from "react";
import {
  api,
  type AiSystem,
  type Fria,
  type ImpactRequirement,
  type TransparencyKind,
  type TransparencyNotice,
} from "../api";

const kinds: TransparencyKind[] = [
  "DIRECT_AI_INTERACTION",
  "DEEPFAKE",
  "EMOTION_RECOGNITION",
  "BIOMETRIC_CATEGORISATION",
  "PUBLIC_INTEREST_CONTENT",
];
const blankFria = (id: string): Omit<Fria, "id" | "signedAt"> => ({
  aiSystemId: id,
  jurisdiction: "EU",
  templateVersion: "v1",
  affectedGroups: "",
  assumptions: "",
  mitigations: "",
  dpiaUri: "",
  dpiaSha256: "",
  signedBy: "",
});
export default function Impact() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [selected, setSelected] = useState("");
  const [req, setReq] = useState<ImpactRequirement>({
    friaRequired: false,
    transparencyRequired: false,
  });
  const [frias, setFrias] = useState<Fria[]>([]);
  const [notices, setNotices] = useState<TransparencyNotice[]>([]);
  const [fria, setFria] = useState<Omit<Fria, "id" | "signedAt">>(
    blankFria(""),
  );
  const [error, setError] = useState("");
  const load = async (id = selected) => {
    try {
      const s = await api.listSystems();
      setSystems(s);
      if (id) {
        const [r, f, n] = await Promise.all([
          api.impactRequirements(id),
          api.frias(id),
          api.transparencyNotices(id),
        ]);
        setReq(r);
        setFrias(f);
        setNotices(n);
        setFria(blankFria(id));
      }
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const choose = (id: string) => {
    setSelected(id);
    load(id);
  };
  const save = async () => {
    try {
      await api.setImpactRequirements(selected, req);
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <>
      <h1>Impact & transparency</h1>
      <p className="sub">
        FRIA, linked DPIA and Article 50 notice deployment evidence.
        Applicability is a governed decision, not an automatic legal
        classification.
      </p>
      {error && <p className="err">{error}</p>}
      <div className="field">
        <label>AI system</label>
        <select value={selected} onChange={(e) => choose(e.target.value)}>
          <option value="">Select…</option>
          {systems.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </div>
      {selected && (
        <>
          <div className="card" style={{ marginBottom: "var(--sp-5)" }}>
            <h2>Applicability</h2>
            <label>
              <input
                type="checkbox"
                checked={req.friaRequired}
                onChange={(e) =>
                  setReq({ ...req, friaRequired: e.target.checked })
                }
              />{" "}
              FRIA required
            </label>
            <label>
              <input
                type="checkbox"
                checked={req.transparencyRequired}
                onChange={(e) =>
                  setReq({ ...req, transparencyRequired: e.target.checked })
                }
              />{" "}
              Transparency evidence required
            </label>
            <button className="btn btn-primary" onClick={save}>
              Save requirements
            </button>
          </div>
          <div className="grid2">
            <section className="card">
              <h2>FRIA / DPIA</h2>
              {frias.map((f) => (
                <p key={f.id}>
                  <b>{f.jurisdiction}</b> · {f.templateVersion} ·{" "}
                  <a href={f.dpiaUri}>DPIA</a>
                </p>
              ))}
              <input
                placeholder="Affected groups"
                value={fria.affectedGroups}
                onChange={(e) =>
                  setFria({ ...fria, affectedGroups: e.target.value })
                }
              />
              <input
                placeholder="Assumptions"
                value={fria.assumptions}
                onChange={(e) =>
                  setFria({ ...fria, assumptions: e.target.value })
                }
              />
              <input
                placeholder="Mitigations"
                value={fria.mitigations}
                onChange={(e) =>
                  setFria({ ...fria, mitigations: e.target.value })
                }
              />
              <input
                placeholder="DPIA URI"
                value={fria.dpiaUri}
                onChange={(e) => setFria({ ...fria, dpiaUri: e.target.value })}
              />
              <input
                placeholder="DPIA SHA-256"
                value={fria.dpiaSha256}
                onChange={(e) =>
                  setFria({ ...fria, dpiaSha256: e.target.value })
                }
              />
              <input
                placeholder="Signed by"
                value={fria.signedBy}
                onChange={(e) => setFria({ ...fria, signedBy: e.target.value })}
              />
              <button
                className="btn btn-primary"
                onClick={async () => {
                  try {
                    await api.createFria(fria);
                    await load();
                  } catch (e) {
                    setError((e as Error).message);
                  }
                }}
              >
                Record FRIA
              </button>
            </section>
            <section className="card">
              <h2>Transparency notices</h2>
              {notices.map((n) => (
                <p key={n.id}>
                  <b>{n.kind.replaceAll("_", " ")}</b> · {n.noticeVersion} ·{" "}
                  {n.audience}
                </p>
              ))}
              <NoticeForm
                systemId={selected}
                done={() => load()}
                fail={setError}
              />
            </section>
          </div>
        </>
      )}
    </>
  );
}
function NoticeForm({
  systemId,
  done,
  fail,
}: {
  systemId: string;
  done: () => void;
  fail: (x: string) => void;
}) {
  const [kind, setKind] = useState<TransparencyKind>("DIRECT_AI_INTERACTION");
  const [audience, setAudience] = useState("");
  const version = "v1";
  const [evidence, setEvidence] = useState("");
  const [by, setBy] = useState("");
  return (
    <>
      <select
        value={kind}
        onChange={(e) => setKind(e.target.value as TransparencyKind)}
      >
        {kinds.map((k) => (
          <option key={k}>{k}</option>
        ))}
      </select>
      <input
        placeholder="Audience"
        value={audience}
        onChange={(e) => setAudience(e.target.value)}
      />
      <input
        placeholder="Deployment evidence URI"
        value={evidence}
        onChange={(e) => setEvidence(e.target.value)}
      />
      <input
        placeholder="Deployed by"
        value={by}
        onChange={(e) => setBy(e.target.value)}
      />
      <button
        className="btn btn-primary"
        onClick={async () => {
          try {
            await api.createTransparencyNotice({
              aiSystemId: systemId,
              kind,
              audience,
              noticeVersion: version,
              deploymentEvidence: evidence,
              deployedBy: by,
            });
            done();
          } catch (e) {
            fail((e as Error).message);
          }
        }}
      >
        Record notice
      </button>
    </>
  );
}
