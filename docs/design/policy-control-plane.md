# Policy control plane: implementation design

Status: managed policy publication, pull distribution, runtime evaluation and decision evidence are implemented behind an opt-in switch. The default remains the legacy OPA policy. Production rollout still requires a secured OPA deployment and the integration and coverage checks listed below; do not treat this implementation as fully deployed.

## Authority and identity

The customer selects an OIDC issuer; validate signature, issuer, expiry and configured audience on access tokens. Human and machine identities are distinct. Identify an actor by the pair (issuer, subject), never email. `issuer-subject` mode encodes the pair as `<issuer-length>:<issuer><subject>`; `legacy` retains existing names to avoid silently invalidating existing approvals and OPA grants. Changing modes is a data migration: existing author/reviewer identities, four-eyes checks, audit references and OPA grants must be migrated consistently before enabling it. Role claim selection and a static external-to-internal role mapping are configurable; this is not yet a managed or versioned role mapping service. Token audiences and provider interoperability must be verified for each deployment. Keycloak is the demo provider, not a required implementation.

The authenticated workload is the caller. A claimed end-user is not authoritative without a separately validated delegation mechanism, including audience, expiry and binding to the requested operation. Initially authorize execution as the workload; no unverified end-user substitution. Mapping external app roles to domain roles must be versioned and scoped to a trusted issuer. Missing, malformed or stale attributes do not grant access. Entra group overage requires explicit handling; absent groups must not be mistaken for complete membership. Never authorize using ID tokens as access tokens.

## Policy administration

PostgreSQL is authoritative for draft policies and immutable published versions. Provide a bounded, validated model: principal role or workload, action, resource selector and effect, with explicit conflict resolution (deny overrides allow, default deny). First slice: `pipeline.run` and `evidence.read` only where the target enforces the decision. Keep protected AI lifecycle checks in Prudence and ensure a policy cannot override suspension, prohibition, expiration or approval requirements. Never expose a control as enforced until its PEP actually calls the PDP and observes its verdict.

Policy versions: DRAFT → VALIDATED → APPROVED → DISTRIBUTING → ACTIVE or FAILED. Editing an approved version creates a new draft. Validate the schema, referenced actions, test vectors, and deny/allow conflicts; use optimistic concurrency. A distinct authorized reviewer approves publication. Restrict the bootstrap administration path independently of the policies it administers; record every change and approval via the durable evidence outbox. Simulation must use a draft snapshot and be clearly marked non-authoritative. Readers must see the current effective version and publishing failures.

## Publication and OPA

A publisher creates an immutable bundle from an approved snapshot plus tested Rego, with deterministic canonical content, version and digest. Persist the bundle and publication state using a transactional outbox; retries are idempotent. Authenticate bundle delivery and secure OPA's management and decision endpoints. OPA pulls a bundle via its supported bundle interface; no restart. Bundle publication is not the same as activation. Track acknowledgement per OPA instance, its active revision, errors and time; never claim a version is active merely because it was delivered. Rollback is a new audited publication referencing a previous immutable snapshot.

Every evaluation must return the revision and digest from the *same evaluated snapshot* as the verdict. Prudence verifies the returned identity against the configured required active revision and fails closed if it is missing, stale, unknown or inconsistent. A status check made separately from the decision is insufficient because activation may race the check. Design and verify how the bundle embeds these values without a self-referential digest; the digest can cover the canonical policy payload while bundle metadata carries its value. Document propagation and behavior during mixed revisions. Bootstrap starts denied until a verified version is active.

## Decisions and evidence

The PDP combines authoritative registry facts and a policy decision: distinguish ALLOW, DENY, APPROVAL_REQUIRED and policy-engine ERROR; no execution on ERROR. Evidence includes the actual evaluated version/digest, decision ID, stable caller and actor identifiers, policy outcome, resource and relevant registry snapshot/version. Never log credentials or raw tokens. The present adapter returns only a boolean and version/hash come from configuration, so current evidence does not yet prove the effective OPA version. Preserve v1 compatibility deliberately and version any new decision API.

