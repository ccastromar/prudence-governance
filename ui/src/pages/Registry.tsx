import { useEffect, useState } from 'react';
import { api, type AiSystem, type RegisterInput, type RegulatoryRole, type RiskTier } from '../api';
import { RiskBadge, StatusBadge } from '../components';

const EMPTY: RegisterInput = { name: '', intendedPurpose: '', ownerName: '', ownerEmail: '', riskTier: 'HIGH', regulatoryRole: 'DEPLOYER', systemVersion: '', modelHash: '', annexI: false, annexIII: true, operatingGeography: 'European Union', affectedPopulation: '', classificationJustification: '', obligations: [''] };
const TIERS: RiskTier[] = ['PROHIBITED', 'HIGH', 'LIMITED', 'MINIMAL'];
const ROLES: RegulatoryRole[] = ['PROVIDER', 'DEPLOYER', 'IMPORTER', 'DISTRIBUTOR'];

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
    try { await api.registerSystem({ ...form, obligations: form.obligations.filter(Boolean) }); setForm(EMPTY); await load(); }
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
            <div className="field"><label>EU AI Act role</label>
              <select value={form.regulatoryRole} onChange={e => setForm({ ...form, regulatoryRole: e.target.value as RegulatoryRole })}>
                {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
          </div>
          <div className="field"><label>Intended purpose</label><input value={form.intendedPurpose} onChange={e => setForm({ ...form, intendedPurpose: e.target.value })} required /></div>
          <div className="grid2">
            <div className="field"><label>Owner name</label><input value={form.ownerName} onChange={e => setForm({ ...form, ownerName: e.target.value })} required /></div>
            <div className="field"><label>Owner email</label><input type="email" value={form.ownerEmail} onChange={e => setForm({ ...form, ownerEmail: e.target.value })} /></div>
          </div>
          <div className="grid2">
            <div className="field"><label>System version</label><input value={form.systemVersion} onChange={e => setForm({ ...form, systemVersion: e.target.value })} required placeholder="e.g. 2026.10" /></div>
            <div className="field"><label>Model / build SHA-256</label><input value={form.modelHash} onChange={e => setForm({ ...form, modelHash: e.target.value })} required /></div>
          </div>
          <div className="grid2">
            <div className="field"><label>Operating geography</label><input value={form.operatingGeography} onChange={e => setForm({ ...form, operatingGeography: e.target.value })} required /></div>
            <div className="field"><label>Affected population</label><input value={form.affectedPopulation} onChange={e => setForm({ ...form, affectedPopulation: e.target.value })} required placeholder="e.g. retail credit applicants" /></div>
          </div>
          <div className="grid2">
            <label className="field"><input type="checkbox" checked={form.annexI} onChange={e => setForm({ ...form, annexI: e.target.checked })} /> Annex I regulated product</label>
            <label className="field"><input type="checkbox" checked={form.annexIII} onChange={e => setForm({ ...form, annexIII: e.target.checked })} /> Annex III use case</label>
          </div>
          <div className="field"><label>Classification justification</label><textarea value={form.classificationJustification} onChange={e => setForm({ ...form, classificationJustification: e.target.value })} required /></div>
          <div className="field"><label>Applicable obligations (one per line)</label><textarea value={form.obligations.join('\n')} onChange={e => setForm({ ...form, obligations: e.target.value.split('\n') })} required placeholder="Article 9 risk management\nArticle 10 data governance" /></div>
          <div className="row between">
            {error ? <span className="err">{error}</span> : <span className="mut" style={{ fontSize: 'var(--fs-sm)' }}>Registration creates an assessment dossier; submit it for review before it can be approved.</span>}
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Registering…' : 'Register system'}</button>
          </div>
        </form>
      </div>

      <table>
        <thead><tr><th>AI system</th><th>Classification</th><th>Lifecycle</th><th>Owner</th></tr></thead>
        <tbody>
          {systems.length === 0 && <tr><td colSpan={4} className="mut">No systems registered yet.</td></tr>}
          {systems.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.regulatoryRole} · v{s.systemVersion}</div></td>
              <td><StatusBadge status={s.approvalStatus} /></td>
              <td className="mut">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
