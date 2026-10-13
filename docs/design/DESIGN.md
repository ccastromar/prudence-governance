# Chestertown design language — one family, three apps

AOS **operate** · Prudence **govern** · Ernest **assure**. Three apps because they serve a regulated
organization's **three lines of defense** (operations / risk & compliance / audit), which are
independent by regulatory design. One *family* because they're one platform — unified by a
shared visual language and a single sign-on.

## The one rule that makes them a family
Everything is shared — neutrals, type, spacing, status colors — **except one accent per
vertex**, set with `data-vertex` on `<html>`:

| App | `data-vertex` | Accent | Means |
|---|---|---|---|
| AOS | `operate` | blue `#2563eb` | run it |
| Prudence | `govern` | amber `#d97706` | decide / oversee |
| Ernest | `assure` | teal `#0d9488` | prove it |

The accent colors buttons, active nav, links, focus rings — so you always know *which line
of defense you're standing in* — while the layout, density and components stay identical.

## Adopt it
Import `tokens.css` and set the vertex:
```html
<html data-vertex="govern">
  <link rel="stylesheet" href="docs/design/tokens.css">
```
Then use the role tokens, never raw hex: `var(--bg)`, `--surface`, `--border`, `--text`,
`--text-muted`, `--accent`, `--accent-fg`. Dark mode is automatic (`prefers-color-scheme`),
forceable with `data-theme="light|dark"`.

## Shared vocabulary (colors that mean the same thing everywhere)
- **Status**: `--ok` / `--warn` / `--danger` / `--info` (+ `-bg`/`-fg` pairs for badges).
- **EU AI Act risk tiers**: `--risk-prohibited` (red) · `--risk-high` (amber) ·
  `--risk-limited` (blue) · `--risk-minimal` (slate). Same tier → same colour in all three.
- **PDP decisions**: `--decision-allow` (green) · `--decision-deny` (red) ·
  `--decision-approval` (amber). A decision looks the same in AOS (the gate), Prudence (the
  PDP) and Ernest (the sealed record).

## Components (conventions, not a cross-framework library)
A shared component *library* across Svelte + React isn't worth it for a PoC; shared **tokens
+ patterns** are. Build native components per framework, but to these patterns:
- **Surfaces**: `--surface` on `--bg`, `1px solid --border`, `--radius`, `--shadow-sm`.
- **Badges/pills**: `-bg`/`-fg` token pairs, `--radius-full`, `--fs-xs`, uppercase tracking.
- **Tables**: dense, `--fs-sm`, muted uppercase headers, row hover `--surface-muted`.
- **Buttons**: primary = `--accent`/`--accent-fg`; secondary = outline on `--border`.
- **Focus**: always `box-shadow: var(--ring)` — visible, accent-tinted, accessible.
- **Type**: Inter (sans), JetBrains Mono for hashes/ids/receipts.

## Convergence, not a rewrite
New surfaces (Prudence) are built on this from day one. AOS and Ernest converge gradually —
drop in `tokens.css`, set their `data-vertex`, migrate hard-coded colors as they're touched.
No big-bang redesign.

## The glue
A top-bar **app switcher** (implemented in all three UIs) turns "three apps" into "one
platform, three modules": the same AOS · Prudence · Ernest control, the current app shown in
its vertex accent, cross-linking via deploy-configured URLs (`*_AOS_URL` / `*_PRUDENCE_URL` /
`*_ERNEST_URL`, dev defaults otherwise). The remaining layer is **one SSO** (OIDC, already
built in Ernest) so a single login carries across the lines of defense a user holds a role in.
