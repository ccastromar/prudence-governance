import { useEffect, useState } from 'react';
import { api, type Attestation, type AttestationSummary } from '../api';
import { RiskBadge, StatusBadge, PostureBadge } from '../components';

/// Attestations: generate a tamper-evident report of the estate and see it sealed in Ernest.
/// The report's content hash is what gets qualify-sealed — the report proves itself.
export default function Attestations() {
  const [list, setList] = useState<AttestationSummary[]>([]);
  const [selected, setSelected] = useState<Attestation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = () => api.listAttestations().then(setList).catch(() => setError('Could not load attestations (is the backend on :8099?)'));
  useEffect(() => { load(); }, []);

  const generate = async () => {
    setBusy(true); setError('');
    try { const a = await api.generateAttestation(); setSelected(a); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const open = async (id: string) => {
    setError('');
    try { setSelected(await api.getAttestation(id)); }
    catch { setError('Could not load that attestation'); }
  };

  const shortHash = (h: string) => `${h.slice(0, 12)}…`;
  const when = (iso: string) => iso.replace('T', ' ').replace(/\.\d+Z?$/, '').replace('Z', '');

  return (
    <>
      <div className="row between" style={{ alignItems: 'flex-end' }}>
        <div>
          <h1>Attestations</h1>
          <p className="sub">A point-in-time report of the estate, hashed and sealed in Ernest — so the report itself is tamper-evident.</p>
        </div>
        <button className="btn btn-primary" onClick={generate} disabled={busy}>
          {busy ? 'Generating…' : 'Generate attestation'}
        </button>
      </div>

      {error && <div className="card" style={{ marginBottom: 'var(--sp-6)' }}><span className="err">{error}</span></div>}

      <table style={{ marginBottom: 'var(--sp-8)' }}>
        <thead><tr><th>Issued</th><th>Estate</th><th>Content hash</th><th>Evidence</th></tr></thead>
        <tbody>
          {list.length === 0 && <tr><td colSpan={4} className="mut">No attestations issued yet.</td></tr>}
          {list.map(a => (
            <tr key={a.id} onClick={() => open(a.id)} style={{ cursor: 'pointer' }}
                className={selected?.id === a.id ? 'active-row' : ''}>
              <td>{when(a.generatedAt)}</td>
              <td><PostureBadge status={a.overallPosture} /></td>
              <td><code>{shortHash(a.contentHash)}</code></td>
              <td>{a.sealed
                ? <span style={{ color: 'var(--ok)' }}>● sealed · Ernest block {a.blockIndex}</span>
                : <span style={{ color: 'var(--warn)' }}>● unsealed</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected && (
        <div className="card">
          <div className="row between">
            <h2 style={{ marginTop: 0 }}>Attestation {selected.id.slice(0, 8)}</h2>
            <PostureBadge status={selected.overallPosture} />
          </div>
          <div className="mut" style={{ fontSize: 'var(--fs-sm)', marginBottom: 'var(--sp-4)' }}>
            <div>Issued: <b>{when(selected.generatedAt)}</b></div>
            <div>Content hash: <code>{selected.contentHash}</code></div>
            <div>Evidence: {selected.evidence.sealed
              ? <span style={{ color: 'var(--ok)' }}>{selected.evidence.detail} (model <code>{selected.evidence.modelId}</code>)</span>
              : <span style={{ color: 'var(--warn)' }}>{selected.evidence.detail}</span>}</div>
          </div>

          <table>
            <thead><tr><th>AI system</th><th>Risk tier</th><th>Approval</th><th>Posture</th></tr></thead>
            <tbody>
              {selected.systems.length === 0 && <tr><td colSpan={4} className="mut">No systems in this attestation.</td></tr>}
              {selected.systems.map(s => (
                <tr key={s.aiSystemId}>
                  <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.aiSystemId}</code></td>
                  <td><RiskBadge tier={s.riskTier} /></td>
                  <td><StatusBadge status={s.approvalStatus} /></td>
                  <td><PostureBadge status={s.posture} /><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.detail}</div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