## Delivery order and acceptance

1. Complete configurable issuer, mandatory audience validation, roles mapping and stable identities; test Keycloak and Entra-compatible tokens, migrations, issuer collisions and service identities.
2. Implement persistent policy model, scoped administration UI, independent approval, simulation and audited lifecycle; preserve the existing policy until migration is explicitly completed.
3. Implement deterministic OPA bundle generation, secure distribution, per-instance activation and rollback; test invalid bundles, concurrent publication and partial deployment.
4. Return evaluated version/digest from the same OPA evaluation and record it in evidence; test stale/mixed versions and OPA outage fail-closed behavior.
5. Enforce execution permissions end to end and implement evidence read authorization only where the evidence service and adapters enforce it. Test bypass attempts and tenant/resource boundaries.

Challenges or per-operation human approvals are a separate feature: bind them to operation, parameters and actor, enforce expiry, separation of duties and single use, and re-evaluate immediately before execution. A visual policy control alone does not implement a challenge.

## Historical implementation notes

The following sections record the incremental implementation before managed publication was connected; where they describe missing functionality, the current status below supersedes them.

## Storage foundation

Migration `V26__policy_control_plane.sql` creates the initial definition and revision tables, including distinct author/reviewer and revision-number constraints. This is deliberately inert: A restricted policy draft API (`author` or `admin` roles) persists create and update operations and a local transactional draft event. It is not yet externally sealed and does not provide publication or enforcement. Validation and independent approval are present at the API layer. A non-authoritative simulation previews only an individual draft. Draft changes never affect the PDP or OPA. In particular, database columns alone do not enforce immutable snapshots or ensure that an ACTIVE revision was approved or distributed; the application lifecycle and transition tests must be implemented before exposing them. OPA's current files continue to govern decisions.

## Draft API (initial slice)

Production requires an authenticated Prudence `author` or `admin` role. `POST /api/policy-admin/drafts` creates a definition and draft, `GET /api/policy-admin/definitions` lists definitions, `GET /api/policy-admin/definitions/{id}/revisions` lists revisions, and `GET`/`PUT /api/policy-admin/drafts/{revisionId}` reads/edits a draft. A draft contains up to 100 `{role, action: "pipeline.run", pipeline, effect: "ALLOW" | "DENY"}` rules. PUT requires the last returned `version`; concurrent/stale writes conflict. Each change is saved with a local event in the same DB transaction. This event is not externally sealed. The API requires an authenticated principal even in the open dev profile. Drafts remain inert and are never sent to OPA. No claims of authoritative evaluation, approval, publication, or production readiness should be made until those paths and database migrations are verified end to end.

`POST /api/policy-admin/drafts/{revisionId}/simulate` previews one DRAFT against supplied roles, action and pipeline. Explicit DENY takes precedence, otherwise ALLOW requires a matching rule; it defaults to DENY. The response is explicitly non-authoritative and does not consult identity, system lifecycle, other policies, or OPA.

`POST /api/policy-admin/drafts/{revisionId}/validate` validates a DRAFT and rejects duplicate or conflicting role/action/pipeline rules. `POST /api/policy-admin/drafts/{revisionId}/approve` requires the `policy-reviewer` role, the current version and issuer-subject mode; any contributor recorded on the revision is barred from approving it. Validation and approval append local transactional events. Approval freezes the revision but does not publish or activate it. The separate reviewer must also be provisioned access to any read endpoints needed for review. Existing identity references must be migrated before enabling issuer-subject mode.

## Managed evaluator prototype

`policy/managed/decision.rego` is an isolated, non-deployed evaluator for the draft rule shape. It returns ALLOW/DENY and the bundle metadata in one OPA result, with explicit deny precedence and default deny. It expects trusted roles supplied by the caller and an independently verified payload digest; it does not authenticate callers or verify a digest itself. It is not currently referenced by `compose.yaml` or `OpaPolicyAdapter`. This is only one part of publication: artifact creation and signing, validation of the complete bundle, secure distribution, activation acknowledgement, revision enforcement, rollback and production migration remain unimplemented.

