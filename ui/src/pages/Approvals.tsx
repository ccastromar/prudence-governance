import { useEffect, useState } from 'react';
import { api, type AiSystem } from '../api';
import { RiskBadge, StatusBadge } from '../components';

/// The reviewer's inbox: every system awaiting a governance decision. Approving one here flips
/// the PDP verdict to ALLOW, so the AOS gate lets its pipeline run — the human link in the loop.
export default function Approvals() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState('');
  const [rejecting, setRejecting] = useState<AiSystem | null>(null);
  const [reason, setReason] = useState('');

  const load = () =>
    api.listSystems().then(setSystems).catch(() => setError('Could not load systems (is the backend on :8099?)'));
  useEffect(() => { load(); }, []);

  const pending = systems.filter(s => s.approvalStatus === 'PENDING_APPROVAL');
  const recent = systems.filter(s => s.approvalStatus === 'APPROVED' || s.approvalStatus === 'REJECTED');

  const approve = async (s: AiSystem) => {
    setBusyId(s.id); setError('');
    try { await api.approveSystem(s.id); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusyId(''); }
  };

  const confirmReject = async () => {
    if (!rejecting) return;
    setBusyId(rejecting.id); setError('');
    try { await api.rejectSystem(rejecting.id, reason); setRejecting(null); setReason(''); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusyId(''); }
  };

  return (
    <>
      <h1>Approvals</h1>
      <p className="sub">High-risk and prohibited systems may not go live without an explicit governance decision. Review the queue below.</p>

      {error && <div className="card" style={{ marginBottom: 'var(--sp-6)' }}><span className="err">{error}</span></div>}

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Awaiting decision ({pending.length})</h2>
      <table style={{ marginBottom: 'var(--sp-8)' }}>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Owner</th><th style={{ textAlign: 'right' }}>Decision</th></tr></thead>
        <tbody>
          {pending.length === 0 && <tr><td colSpan={4} className="mut">Nothing awaiting approval.</td></tr>}
          {pending.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.intendedPurpose}</div></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td className="mut">{s.ownerName}</td>
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                <button className="btn btn-primary" disabled={busyId === s.id} onClick={() => approve(s)} style={{ marginRight: 'var(--sp-2)' }}>
                  {busyId === s.id ? '…' : 'Approve'}
                </button>
                <button className="btn btn-outline" disabled={busyId === s.id} onClick={() => { setRejecting(s); setReason(''); }}>Reject</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Recently decided</h2>
      <table>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Outcome</th><th>Owner</th></tr></thead>
        <tbody>
          {recent.length === 0 && <tr><td colSpan={4} className="mut">No decisions yet.</td></tr>}
          {recent.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td><StatusBadge status={s.approvalStatus} /></td>
              <td className="mut">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {rejecting && (
        <div className="card" style={{ position: 'fixed', inset: 0, margin: 'auto', maxWidth: 440, height: 'fit-content', boxShadow: 'var(--shadow-lg, 0 12px 40px rgba(0,0,0,.3))' }}>
          <h2 style={{ marginTop: 0 }}>Reject “{rejecting.name}”?</h2>
          <p className="sub">The system stays off-limits to the PDP until it is re-submitted or reclassified.</p>
          <div className="field"><label>Reason</label>
            <input value={reason} onChange={e => setReason(e.target.value)} placeholder="why this is being declined" autoFocus />
          </div>
          <div className="row between">
            <button className="btn btn-outline" onClick={() => setRejecting(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busyId === rejecting.id} onClick={confirmReject}>
              {busyId === rejecting.id ? 'Rejecting…' : 'Confirm reject'}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
