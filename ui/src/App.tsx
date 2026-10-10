import { useState } from 'react';
import Registry from './pages/Registry';
import Decisions from './pages/Decisions';

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
          <a href="#" title="AOS — operate">AOS</a>
          <a href="#" className="here" title="Prudence — govern">Prudence</a>
          <a href="#" title="Ernest — assure">Ernest</a>
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
