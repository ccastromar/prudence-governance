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
      {error && <Alert tone="danger">{error}</Alert>}
      <Field label="AI system" htmlFor="impact-ai-system">
        <Select
          id="impact-ai-system"
          value={selected}
          onChange={(e) => choose(e.target.value)}
        >
          <option value="">Select…</option>
          {systems.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </Select>
      </Field>
      {selected && (
        <>
          <Card style={{ marginBottom: "var(--sp-5)" }}>
            <h2>Applicability</h2>
            <Checkbox
              label="FRIA required"
              checked={req.friaRequired}
              onChange={(e) =>
                setReq({ ...req, friaRequired: e.target.checked })
              }
            />
            <Checkbox
              label="Transparency evidence required"
              checked={req.transparencyRequired}
              onChange={(e) =>
                setReq({ ...req, transparencyRequired: e.target.checked })
              }
            />
            <Button variant="primary" onClick={save}>
              Save requirements
            </Button>
          </Card>
          <div className="grid2">
            <Card>
              <h2>FRIA / DPIA</h2>
              {frias.map((f) => (
                <p key={f.id}>
                  <b>{f.jurisdiction}</b> · {f.templateVersion} ·{" "}
                  <a href={f.dpiaUri}>DPIA</a>
                </p>
              ))}
              <Input
                placeholder="Affected groups"
                value={fria.affectedGroups}
                onChange={(e) =>
                  setFria({ ...fria, affectedGroups: e.target.value })
                }
              />
              <Input
                placeholder="Assumptions"
                value={fria.assumptions}
                onChange={(e) =>
                  setFria({ ...fria, assumptions: e.target.value })
                }
              />
              <Input
                placeholder="Mitigations"
                value={fria.mitigations}
                onChange={(e) =>
                  setFria({ ...fria, mitigations: e.target.value })
                }
              />
              <Input
                placeholder="DPIA URI"
                value={fria.dpiaUri}
                onChange={(e) => setFria({ ...fria, dpiaUri: e.target.value })}
              />
              <Input
                placeholder="DPIA SHA-256"
                value={fria.dpiaSha256}
                onChange={(e) =>
                  setFria({ ...fria, dpiaSha256: e.target.value })
                }
              />
              <Input
                placeholder="Signed by"
                value={fria.signedBy}
                onChange={(e) => setFria({ ...fria, signedBy: e.target.value })}
              />
              <Button
                variant="primary"
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
              </Button>
            </Card>
            <Card>
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
            </Card>
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
      <Select
        value={kind}
        onChange={(e) => setKind(e.target.value as TransparencyKind)}
      >
        {kinds.map((k) => (
          <option key={k}>{k}</option>
        ))}
      </Select>
      <Input
        placeholder="Audience"
        value={audience}
        onChange={(e) => setAudience(e.target.value)}
      />
      <Input
        placeholder="Deployment evidence URI"
        value={evidence}
        onChange={(e) => setEvidence(e.target.value)}
      />
      <Input
        placeholder="Deployed by"
        value={by}
        onChange={(e) => setBy(e.target.value)}
      />
      <Button
        variant="primary"
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
      </Button>
    </>
  );
}
