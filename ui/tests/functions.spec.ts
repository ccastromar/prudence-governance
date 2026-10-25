import { test, expect } from "@playwright/test";
import { ISSUER, checkSession, signIn } from "./fixtures";

// What a signed-in person sees depends on their governance functions (GET /api/me). The backend
// enforces them; the console must offer only what the person may do. Everything is mocked: the
// API and the identity provider at http://idp.test.

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
