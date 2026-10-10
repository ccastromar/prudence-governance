import { useState } from 'react';
import { api, type Decision } from '../api';
import { DecisionBadge } from '../components';

export default function Decisions() {
  const [req, setReq] = useState({ subject: 'alice@bank.eu', pipeline: 'credit-risk-scoring', aiSystemId: '', action: 'run' });
  const [decision, setDecision] = useState<Decision | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const set = (k: keyof typeof req) => (e: React.ChangeEvent<HTMLInputElement>) => setReq({ ...req, [k]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(''); setDecision(null);
    try { setDecision(await api.decide(req)); }
    catch { setError('PDP call failed (is the backend on :8099?)'); }
    finally { setBusy(false); }
  };

  return (
    <>
      <h1>Policy decisions</h1>
      <p className="sub">The PDP an AOS gate consults. Ask whether a subject may run a pipeline against a registered AI system.</p>

      <div className="card" style={{ marginBottom: 'var(--sp-6)' }}>
        <form onSubmit={submit}>
          <div className="grid2">
            <div className="field"><label>Subject</label><input value={req.subject} onChange={set('subject')} /></div>
            <div className="field"><label>Pipeline</label><input value={req.pipeline} onChange={set('pipeline')} /></div>
          </div>
          <div className="grid2">
            <div className="field"><label>AI system id</label><input value={req.aiSystemId} onChange={set('aiSystemId')} placeholder="paste an id from the registry" /></div>
            <div className="field"><label>Action</label><input value={req.action} onChange={set('action')} /></div>
          </div>
          <div className="row between">
            {error ? <span className="err">{error}</span> : <span className="mut" style={{ fontSize: 'var(--fs-sm)' }}>Governance facts first (registered/approved/risk), then the policy engine.</span>}
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Deciding…' : 'Evaluate'}</button>
          </div>
        </form>
      </div>

      {decision && (
        <div className="card">
          <div className="row between">
            <DecisionBadge decision={decision.decision} />
            {decision.approver && <span className="mut">approver: <b>{decision.approver}</b></span>}
          </div>
          {decision.reason && <p className="mut" style={{ marginBottom: 0 }}>{decision.reason}</p>}
        </div>
      )}
    </>
  );
}
