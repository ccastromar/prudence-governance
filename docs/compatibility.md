# Compatibility matrix · Chestertown 0.1.0

What works with what, and how each claim is checked. Update it with every release of any of
the three apps or the design system; a row without a check is a promise nobody verifies.

## Apps in the suite

| Suite | AOS (orchestrate) | Prudence (govern) | Ernest (prove) | `@chestertown/design` |
| --- | --- | --- | --- | --- |
| 0.1.0 | v0.4.0 (server and `ui/` console) | 0.1.0 | v0.3.0 (backend, event-ingestor, console) | 0.3.11 |

Within a suite version, any app can be upgraded on its own as long as the contracts below keep
their version.

## Contracts between the apps

| Contract | Provider | Consumers | Version | Checked by |
| --- | --- | --- | --- | --- |
| Governance functions (`contract/governance/`) | Prudence | AOS, Ernest | `chestertown.governance/v1` | Vectors (resolution, validation) in Prudence's CI; AOS and Ernest run them on a copy synced at `56bdddb` and check its SHA-256 sums |
| Decision API (`docs/api/decision-api-v1.yaml`, `contract/decision/`) | Prudence | AOS gate, `integrations/prudence-core` (Pydantic AI, LangGraph, ADK, OpenAI Agents, Agent Framework) | `prudence.decision-api/v1` | `DecisionApiContractTest` (Prudence's controllers against the vectors and the schema); AOS `decision_contract_test.go` on a synced copy; prudence-core `tests/test_decision_contract.py`; the reference in `contract/decision/reference/` |
| AuthZEN evaluation (`/access/v1/evaluation(s)`) | Prudence | MCP gateways | AuthZEN 1.0 | `integrations/conformance/run-sso.py` (needs the local stack, not in CI) |
| Evidence events (`docs/api/evidence-events-v1.yaml`) | Prudence | A company's own audit store (Kafka or webhook) | `chestertown.prudence.<kind>.v1` | An end-to-end check against the running stack |
| Prudence → Ernest evidence (`contract/ernest/`): reviews, decisions, attestations, proofs, Posture reads | Ernest | Prudence (`ReviewSealAdapter`, `ErnestEvidenceAdapter`, `ErnestAttestationAdapter`, `ErnestEvidenceHealthAdapter`) | `prudence.ernest-evidence/v1` | `ErnestEvidenceContractTest` (Prudence's adapters against the interactions); Ernest `prudence-contract.test.js` (batching `on`, as deployed), `prudence-contract-events.test.js` (`events`, Ernest's default) and `prudence-contract-unbatched.test.js` (`off`: attestations confirmed through provenance), the real backend on MongoDB, on a synced copy. With batching `off` Ernest does not deduplicate retries or refuse conflicting re-sends, so do not turn it off where Prudence writes. Not covered: qualified seals |
| Prudence decision events (`contract/decision-events/`): the Kafka record each PDP decision becomes (or the ingestor's `POST /events` body) | Prudence | Ernest event-ingestor | `prudence.decision-events/v1` (`policy.decision`) | `DecisionEventsContractTest` (exact records and `POST /events` requests); Ernest `kafkasource/decision_events_contract_test.go` and `ingest/prudence_contract_test.go` on a synced copy (deduplication id, `policy_decision`, system of record; the ingest key accepted) |
| AOS audit events (`contract/audit-events/` in AOS): the Kafka record each journal entry becomes, or the signed `POST /events` request | AOS | Ernest event-ingestor | `aos.audit-events/v2` (events identified by a keyed digest, `AOS_EVIDENCE_KEY`) | AOS `contract_test.go` (exact records; no user id, parameter, error or bare record hash leaves AOS); Ernest `kafkasource/contract_test.go` and `ingest/aos_contract_test.go` on a synced copy (deduplication id, model, `stage: execution`; AOS's HMAC signature accepted). Not covered: the backend's counting of what the ingestor recorded (Ernest's own tests) |
| Observability conventions (`contract/observability/`): resource attributes, `trace_id` versus `correlation_id`, propagation, log fields, metric names, indicators | — | AOS, Prudence, Ernest | v1 | Not yet checked automatically; each app's state is in its section 9 |
| Design system | `@chestertown/design` | the three consoles | 0.3.x | Vendored tarball pinned by version and integrity; axe (WCAG 2.1 AA) in each console's e2e |

## Identity providers

People sign in with OpenID Connect; machines use client credentials. The governance model maps
each provider's groups or roles to functions.

| Provider | Version tested | Checked by |
| --- | --- | --- |
| Keycloak | 26.8 | `scripts/e2e-suite.sh` (25 cases) |
| Microsoft Entra ID | v2 access tokens, development tenant | `scripts/idp-acceptance.sh`: 14/14 (2026-10-25) |
| Dex | v2.46.0 | `scripts/idp-acceptance.sh`: 14/14 (2026-10-25); reproducible local tests, not production |

Details and limits: `docs/design/idp-interoperability.md`.

## Platform

| Component | Version | Used by |
| --- | --- | --- |
| Java | 25 (Temurin) | Prudence |
| Go | 1.26 | AOS, Ernest event-ingestor |
| Node.js | 24 | Ernest backend and console; consoles' build |
| PostgreSQL | 17 | Prudence |
| MongoDB | 7 | Ernest |
| Apache Kafka | 4.2 | Ernest ingestion, Prudence evidence delivery |
| Open Policy Agent | 1.21 | Prudence's PDP |

## How to change a contract

1. Change it in its provider repository, with its vectors, and commit.
2. Run its `sync.sh` so every consumer gets the copy and its sums; the consumers' tests fail
   until they implement the change.
3. A change that would alter what an existing vector expects is a new version (`…/v2`), served
   alongside v1 for at least one suite release. Update this matrix.
