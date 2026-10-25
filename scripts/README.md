# Local scripts (no Docker)

Run Prudence and its dependencies on one machine. Login comes from a local Keycloak — the
Chestertown dev IdP shared by AOS · Prudence · Ernest, standalone on your JDK. Test realm only; the passwords here are demo values.

## Run

Without login (H2 in `./data`, every action signed as `dev-reviewer`): `./scripts/opa.sh`,
`./scripts/backend.sh`, `./scripts/ui.sh`. With login, as below.

Download Keycloak once (any recent 26.x `.tar.gz` from github.com/keycloak/keycloak/releases)
and unpack it next to this repo. Then, each in its own terminal:

```bash
./scripts/keycloak.sh      # IdP on :8081, imports this realm (or set KEYCLOAK_HOME)
./scripts/opa.sh           # policy engine on :8181
./scripts/backend-sso.sh   # Prudence secured (JWT + PostgreSQL/Flyway) on :8099
./scripts/ui-sso.sh        # console with SSO login on :5200
./scripts/seed.sh          # optional: the sample AI systems docs/functional/ uses
./scripts/kafka.sh         # optional: Kafka (KRaft) on :9092 for decision-transport=kafka
./scripts/ernest.sh        # Ernest from the sibling repo on :3001 (--kafka adds its Kafka consumer)
```

Decisions over Kafka (Ernest ADR-005): start `./scripts/kafka.sh`, then `./scripts/ernest.sh --kafka`
(Ernest's backend plus its `event-ingestor kafka` consumer), and start Prudence with
`PRUDENCE_ERNEST_DECISION_TRANSPORT=kafka PRUDENCE_ERNEST_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092`.
This local Kafka is PLAINTEXT, so use it with `./scripts/backend.sh` (dev profile): the secured
`backend-sso.sh` refuses `decision-transport=kafka` without SSL or SASL_SSL, as production does.

`backend-sso.sh` runs the production security configuration with local values: a
`prudence_sso` database in your local PostgreSQL (created on first run), a dummy SMTP host
(alerts end up as undelivered records in Posture) and open-access Ernest. In this mode the
*Policy decisions* tab answers 403 to people: the PDP only accepts the `pdp-client` token below.

Realm `chestertown` issuer: `http://localhost:8081/realms/chestertown`.
Clients (public, PKCE): `ernest-spa`, `prudence-spa`, `aos-spa`.
Machine-to-machine (confidential, client_credentials): `pdp-client` (secret `pdp-dev-secret`),
carrying the `pdp.decide` client scope — the credential a PDP caller (AOS gate, runtime
adapter) presents to Prudence's `/api/policy/decisions`.
Users (password = username): `alice` (Prudence author and system approver, Ernest editor, AOS operator), `bob` and
`carol` (Prudence system approvers), `policy-author` (Prudence admin), `policy-reviewer`,
`auditor` (read-only everywhere). Each app translates the realm roles (`prudence-*`, `ernest-*`,
`aos-*`) to its own names with its role-mapping setting, listed below.

### Calling the secured PDP (client_credentials)

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/chestertown/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=pdp-client \
  -d client_secret=pdp-dev-secret -d scope=pdp.decide | jq -r .access_token)
