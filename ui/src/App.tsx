import { useEffect, useState } from "react";
import Registry from "./pages/Registry";
import Decisions from "./pages/Decisions";
import Approvals from "./pages/Approvals";
import Posture from "./pages/Posture";
import Attestations from "./pages/Attestations";
import Incidents from "./pages/Incidents";
import GovernanceCases from "./pages/GovernanceCases";
import Impact from "./pages/Impact";
import Monitoring from "./pages/Monitoring";
import Oversight from "./pages/Oversight";
import Snapshots from "./pages/Snapshots";
import Changes from "./pages/Changes";
import {
  AppShell,
  Button,
  Tabs,
  ThemeToggle,
  type TabItem,
} from "@chestertown/design";
import { APPS } from "./chestertown";
import {
  ssoEnabled,
  getToken,
  login,
  completeLoginIfPresent,
  clearToken,
} from "./auth";

type Tab =
  | "registry"
  | "approvals"
  | "decisions"
  | "posture"
  | "incidents"
  | "cases"
  | "impact"
  | "monitoring"
  | "oversight"
  | "changes"
  | "snapshots"
  | "attestations";

const TAB_ITEMS: TabItem<Tab>[] = [
  { id: "registry", label: "AI system registry" },
  { id: "approvals", label: "Approvals" },
  { id: "decisions", label: "Policy decisions" },
  { id: "posture", label: "Posture" },
  { id: "incidents", label: "Incidents" },
  { id: "cases", label: "Governance cases" },
  { id: "impact", label: "Impact & transparency" },
  { id: "monitoring", label: "Monitoring" },
  { id: "oversight", label: "Human oversight" },
  { id: "changes", label: "Changes" },
  { id: "snapshots", label: "Snapshots" },
  { id: "attestations", label: "Attestations" },
];
const TABS: readonly Tab[] = TAB_ITEMS.map((item) => item.id);

/// Deep links from the other Chestertown apps: `?tab=registry&system=<id>` opens a tab and
/// highlights one AI system. The SSO round-trip reloads the page without them, so they are
/// parked in sessionStorage until the user is back.
const DEEP_LINK_KEY = "prudence.deeplink";
function readDeepLink(): { tab?: Tab; system?: string } {
  let params = new URLSearchParams(window.location.search);
  if (!params.get("tab") && !params.get("system")) {
    try {
      params = new URLSearchParams(sessionStorage.getItem(DEEP_LINK_KEY) ?? "");
    } catch {
      /* storage unavailable: no parked link */
    }
  } else {
    try {
      sessionStorage.setItem(DEEP_LINK_KEY, window.location.search);
    } catch {
      /* best-effort */
    }
  }
  const tab = params.get("tab") as Tab | null;
  return {
    tab: tab && TABS.includes(tab) ? tab : undefined,
    system: params.get("system") ?? undefined,
  };
}

export default function App() {
  const [link] = useState(readDeepLink);
  const [tab, setTab] = useState<Tab>(link.tab ?? "registry");
  const [ready, setReady] = useState(false);
  const [authed, setAuthed] = useState(!ssoEnabled || !!getToken());
  const [authError, setAuthError] = useState("");

  useEffect(() => {
    (async () => {
      if (await completeLoginIfPresent()) setAuthed(true);
      setReady(true);
    })();
  }, []);

  // Once signed in, the deep link has served: forget it, so a later visit opens normally.
  useEffect(() => {
    if (authed) {
      try {
        sessionStorage.removeItem(DEEP_LINK_KEY);
      } catch {
        /* best-effort */
      }
    }
  }, [authed]);

  useEffect(() => {
    const signOutExpiredSession = () => {
      clearToken();
      setAuthed(false);
    };
    window.addEventListener("prudence:unauthorized", signOutExpiredSession);
    return () =>
      window.removeEventListener(
        "prudence:unauthorized",
        signOutExpiredSession,
      );
  }, []);

  if (!ready) return null;

  const shell = {
    vertex: "govern" as const,
    name: "Prudence",
    apps: APPS,
    actions: (
      <>
        <ThemeToggle storageKey="prudence.theme" />
        {ssoEnabled && authed && (
          <Button
            size="sm"
            onClick={() => {
              clearToken();
              setAuthed(false);
            }}
          >
            Sign out
          </Button>
        )}
      </>
    ),
  };

  if (ssoEnabled && !authed) {
    return (
      <AppShell {...shell}>
        <main>
          <div
            className="card"
            style={{
              maxWidth: 420,
              margin: "var(--sp-12) auto",
              textAlign: "center",
            }}
          >
            <h1>Sign in to Prudence</h1>
            <p className="sub">
              Governance is a controlled function — sign in with your identity
              provider.
            </p>
            <button
              className="btn btn-primary"
              onClick={() => {
                setAuthError("");
                void login().catch((error) =>
                  setAuthError((error as Error).message),
                );
              }}
            >
              Sign in with SSO
            </button>
            {authError && <p className="err">{authError}</p>}
          </div>
        </main>
      </AppShell>
    );
  }

  return (
    <AppShell
      {...shell}
      nav={
        <Tabs
          label="Prudence sections"
          items={TAB_ITEMS}
          value={tab}
          onChange={setTab}
        />
      }
    >
      <main>
        {tab === "registry" && <Registry focusSystem={link.system} />}
        {tab === "approvals" && <Approvals />}
        {tab === "decisions" && <Decisions />}
        {tab === "posture" && <Posture />}
        {tab === "incidents" && <Incidents />}
        {tab === "cases" && <GovernanceCases />}
        {tab === "impact" && <Impact />}
        {tab === "monitoring" && <Monitoring />}
        {tab === "oversight" && <Oversight />}
        {tab === "changes" && <Changes />}
        {tab === "snapshots" && <Snapshots />}
        {tab === "attestations" && <Attestations />}
      </main>
    </AppShell>
  );
}
