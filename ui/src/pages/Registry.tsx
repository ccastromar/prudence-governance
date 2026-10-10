import { useEffect, useState } from 'react';
import { api, type AiSystem, type RegisterInput, type RiskTier } from '../api';
import { RiskBadge, StatusBadge } from '../components';

const EMPTY: RegisterInput = { name: '', intendedPurpose: '', ownerName: '', ownerEmail: '', riskTier: 'HIGH' };
const TIERS: RiskTier[] = ['PROHIBITED', 'HIGH', 'LIMITED', 'MINIMAL'];

export default function Registry() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [form, setForm] = useState<RegisterInput>(EMPTY);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = () => api.listSystems().then(setSystems).catch(() => setError('Could not load systems (is the backend on :8099?)'));
  useEffect(() => { load(); }, []);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError('');
    try { await api.registerSystem(form); setForm(EMPTY); await load(); }
    catch { setError('Registration failed'); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h1>AI system registry</h1>
      <p className="sub">Every AI use case, its EU AI Act risk tier, owner and approval state — the governance spine.</p>

      <div className="card" style={{ marginBottom: 'var(--sp-6)' }}>
        <form onSubmit={submit}>
          <div className="grid2">
            <div className="field"><label>Name</label><input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required /></div>
            <div className="field"><label>Risk tier</label>
              <select value={form.riskTier} onChange={e => setForm({ ...form, riskTier: e.target.value as RiskTier })}>
                {TIERS.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
          </div>
          <div className="field"><label>Intended purpose</label><input value={form.intendedPurpose} onChange={e => setForm({ ...form, intendedPurpose: e.target.value })} required /></div>
          <div className="grid2">
            <div className="field"><label>Owner name</label><input value={form.ownerName} onChange={e => setForm({ ...form, ownerName: e.target.value })} required /></div>
            <div className="field"><label>Owner email</label><input type="email" value={form.ownerEmail} onChange={e => setForm({ ...form, ownerEmail: e.target.value })} /></div>
          </div>
          <div className="row between">
            {error ? <span className="err">{error}</span> : <span className="mut" style={{ fontSize: 'var(--fs-sm)' }}>High-risk & prohibited tiers require governance approval.</span>}
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Registering…' : 'Register system'}</button>
          </div>
        </form>
      </div>

      <table>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Approval</th><th>Owner</th></tr></thead>
        <tbody>
          {systems.length === 0 && <tr><td colSpan={4} className="mut">No systems registered yet.</td></tr>}
          {systems.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td><StatusBadge status={s.approvalStatus} /></td>
              <td className="mut">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
