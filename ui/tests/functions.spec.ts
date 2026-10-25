import { test, expect, type Page } from "@playwright/test";

// What a signed-in person sees depends on their governance functions (GET /api/me). The backend
// enforces them; the console must offer only what the person may do. Everything is mocked: the
// API and the identity provider at http://idp.test.

const ISSUER = "http://idp.test";

const b64 = (value: object) =>
  Buffer.from(JSON.stringify(value)).toString("base64url");
const token = (user: string) =>
  `${b64({ alg: "none" })}.${b64({
    sub: user,
    preferred_username: user,
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;

const system = (id: string, name: string, approvalStatus: string) => ({
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
async function signIn(page: Page, functions: string[], user = "alice") {
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
const checkSession = (page: Page) =>
  page.evaluate(() => window.dispatchEvent(new Event("focus")));

test("a declarer registers and submits but does not approve", async ({ page }) => {
  await signIn(page, ["system.declare"]);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Register system" })).toBeVisible();

  await page.getByRole("tab", { name: "Approvals" }).click();
  await expect(page.getByRole("button", { name: "Submit for review" })).toBeEnabled();
  const approve = page.getByRole("button", { name: "Approve", exact: true });
  await expect(approve).toBeDisabled();
  await expect(approve).toHaveAttribute("title", "Needs the system.approve function");
  await expect(page.getByRole("button", { name: "Reject", exact: true })).toBeDisabled();
});

test("an approver reviews but does not register or submit", async ({ page }) => {
  await signIn(page, ["system.approve"]);
  await page.goto("/");
  await expect(page.getByText("Support assistant")).toBeVisible();
  await expect(page.getByRole("button", { name: "Register system" })).toHaveCount(0);

  await page.getByRole("tab", { name: "Approvals" }).click();
  await expect(page.getByRole("button", { name: "Approve", exact: true })).toBeEnabled();
  await expect(page.getByRole("button", { name: "Reject", exact: true })).toBeEnabled();
  const submit = page.getByRole("button", { name: "Submit for review" });
  await expect(submit).toBeDisabled();
  await expect(submit).toHaveAttribute("title", "Needs the system.declare function");
});

test("an auditor only reads", async ({ page }) => {
  await signIn(page, ["audit"], "auditor");
  await page.goto("/");
  await expect(page.getByText("Support assistant")).toBeVisible();
  await expect(page.getByRole("button", { name: "Register system" })).toHaveCount(0);

  await page.getByRole("tab", { name: "Approvals" }).click();
  await expect(page.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Approve", exact: true })).toBeDisabled();

  await page.getByRole("tab", { name: "Policy drafts" }).click();
  await expect(page.getByText("Your governance functions: audit")).toBeVisible();
  await expect(page.getByRole("button", { name: "Create draft" })).toBeDisabled();
});

test("a signed-in person without a function has no access", async ({ page }) => {
  await signIn(page, [], "visitor");
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "No access to Prudence" })).toBeVisible();
  await expect(page.getByText("visitor is signed in")).toBeVisible();
});

test("the same identity provider session keeps the person signed in", async ({ page }) => {
  await signIn(page, ["audit"]);
  await page.goto("/");
  await expect(page.getByText("Support assistant")).toBeVisible();
  const asked = page.waitForRequest(`${ISSUER}/auth**`);
  await checkSession(page);
  await asked;
  await page.waitForTimeout(1000);
  await expect(page.getByText("Support assistant")).toBeVisible();
});

test("another identity provider session ends ours", async ({ page }) => {
  const idp = await signIn(page, ["audit"]);
  await page.goto("/");
  await expect(page.getByText("Support assistant")).toBeVisible();
  // Signed out in another app, someone else signed in: the provider reports another session.
  idp.session = "session-b";
  await checkSession(page);
  await expect(page.getByRole("heading", { name: "Sign in to Prudence" })).toBeVisible();
});
