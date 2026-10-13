import { useEffect, useState } from 'react';
import { api, type AiSystem, type ReviewEvent } from '../api';
import { EvidenceState, RiskBadge, StatusBadge } from '../components';

const actionColor: Record<string, string> = {
  APPROVE: 'var(--ok)', FIRST_APPROVAL: 'var(--ok)', RENEW: 'var(--ok)', FIRST_RENEWAL_APPROVAL: 'var(--ok)',
  REJECT: 'var(--danger)', EXPIRE: 'var(--danger)', REVIEW_DUE: 'var(--warn)',
};
const when = (iso: string) => iso.replace('T', ' ').replace(/\.\d+Z?$/, '').replace('Z', '');

/// The reviewer's inbox: every system awaiting a governance decision. Approving one here flips
/// the PDP verdict to ALLOW, so the AOS gate lets its pipeline run — the human link in the loop.
export default function Approvals() {
  const [systems, setSystems] = useState<AiSystem[]>([]);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState('');
  const [rejecting, setRejecting] = useState<AiSystem | null>(null);
  const [reason, setReason] = useState('');
  const [trailFor, setTrailFor] = useState<AiSystem | null>(null);
  const [trail, setTrail] = useState<ReviewEvent[]>([]);

  const load = () =>
    api.listSystems().then(setSystems).catch(() => setError('Could not load systems (is the backend on :8099?)'));
  useEffect(() => { load(); }, []);

  const openTrail = async (s: AiSystem) => {
    setTrailFor(s); setTrail([]);
    try { setTrail(await api.systemReviews(s.id)); }
    catch { setError('Could not load the audit trail'); }
  };

  const pending = systems.filter(s => s.approvalStatus === 'PENDING_APPROVAL' || s.approvalStatus === 'PENDING_SECOND_APPROVAL');
  const assessed = systems.filter(s => s.approvalStatus === 'UNDER_ASSESSMENT' || s.approvalStatus === 'REJECTED'
    || s.approvalStatus === 'EXPIRED');
  const renewalDue = systems.filter(s => s.approvalStatus === 'APPROVED' && s.reviewDue);
  const recent = systems.filter(s => s.approvalStatus === 'APPROVED' || s.approvalStatus === 'REJECTED'
    || s.approvalStatus === 'EXPIRED');

  const approve = async (s: AiSystem) => {
    setBusyId(s.id); setError('');
    try { await api.approveSystem(s.id); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusyId(''); }
  };

  const renew = async (s: AiSystem) => {
    setBusyId(s.id); setError('');
    try { await api.renewSystem(s.id); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusyId(''); }
  };

  const submit = async (s: AiSystem) => {
    setBusyId(s.id); setError('');
    try { await api.submitSystem(s.id); await load(); }
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

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Ready to submit — assessed, rejected or expired ({assessed.length})</h2>
      <table style={{ marginBottom: 'var(--sp-8)' }}>
        <thead><tr><th>AI system</th><th>Classification</th><th>Evidence</th><th style={{ textAlign: 'right' }}>Action</th></tr></thead>
        <tbody>
          {assessed.length === 0 && <tr><td colSpan={4} className="mut">Nothing awaiting (re)submission.</td></tr>}
          {assessed.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.regulatoryRole} · v{s.systemVersion}</div></td>
              <td className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.obligations.length} obligations · {s.annexI ? 'Annex I' : 'No Annex I'}</td>
              <td style={{ textAlign: 'right' }}><button className="btn btn-primary" disabled={busyId === s.id} onClick={() => submit(s)}>{busyId === s.id ? '…' : 'Submit for review'}</button></td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Awaiting decision ({pending.length})</h2>
      <table style={{ marginBottom: 'var(--sp-8)' }}>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Owner</th><th style={{ textAlign: 'right' }}>Decision</th></tr></thead>
        <tbody>
          {pending.length === 0 && <tr><td colSpan={4} className="mut">Nothing awaiting approval.</td></tr>}
          {pending.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.intendedPurpose}</div></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td className="mut">{s.ownerName}{s.firstApprover && <div style={{ fontSize: 'var(--fs-sm)' }}>First sign-off: {s.firstApprover}</div>}</td>
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

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Renewal due ({renewalDue.length})</h2>
      <p className="mut" style={{ fontSize: 'var(--fs-sm)', marginTop: 0 }}>Periodic review: renew before expiry and the system keeps running. High-risk renewals need two independent sign-offs.</p>
      <table style={{ marginBottom: 'var(--sp-8)' }}>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Expires</th><th style={{ textAlign: 'right' }}>Review</th></tr></thead>
        <tbody>
          {renewalDue.length === 0 && <tr><td colSpan={4} className="mut">No approvals inside their review window.</td></tr>}
          {renewalDue.map(s => (
            <tr key={s.id}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td className="mut">{s.approvalExpiresAt && when(s.approvalExpiresAt)}{s.renewalFirstApprover && <div style={{ fontSize: 'var(--fs-sm)' }}>First renewal sign-off: {s.renewalFirstApprover}</div>}</td>
              <td style={{ textAlign: 'right' }}><button className="btn btn-primary" disabled={busyId === s.id} onClick={() => renew(s)}>{busyId === s.id ? '…' : 'Renew'}</button></td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2 style={{ fontSize: 'var(--fs-lg)' }}>Recently decided</h2>
      <p className="mut" style={{ fontSize: 'var(--fs-sm)', marginTop: 0 }}>Click a system to see its sealed audit trail.</p>
      <table>
        <thead><tr><th>AI system</th><th>Risk tier</th><th>Outcome</th><th>Owner</th></tr></thead>
        <tbody>
          {recent.length === 0 && <tr><td colSpan={4} className="mut">No decisions yet.</td></tr>}
          {recent.map(s => (
            <tr key={s.id} onClick={() => openTrail(s)} style={{ cursor: 'pointer' }}
                className={trailFor?.id === s.id ? 'active-row' : ''}>
              <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
              <td><RiskBadge tier={s.riskTier} annexIII={s.annexIII} /></td>
              <td><StatusBadge status={s.approvalStatus} />{s.approvalExpiresAt && <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>Expires {when(s.approvalExpiresAt)}</div>}</td>
              <td className="mut">{s.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {trailFor && (
        <div className="card" style={{ marginTop: 'var(--sp-6)' }}>
          <div className="row between">
            <h2 style={{ marginTop: 0, fontSize: 'var(--fs-lg)' }}>Audit trail — {trailFor.name}</h2>
            <button className="btn btn-outline" style={{ fontSize: 'var(--fs-sm)', padding: '4px 10px' }} onClick={() => setTrailFor(null)}>Close</button>
          </div>
          {trail.length === 0
            ? <p className="mut">No review events recorded for this system.</p>
            : (
              <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                {trail.map(e => (
                  <li key={e.id} style={{ padding: 'var(--sp-3) 0', borderBottom: '1px solid var(--border)' }}>
                    <div className="row between">
                      <span style={{ fontWeight: 700, color: actionColor[e.action] }}>● {e.action}</span>
                      <span className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{when(e.at)}</span>
                    </div>
                    <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>
                      {e.previousStatus} → {e.newStatus} · by <b>{e.reviewer}</b>
                      {' · '}<EvidenceState delivery={e.evidence} sealed={e.sealed} blockIndex={e.blockIndex} />
                    </div>
                    {e.reason && <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>“{e.reason}”</div>}
                  </li>
                ))}
              </ul>
            )}
        </div>
      )}

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
