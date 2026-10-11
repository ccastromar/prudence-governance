import { useState } from 'react';
import Registry from './pages/Registry';
import Decisions from './pages/Decisions';
import { TRIO, CURRENT } from './trio';

type Tab = 'registry' | 'decisions';

export default function App() {
  const [tab, setTab] = useState<Tab>('registry');
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="mark">P</span>
          <span>Prudence</span>
          <span className="vertex">· govern</span>
        </div>
        {/* App switcher across the trio (one platform, three lines of defense) */}
        <nav className="switcher">
          {TRIO.map((app) => (
            <a key={app.key} href={app.url} className={app.vertex === CURRENT ? 'here' : ''} title={`${app.label} — ${app.vertex}`}>
              {app.label}
            </a>
          ))}
        </nav>
      </header>
      <nav className="tabs">
        <button className={tab === 'registry' ? 'active' : ''} onClick={() => setTab('registry')}>AI system registry</button>
        <button className={tab === 'decisions' ? 'active' : ''} onClick={() => setTab('decisions')}>Policy decisions</button>
      </nav>
      <main>{tab === 'registry' ? <Registry /> : <Decisions />}</main>
    </div>
  );
}
