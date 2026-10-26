# Upgrading Chestertown

How the suite's versions relate, in which order to upgrade, and what each app does with its data
on the way. For the person running the suite; maintainers will find the release side at the end.

## Versions

- Each app follows **semantic versioning** on its own: AOS, Prudence, Ernest and
  `@chestertown/design`. Before 1.0, a minor release may need an upgrade step; the step is in
  its CHANGELOG, under **Upgrading**. A patch release never needs one.
- A **suite release** names the app versions tested together: [`compatibility.md`](../compatibility.md).
- The **contracts between the apps** have versions of their own (`chestertown.governance/v1`,
  `prudence.decision-api/v1`, `aos.audit-events/v2`…). Inside a version they only grow: new
  optional fields that consumers ignore. A change that would break a consumer is a new version,
  served next to the old one for at least one suite release.
- Configuration follows the same rule: a renamed or removed setting is announced as
  **Deprecated** in the CHANGELOG one suite release before it stops working. A new required
  setting is never silent: the app refuses to start and names it.

Within a suite release, any app can be upgraded on its own.

## Order

Upgrade whoever **receives** a contract before whoever **sends** it, so a new field always lands
on an app that already understands it:

1. **chestertown-infra / your deployment configuration**: new secrets and keys first. An app
   that needs one refuses to start without it.
2. **Ernest**: it receives Prudence's evidence and decisions and AOS's audit events.
3. **Prudence**: it receives AOS's decision requests and execution reports.
4. **AOS**.
5. **The consoles**: any time after their app.

## Before

- Read each app's CHANGELOG from your version to the new one, and every **Upgrading** step.
- Back up everything, keys included (operator guide, section 7), and keep that backup until the
  upgrade is verified.
- Check the new required settings. A dry start is enough: the app names what is missing and
  stops.
- Never change the AOS evidence key or the Prudence pseudonym key as part of an upgrade.

## What each app does with its data

| App | Data | On upgrade | Rolling back |
| --- | --- | --- | --- |
| Prudence | PostgreSQL | Flyway applies the new migrations at start, forward only; Hibernate then validates the schema and refuses to start if it does not match. A database from before 0.1.0 is adopted first (logged as a warning) | Restore the backup taken before: migrations have no down steps |
| Ernest | MongoDB | Evidence is append-only and never rewritten. Read models derived from the chain (indexes, statistics) are rebuilt when their version changes | The previous version reads the chain as it was; derived read models rebuild |
| AOS | Audit journal, SQLite state | The journal is verified at start (a broken chain stops AOS); its record format is stable. The state store creates what it needs and has had no schema change yet | Restore the state backup if needed; the journal is kept as is |

## After

- Health endpoints answer (operator guide, section 6).
- Ernest: `GET /api/verify` answers `isValid: true`.
- AOS: `aos -verify-audit <journal>` answers `"valid":true`.
- Prudence: posture as before; no dead-lettered evidence.
- One governed run end to end: denied while not approved, run once approved, evidence in Ernest.

## For maintainers: releasing

- `PostgresMigrationsTest` applies every Prudence migration to an empty PostgreSQL and validates
  the entities against the result, in CI. A migration is never edited once released; fix
  forward with a new one.
- Release 0.1.0 starts from one migration, `V1__baseline.sql`: the 31 pre-release migrations
  folded into the schema they built (`PreReleaseAdoptionTest` proves the catalogs identical; the
  31 are kept in `src/test/resources/db/pre-release`). A database built before 0.1.0 is adopted
  at start, with its data: its Flyway history becomes the baseline's (`PreReleaseAdoption`), and
  any other history still fails closed.
- The contract vectors run on both sides of every contract in CI; a contract change follows its
  README (sync, version).
- Each release records its upgrade steps under **Upgrading** and its deprecations under
  **Deprecated** in the CHANGELOG, and updates `compatibility.md`.
- **From 0.2.0, test the upgrade itself.** At each release tag, keep a dump of that release's
  schema with representative data, and have `PostgresMigrationsTest` migrate from every kept
  dump to the current version. With one release there is nothing to upgrade from yet; this is the
  first thing to add for 0.2.0.
- `scripts/dr-drill.sh` before tagging: a release whose backup does not restore is not a release.
