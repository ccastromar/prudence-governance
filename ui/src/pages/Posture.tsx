import { useEffect, useState } from 'react';
import { api, type Posture as PostureData, type Drift, type DeadLetter } from '../api';
import { RiskBadge, StatusBadge, PostureBadge } from '../components';

/// Oversight dashboard: the step that closes the control loop. Prudence reads Ernest's evidence
/// back and colours each registered system — GREEN only when it is both approved to run and
/// backed by an intact, reachable evidence record — and flags policy drift (ungoverned runs).
export default function Posture() {
  const [data, setData] = useState<PostureData | null>(null);
  const [drift, setDrift] = useState<Drift | null>(null);
  const [error, setError] = useState('');
  const [dead, setDead] = useState<DeadLetter[]>([]);
  const [busy, setBusy] = useState('');

  const load = () => {
    api.posture().then(setData).catch(() => setError('Could not load posture (is the backend on :8099?)'));
    api.drift().then(setDrift).catch(() => { /* drift is best-effort */ });
    api.deadLetters().then(setDead).catch(() => { /* best-effort */ });
  };

  const retry = async (d: DeadLetter) => {
    setBusy(d.id); setError('');
    try { await api.retryDeadLetter(d.target, d.id); load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(''); }
  };
  useEffect(() => { load(); }, []);

  return (
    <>
      <h1>Posture</h1>
      <p className="sub">The compliance posture of the AI estate — risk tier, approval, and whether the evidence record backs it up.</p>

      {error && <div className="card"><span className="err">{error}</span></div>}

      {data && (
        <>
          <div className="card" style={{ marginBottom: 'var(--sp-6)' }}>
            <div className="row between">
              <div>
                <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>Estate roll-up</div>
                <div style={{ fontSize: 'var(--fs-lg)', fontWeight: 700 }}><PostureBadge status={data.overall} /></div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>Evidence plane (Ernest)</div>
                <div style={{ color: data.evidenceHealthy ? 'var(--ok)' : 'var(--danger)', fontWeight: 600 }}>
                  {data.evidenceHealthy ? '● healthy' : '● unavailable'}
                </div>
                <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{data.evidenceDetail}</div>
              </div>
            </div>
          </div>

          <table>
            <thead><tr><th>AI system</th><th>Risk tier</th><th>Approval</th><th>Posture</th></tr></thead>
            <tbody>
              {data.systems.length === 0 && <tr><td colSpan={4} className="mut">No systems registered yet.</td></tr>}
              {data.systems.map(s => (
                <tr key={s.id}>
                  <td><div style={{ fontWeight: 600 }}>{s.name}</div><code>{s.id}</code></td>
                  <td><RiskBadge tier={s.riskTier} /></td>
                  <td><StatusBadge status={s.approvalStatus} /></td>
                  <td><PostureBadge status={s.status} /><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{s.detail}</div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {drift && !drift.complete && (
        <div className="card" style={{ marginTop: 'var(--sp-6)', borderColor: 'var(--danger)' }}>
          <span className="err">● Policy-drift scan incomplete — results below cannot be trusted.</span>
          <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{drift.scanDetail}</div>
        </div>
      )}

      {drift && drift.findings.length > 0 && (
        <div className="card" style={{ marginTop: 'var(--sp-6)', borderColor: drift.hasViolations ? 'var(--danger)' : 'var(--border)' }}>
          <div className="row between">
            <h2 style={{ marginTop: 0, fontSize: 'var(--fs-lg)' }}>Policy drift</h2>
            <span style={{ fontWeight: 700, color: drift.hasViolations ? 'var(--danger)' : 'var(--ok)' }}>
              {drift.hasViolations ? '● violations detected' : '● clean'}
            </span>
          </div>
          <p className="mut" style={{ fontSize: 'var(--fs-sm)', marginTop: 0 }}>Executions in the evidence plane checked against the governance record.</p>
          <table>
            <thead><tr><th>AI system</th><th>Approval</th><th>Runs</th><th>Finding</th></tr></thead>
            <tbody>
              {drift.findings.map(f => (
                <tr key={f.aiSystemId}>
                  <td><div style={{ fontWeight: 600 }}>{f.name}</div><code>{f.aiSystemId}</code></td>
                  <td><StatusBadge status={f.approvalStatus} /></td>
                  <td className="mut">{f.executionCount}</td>
                  <td>
                    <span style={{ fontWeight: 700, color: f.severity === 'VIOLATION' ? 'var(--danger)' : 'var(--ok)' }}>● {f.severity}</span>
                    <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{f.detail}</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {dead.length > 0 && (
        <div className="card" style={{ marginTop: 'var(--sp-6)', borderColor: 'var(--danger)' }}>
          <h2 style={{ marginTop: 0, fontSize: 'var(--fs-lg)' }}>Undelivered records ({dead.length})</h2>
          <p className="mut" style={{ fontSize: 'var(--fs-sm)', marginTop: 0 }}>Evidence that never reached Ernest, or alerts that were never sent. Fix the cause (Ernest reachable, credentials, payload), then retry: the record is requeued and delivered at once.</p>
          <table>
            <thead><tr><th>Kind</th><th>Record</th><th>Last error</th><th style={{ textAlign: 'right' }}>Action</th></tr></thead>
            <tbody>
              {dead.map(d => (
                <tr key={d.target + d.id}>
                  <td>{d.target}{d.aiSystemId && <div className="mut" style={{ fontSize: 'var(--fs-sm)' }}><code>{d.aiSystemId}</code></div>}</td>
                  <td><code>{d.id}</code><div className="mut" style={{ fontSize: 'var(--fs-sm)' }}>{d.attempts} attempt(s) · gave up {d.deadAt.replace('T', ' ').replace(/\.\d+Z$/, '')}</div></td>
                  <td className="mut" style={{ fontSize: 'var(--fs-sm)', maxWidth: 360, overflowWrap: 'anywhere' }}>{d.lastError}</td>
                  <td style={{ textAlign: 'right' }}><button className="btn btn-primary" disabled={busy === d.id} onClick={() => retry(d)}>{busy === d.id ? '…' : 'Retry'}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