curl -X POST http://localhost:8099/api/policy/decisions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"subject":"ignored-in-secured-mode","pipeline":"credit-risk-scoring","aiSystemId":"<id>","action":"run"}'
```

The Python adapters do this automatically when `PRUDENCE_PDP_TOKEN_URL` / `_CLIENT_ID` /
`_CLIENT_SECRET` are set (see `prudence_governance.M2MAuth`). In secured mode the PDP takes
the subject from the token; the JSON `subject` is ignored, so a caller cannot impersonate a
different principal. A token without the
`pdp.decide` scope (e.g. a human SSO token) gets 403; no token gets 401.

## Whole suite, end to end

`./scripts/e2e-suite.sh` runs AOS, Prudence (secured) and Ernest together against the local
Keycloak and checks the loop and its failure modes, in about three minutes:

- identity: the token carries the `prudence-api` audience and suite roles; no token → 401; the
  auditor cannot register a system (403); Prudence (`/api/me`) and Ernest (`whoami`) resolve the
  same person to their own governance functions (contract v1)
- governance: the four MVP systems give AOS DENY / APPROVAL_REQUIRED / DENY / DENY; once
  approved in Prudence, the support lookup runs
- evidence: the run reaches Ernest over Kafka and Prudence shows the system GREEN
- failure modes: with Kafka down AOS still runs and the evidence arrives once Kafka is back; with
  Ernest down Prudence reports it unhealthy, then healthy again; with Prudence down AOS fails
  closed

It uses its own ports, a throw-away Kafka and its own databases (`prudence_e2e`, `ernest_e2e`,
dropped at the end), starts Keycloak if it is not running, and refuses to start if one of its
ports is busy. `KEEP_LOGS=1` keeps the logs. The consoles (browser sign-in and suite-wide
sign-out) are not covered.

## Point the apps at it (dev)

- Ernest frontend: `PUBLIC_OIDC_ISSUER=http://localhost:8081/realms/chestertown PUBLIC_OIDC_CLIENT_ID=ernest-spa`
- Ernest backend: `OIDC_ISSUER=…/realms/chestertown OIDC_AUDIENCE=ernest-spa OIDC_ROLE_CLAIM=roles OIDC_ROLE_MAP=ernest-editor=read-write,ernest-reader=read-only`
- Prudence backend: `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=…/realms/chestertown PRUDENCE_IDENTITY_ROLE_MAPPING=prudence-reader=reader,prudence-author=author,prudence-system-approver=system-approver,prudence-policy-reviewer=policy-reviewer,prudence-admin=admin`
- Prudence UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=prudence-spa` (`VITE_OIDC_SCOPE` when the IdP needs more than `openid profile email`)
- AOS backend: `AOS_JWT_ISSUER=…/realms/chestertown AOS_JWK_URL=…/realms/chestertown/protocol/openid-connect/certs AOS_ROLE_MAP=aos-admin=admin,aos-operator=operator,aos-auditor=guest`
- AOS UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=aos-spa` (`VITE_OIDC_SCOPE` likewise)

## Production

For a local unauthenticated backend, explicitly activate the dev profile:

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

Outside `dev`/`test`, Prudence refuses to start without a JWT issuer and requires
`PRUDENCE_DB_URL`, `PRUDENCE_DB_USER`, `PRUDENCE_DB_PASSWORD`, `PRUDENCE_ERNEST_API_KEY`,
SMTP (`SPRING_MAIL_HOST`) with both alert recipient lists (`PRUDENCE_ALERTS_GOVERNANCE_TO`,
`PRUDENCE_ALERTS_OPERATIONS_TO`), and an explicit `PRUDENCE_CORS_ORIGINS`; it uses schema validation, not automatic schema mutation.
Use PostgreSQL plus a migration tool in production.

Swap Keycloak for Azure Entra ID (or any OIDC IdP) with no code changes: set the issuer to
the Entra v2.0 endpoint, register the three apps, and model roles as **App Roles** so they
arrive in the `roles` claim the apps already read.

## Public MVP seed

`scripts/seed-mvp.sh` creates an idempotent dataset through the API: one system under
assessment, one pending review, one pending a second high-risk approval, one approved, one
rejected, one suspended by an incident and one prohibited/blocked. It requires three different
users (one `prudence-author` and two distinct `prudence-system-approver`), because Prudence enforces an independent second signer
for high-risk systems.

```bash
PRUDENCE_URL=https://prudence.ccmlab.cc \
SEED_AUTHOR_TOKEN='...' \
SEED_REVIEWER_ONE_TOKEN='...' \
SEED_REVIEWER_TWO_TOKEN='...' \
./scripts/seed-mvp.sh
```

The script neither accesses PostgreSQL directly nor stores passwords. The author token creates
the records and the demonstrative incident; the two reviewer tokens provide the independent
approvals. Re-running it leaves correctly seeded records unchanged and resumes a partially
completed run safely.

## Identity claims in the dev realm

`chestertown-realm.json` declares its own `clientScopes`, so on import Keycloak does **not**
create the standard `basic` / `profile` / `email` scopes. Each client therefore maps `sub`,
`email` and `preferred_username` explicitly. Without them a token names no one — and Prudence
rejects such tokens (401) rather than recording an anonymous reviewer.

## Register a runtime

`provision-runtime.py` gives an orchestrator its own Keycloak machine identity (`pdp.decide`,
`execution.report`, the `preferred_username` mapper) and grants it pipelines in
`policy/opa/data.json`. Against the local dev Keycloak:

```bash
KEYCLOAK_ADMIN_PASSWORD=admin scripts/provision-runtime.py --realm chestertown \
  --name my-runtime --pipeline my_pipeline
```

The full walk-through is [docs/integration/README.md](../docs/integration/README.md).
