import { useEffect, useState } from "react";
import Registry from "./pages/Registry";
import Decisions from "./pages/Decisions";
import Policies from "./pages/Policies";
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
  Alert,
  AppShell,
  Button,
  Card,
  Identity,
  Page,
  Tabs,
  ThemeToggle,
  type TabItem,
} from "@chestertown/design";
import { APPS } from "./chestertown";
import { api, NOT_ALLOWED } from "./api";
import {
  ssoEnabled,
  getToken,
  getAuthenticatedIdentity,
  login,
  completeLoginIfPresent,
  clearToken,
} from "./auth";

type Tab =
  | "policies"
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
  { id: "policies", label: "Policy drafts" },
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
  const [identity, setIdentity] = useState(getAuthenticatedIdentity);
  const [authError, setAuthError] = useState("");
  const [noAccess, setNoAccess] = useState(false);

  useEffect(() => {
    (async () => {
      if (await completeLoginIfPresent()) {
        setIdentity(getAuthenticatedIdentity());
        setAuthed(true);
      }
      setReady(true);
    })();
  }, []);

  // A signed-in user without any Prudence role gets 403 everywhere: say so once, up front.
  useEffect(() => {
    if (!ssoEnabled || !authed) {
      setNoAccess(false);
      return;
    }
    api
      .me()
      .then((me) => setNoAccess(me.roles.length === 0))
      .catch((e: Error) => setNoAccess(e.message === NOT_ALLOWED));
  }, [authed]);

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
      setIdentity("");
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
        <Identity
          name={identity || null}
          detail={authed && ssoEnabled ? "Signed in" : "Not signed in"}
        />
        <ThemeToggle storageKey="prudence.theme" />
        {ssoEnabled && authed && (
          <>
            <Button
              size="sm"
              onClick={() => {
                clearToken();
                setIdentity("");
                setAuthed(false);
              }}
            >
              Sign out
            </Button>
          </>
        )}
      </>
    ),
  };

  if (ssoEnabled && !authed) {
    return (
      <AppShell {...shell}>
        <Page>
          <Card
            style={{
              maxWidth: 420,
              margin: "var(--sp-12) auto",
              textAlign: "center",
            }}
          >
            <h1 className="ct-title">Sign in to Prudence</h1>
            <p className="sub">
              Governance is a controlled function — sign in with your identity
              provider.
            </p>
            <Button
              variant="primary"
              onClick={() => {
                setAuthError("");
                void login().catch((error) =>
                  setAuthError((error as Error).message),
                );
              }}
            >
              Sign in with SSO
            </Button>
            {authError && <Alert tone="danger">{authError}</Alert>}
          </Card>
        </Page>
      </AppShell>
    );
  }

  if (noAccess) {
    return (
      <AppShell {...shell}>
        <Page>
          <Card
            style={{
              maxWidth: 480,
              margin: "var(--sp-12) auto",
              textAlign: "center",
            }}
          >
            <h1 className="ct-title">No access to Prudence</h1>
            <p className="sub">
              {identity || "Your account"} is signed in but has no Prudence
              role. Ask an administrator to assign one, then sign in again.
            </p>
          </Card>
        </Page>
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
      <>
        {tab === "policies" && <Policies />}
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
      </>
    </AppShell>
  );
}