## Managed result verification prototype

`ManagedOpaResult` parses an OPA response and returns an error unless the decision is ALLOW/DENY and both the version and SHA-256 payload digest match a separately established required identity. This parser is not wired to the PDP, HTTP response or evidence: publication/activation, trusted role derivation and a reliable required identity are prerequisites. It cannot prove that the bundle contents match the digest; artifact verification and trusted transport must establish that independently. For registry denials where OPA is not consulted, evidence must explicitly say no policy engine evaluation occurred rather than claim a managed revision was evaluated.

## Revision editing and bundle preview

An administrator can reopen a validated revision or fork an approved revision to a new draft. The UI supports multiple rules and optimistic edits. A new revision requires a separate review; these operations append local transactional events. Authenticated reviewers and administrators can read revision events, which are not yet externally sealed.

For inspection only, `GET /api/policy-admin/drafts/{id}/bundle-preview` builds a deterministic gzip/tar OPA bundle for a single approved revision. The digest covers the canonical, sorted rule JSON, avoiding a self-referential hash; it does not authenticate the Rego module or the archive. The revision ID and digest are included in the bundle and response headers. This endpoint does not publish, activate, or acknowledge anything, and the preview is not a complete aggregate of all approved policies. Publishing must create its own immutable combined snapshot and protect the complete artifact and transport.


## Current managed path and deployment requirements

With `prudence.managed.enabled=true`, an administrator publishes an approved revision. The current revision of every selected policy becomes one immutable PostgreSQL publication. A row lock serializes publication and rollback, and every rollback creates a new publication. The authenticated bundle endpoint serves the current aggregate; OPA polls it without restart. Publication means requested, not active. Each successful decision stores a last observation for its configured OPA endpoint; it does not prove all instances are healthy or up to date. Configure each Prudence instance to talk directly to one OPA instance and give that endpoint a unique `PRUDENCE_MANAGED_OPA_INSTANCE_ID`.

The PDP derives roles from the authenticated access token, not the request JSON. It holds a shared channel lock through decision and evidence commit and checks the version and digest returned in the same OPA response. Missing, unavailable, or stale results yield ERROR and never allow execution. Denials made by registry lifecycle checks carry no evaluated policy version. The durable evidence outbox stores the actual version and digest. Draft changes and publications also write a transactional audit outbox; its Ernest delivery worker retries, and QTSP signing is used when configured. This delivery is not implemented for the CloudEvents sink; production managed mode requires Ernest.

Enable managed mode only after provisioning a separate reviewer, migrating historical identities to `issuer-subject`, configuring token issuer, audience, role mapping and machine roles, and publishing an initial approved policy. The first bundle and every change temporarily fail closed while OPA pulls it. Set `PRUDENCE_MANAGED_BUNDLE_KEY` (at least 32 random characters), `PRUDENCE_MANAGED_BUNDLE_URL`, `PRUDENCE_MANAGED_OPA_TOKEN`, `PRUDENCE_MANAGED_OPA_INSTANCE_ID` and `PRUDENCE_OPA_BASE_URL`. Production requires HTTPS for both URLs. `policy/managed/opa-config.example.yaml` is a template: inject the same bundle key using a secret manager and protect the file. The deployment must ensure authenticated TLS to the bundle endpoint and the OPA decision endpoint, and restrict access to OPA management APIs. TLS termination must be trusted and the servlet request marked secure. The digest covers rules, not the evaluator archive; this implementation does not yet sign bundles independently.

The local integration check used PostgreSQL 16, a real OPA instance and synthetic authenticated principals. It verified separate author/reviewer, aggregate publication, OPA pull, fail-closed behavior during propagation, decision evidence identity, and audit outbox delivery to a simulated Ernest receiver. It did not validate a real IdP, an Ernest deployment or the complete AOS/Ernest enforcement surface. AOS currently opts into the governance gate per pipeline, and evidence reads are not managed by these rules. Do not enable or advertise `evidence.read` until Ernest enforces it end to end.
