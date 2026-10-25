import { type Page } from "@playwright/test";

// Shared by the browser tests: a mocked identity provider (http://idp.test) and a mocked API.

export const ISSUER = "http://idp.test";

const b64 = (value: object) =>
  Buffer.from(JSON.stringify(value)).toString("base64url");
const token = (user: string) =>
  `${b64({ alg: "none" })}.${b64({
    sub: user,
    preferred_username: user,
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;

export const system = (id: string, name: string, approvalStatus: string) => ({
  id,
  name,
  intendedPurpose: "Assist analysts",
  ownerName: "Owner",
  ownerEmail: "owner@bank.eu",
  riskTier: "LIMITED",
  annexIII: false,
  approvalStatus,
  regulatoryRole: "DEPLOYER",
  systemVersion: "1.0.0",
  modelHash: "a".repeat(64),
  annexI: false,
  operatingGeography: "European Union",
  affectedPopulation: "Analysts",
  classificationJustification: "Limited risk",
  obligations: ["Transparency"],
  assessedAt: "2026-10-01T00:00:00Z",
  approvedAt: null,
  approvalExpiresAt: null,
  firstApprover: null,
  firstApprovedAt: null,
  renewalFirstApprover: null,
  renewalFirstApprovedAt: null,
  reviewDue: false,
});

/** Signs a person in with these functions; `idp.session` is what the identity provider reports. */
export async function signIn(page: Page, functions: string[], user = "alice") {
  const idp = { session: "session-a" };
  await page.addInitScript(
    ([seededToken, session]) => {
      // Seed the top window once, so a sign-out during the test sticks.
      if (window.top !== window || sessionStorage.getItem("test_seeded")) return;
      sessionStorage.setItem("test_seeded", "1");
      sessionStorage.setItem("prudence_token", seededToken);
      sessionStorage.setItem("prudence_idp_session", session);
    },
    [token(user), idp.session],
  );
  await page.route(`${ISSUER}/.well-known/openid-configuration`, (route) =>
    route.fulfill({
      json: {
        issuer: ISSUER,
        authorization_endpoint: `${ISSUER}/auth`,
        token_endpoint: `${ISSUER}/token`,
        end_session_endpoint: `${ISSUER}/logout`,
      },
    }),
  );
  // prompt=none: the identity provider sends the browser back with a code and its session.
  await page.route(`${ISSUER}/auth**`, (route) => {
    const asked = new URL(route.request().url());
    const back = new URL(asked.searchParams.get("redirect_uri")!);
    back.searchParams.set("state", asked.searchParams.get("state")!);
    back.searchParams.set("code", "code");
    back.searchParams.set("session_state", idp.session);
    return route.fulfill({ status: 302, headers: { location: back.toString() } });
  });
  // Later routes win: a catch-all first, the endpoints that matter after it.
  await page.route("**/api/**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/me", (route) =>
    route.fulfill({ json: { name: user, roles: [], functions } }),
  );
  await page.route("**/api/policy-admin/status", (route) =>
    route.fulfill({
      json: {
        managed: false,
        actorMode: "legacy",
        approvalAvailable: false,
        roles: [],
        functions,
        requiredPublicationId: null,
        requiredDigest: null,
      },
    }),
  );
  await page.route("**/api/registry/systems", (route) =>
    route.fulfill({
      json: [
        system("ready-1", "Support assistant", "UNDER_ASSESSMENT"),
        system("pending-1", "Credit pre-screening", "PENDING_APPROVAL"),
      ],
    }),
  );
  return idp;
}

/** Asks the console to check the identity provider session now, as when the tab regains focus. */
export const checkSession = (page: Page) =>
  page.evaluate(() => window.dispatchEvent(new Event("focus")));

