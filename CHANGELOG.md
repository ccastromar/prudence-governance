# Changelog

All notable changes to Prudence are documented here.

The format follows the spirit of Keep a Changelog, and Prudence uses semantic versioning.

## [Unreleased]

Prudence 0.1.0 is the first release. It ships in Chestertown 0.1.0, with AOS (orchestrate) and
Ernest (prove). Prudence is a governance-readiness platform, not a legal declaration of EU AI Act
conformity.

### Added

- **AI system registry.** A classification dossier per system (EU AI Act role, Annex I/III,
  version and model hash, geography, affected population, justification, obligations) with its
  risk tier. Registration never authorizes use: a system is submitted for review once its
  compliance controls, accountabilities and impact assessments are in place, and approved,
  rejected, renewed before it expires, or suspended. Records predating the dossier drop back to
  assessment.
- **Policy decision point.** Decision API v1 (`POST /api/policy/decisions`, OpenAPI in
  `docs/api/`) backed by OPA/Rego, plus an OpenID AuthZEN front door; fail-closed, with the policy
  version and digest recorded on every decision. An unregistered system is a `DENY`. Governed
  executions are reported to `POST /api/executions`. `contract/decision/` turns the API into
  test vectors that Prudence, AOS and prudence-core's gate run in CI: only status 200 with
  `ALLOW` and a `decisionId` lets a step run.
- **Evidence contract with Ernest.** `contract/ernest/` lists what Prudence sends to Ernest and
  the fields it reads back; Prudence's adapters run it against a stub and Ernest's backend
  replays it against itself, with batching on as deployed.
- **Decision events contract.** `contract/decision-events/` pins the Kafka record each PDP
  decision becomes (key, headers, body) and what Ernest's event-ingestor makes of it
  (deduplication id, `policy_decision`, the system of record).
- **Policy control plane.** Policy drafts are validated, simulated, approved by someone other
  than their contributors, and published to managed OPA instances, whose observed state is
  reported.
- **Oversight and incidents.** Human oversight assignments with training and authority, an AI
  incident register with AI Act art. 73 deadlines, monitoring signals and drift, posture across
  the estate, governance cases, change assessments, snapshots and signed attestations, and email
  alerts.
- **Evidence to Ernest.** A durable transactional outbox delivers every decision and review to
  Ernest (HTTP or Kafka) and as CloudEvents, with retries; evidence events v1 are described in
  AsyncAPI (`docs/api/`). Prudence reports when the evidence store is unhealthy.
- **Governance functions** (`contract/governance/`, contract `chestertown.governance/v1`): the
  twelve functions AOS, Prudence and Ernest authorize by; the governance model that maps an
  organization's identity provider roles or groups to them; its rules (approvals per risk tier,
  incompatible functions, required accountabilities) within the legal floor; the default model;
  and test vectors shared by the Python reference, Java, Go and TypeScript.
- **Organization templates and `ct-model`.** Starting models for a small or medium organization,
  a bank and a public body; `ct_model.py` checks a model for gaps and generates a Keycloak
  development realm, Entra ID app roles and app settings, and explains what any token gets.
- **Identity providers.** An OAuth 2.0 resource server for any OIDC provider (issuer, audience and
  signature checked). `scripts/idp-acceptance.sh` checks a provider with five of its tokens;
  Keycloak, Dex and Entra ID pass it (`docs/design/idp-interoperability.md`).
- **Authorization by function.** Each route needs a governance function: a person's token never
  calls the PDP (`runtime.decide`) or reports executions (`runtime.report`); approving a system
  is `system.approve` and declaring one `system.declare`. With `[system.declare, system.approve]`
  in the model, whoever registered a system or submitted its review cannot review it. A system's
  owner never reviews it, and a policy draft's contributors never approve it, whatever the model
  says. `GET /api/me` returns the caller's functions.
- **Console.** React, on the shared `@chestertown/design`, with OIDC sign-in, suite-wide sign-out
  and pages gated by the caller's functions.
- **Integrations.** Python adapters for LangGraph and Pydantic AI; a guide to connect any
  orchestrator (`docs/integration/`).
- **Operations.** Docker image and Compose file; production refuses to start without an issuer,
  an audience, database credentials, an Ernest key and CORS origins; local scripts without Docker;
  a whole-suite end-to-end test (`scripts/e2e-suite.sh`) and a load test.
