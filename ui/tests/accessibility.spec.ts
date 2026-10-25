import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { signIn, system } from "./fixtures";

// WCAG 2.1 AA (the level EN 301 549 requires of public-sector software in the EU), checked with
// axe on every page of the console, in light and dark themes.

const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

async function violations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(WCAG).analyze();
  return results.violations.map((v) => ({
    rule: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => {
      const contrast = n.any.find((c) => c.id === "color-contrast")?.data;
      return contrast
        ? `${n.target.join(" ")} — ${contrast.fgColor} on ${contrast.bgColor}: ${contrast.contrastRatio}:1 (needs ${contrast.expectedContrastRatio})`
        : n.target.join(" ");
    }),
  }));
}

const EVERY_PERSON_FUNCTION = [
  "audit",
  "incident.handle",
  "oversight",
  "platform.admin",
  "policy.approve",
  "policy.author",
  "system.approve",
  "system.declare",
];

for (const scheme of ["light", "dark"] as const) {
  test.describe(`${scheme} theme`, () => {
    test.use({ colorScheme: scheme });

    test("the sign-in page", async ({ page }) => {
      await page.goto("/");
      await expect(page.getByRole("heading", { name: "Sign in to Prudence" })).toBeVisible();
      expect(await violations(page)).toEqual([]);
    });

    test("every tab", async ({ page }) => {
      await signIn(page, EVERY_PERSON_FUNCTION);
      // Every risk tier and approval state, so every badge is on screen.
      await page.route("**/api/registry/systems", (route) =>
        route.fulfill({
          json: [
            { ...system("s-high", "Credit scoring", "PENDING_SECOND_APPROVAL"), riskTier: "HIGH", annexIII: true },
            { ...system("s-prohibited", "Social scoring", "BLOCKED"), riskTier: "PROHIBITED" },
            { ...system("s-limited", "Support assistant", "UNDER_ASSESSMENT") },
            { ...system("s-minimal", "Spam filter", "APPROVED"), riskTier: "MINIMAL", reviewDue: true },
            { ...system("s-rejected", "CV screening", "REJECTED"), riskTier: "HIGH" },
            { ...system("s-suspended", "Translator", "SUSPENDED") },
          ],
        }),
      );
      await page.goto("/");
      await expect(page.getByRole("button", { name: "Register system" })).toBeVisible();
      const tabs = page.getByRole("tab");
      const found: Record<string, unknown> = {};
      for (let i = 0; i < (await tabs.count()); i++) {
        const tab = tabs.nth(i);
        const name = (await tab.textContent())?.trim() ?? String(i);
        await tab.click();
        await page.waitForLoadState("networkidle");
        const v = await violations(page);
        if (v.length) found[name] = v;
      }
      expect(found).toEqual({});
    });
  });
}
