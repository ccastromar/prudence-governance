import { useEffect, useState } from 'react';
import Registry from './pages/Registry';
import Decisions from './pages/Decisions';
import Approvals from './pages/Approvals';
import Posture from './pages/Posture';
import Attestations from './pages/Attestations';
import Incidents from './pages/Incidents';
import GovernanceCases from './pages/GovernanceCases';
import Impact from './pages/Impact';
import { APPS, CURRENT } from './chestertown';
import { ssoEnabled, getToken, login, completeLoginIfPresent, clearToken } from './auth';

type Tab = 'registry' | 'approvals' | 'decisions' | 'posture' | 'incidents' | 'cases' | 'impact' | 'attestations';

function Switcher() {
  return (
    <nav className="switcher">
      {APPS.map((app) => (
        <a key={app.key} href={app.url} className={app.vertex === CURRENT ? 'here' : ''} title={`${app.label} — ${app.vertex}`}>
          {app.label}
        </a>
      ))}
    </nav>
  );
}

export default function App() {
  const [tab, setTab] = useState<Tab>('registry');
  const [ready, setReady] = useState(false);
  const [authed, setAuthed] = useState(!ssoEnabled || !!getToken());

  useEffect(() => {
    (async () => {
      if (await completeLoginIfPresent()) setAuthed(true);
      setReady(true);
    })();
  }, []);

  if (!ready) return null;

  const topbar = (
    <header className="topbar">
      <div className="brand">
        <span className="mark">P</span>
        <span>Prudence</span>
        <span className="vertex">· govern</span>
      </div>
      <Switcher />
      {ssoEnabled && authed && (
        <button className="btn btn-outline" style={{ marginLeft: 'var(--sp-3)' }} onClick={() => { clearToken(); setAuthed(false); }}>Sign out</button>
      )}
    </header>
  );

  if (ssoEnabled && !authed) {
    return (
      <div className="app">
        {topbar}
        <main>
          <div className="card" style={{ maxWidth: 420, margin: 'var(--sp-12) auto', textAlign: 'center' }}>
            <h1>Sign in to Prudence</h1>
            <p className="sub">Governance is a controlled function — sign in with your identity provider.</p>
            <button className="btn btn-primary" onClick={login}>Sign in with SSO</button>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="app">
      {topbar}
      <nav className="tabs">
        <button className={tab === 'registry' ? 'active' : ''} onClick={() => setTab('registry')}>AI system registry</button>
        <button className={tab === 'approvals' ? 'active' : ''} onClick={() => setTab('approvals')}>Approvals</button>
        <button className={tab === 'decisions' ? 'active' : ''} onClick={() => setTab('decisions')}>Policy decisions</button>
        <button className={tab === 'posture' ? 'active' : ''} onClick={() => setTab('posture')}>Posture</button>
        <button className={tab === 'incidents' ? 'active' : ''} onClick={() => setTab('incidents')}>Incidents</button>
        <button className={tab === 'cases' ? 'active' : ''} onClick={() => setTab('cases')}>Governance cases</button>
        <button className={tab === 'impact' ? 'active' : ''} onClick={() => setTab('impact')}>Impact & transparency</button>
        <button className={tab === 'attestations' ? 'active' : ''} onClick={() => setTab('attestations')}>Attestations</button>
      </nav>
      <main>
        {tab === 'registry' && <Registry />}
        {tab === 'approvals' && <Approvals />}
        {tab === 'decisions' && <Decisions />}
        {tab === 'posture' && <Posture />}
        {tab === 'incidents' && <Incidents />}
        {tab === 'cases' && <GovernanceCases />}
        {tab === 'impact' && <Impact />}
        {tab === 'attestations' && <Attestations />}
      </main>
    </div>
  );
}
