import { useEffect, useState } from 'react';
import { api, type Posture as PostureData } from '../api';
import { RiskBadge, StatusBadge, PostureBadge } from '../components';

/// Oversight dashboard: the step that closes the control loop. Prudence reads Ernest's evidence
/// back and colours each registered system — GREEN only when it is both approved to run and
/// backed by an intact, reachable evidence record.
export default function Posture() {
  const [data, setData] = useState<PostureData | null>(null);
  const [error, setError] = useState('');

  const load = () => api.posture().then(setData).catch(() => setError('Could not load posture (is the backend on :8099?)'));
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
    </>
  );
}
