# Chestertown dev IdP (Keycloak)

Local SSO for the Chestertown trio (AOS · Prudence · Ernest). Open-source, no Docker —
Keycloak standalone on your JDK. Test realm only; the passwords here are demo values.

## Run

```bash
# download Keycloak once (any recent 26.x), then from its dir:
cp path/to/prudence/dev/chestertown-realm.json data/import/
KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  bin/kc.sh start-dev --http-port 8081 --import-realm
```

Realm `chestertown` issuer: `http://localhost:8081/realms/chestertown`.
Clients (public, PKCE): `ernest-spa`, `prudence-spa`, `aos-spa`.
Machine-to-machine (confidential, client_credentials): `pdp-client` (secret `pdp-dev-secret`),
carrying the `pdp.decide` client scope — the credential a PDP caller (AOS gate, runtime
adapter) presents to Prudence's `/api/policy/decisions`.
Users: `alice`/`alice` (read-write), `bob`/`bob` (read-write), `auditor`/`auditor` (read-only).

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

## Point the apps at it (dev)

- Ernest frontend: `PUBLIC_OIDC_ISSUER=http://localhost:8081/realms/chestertown PUBLIC_OIDC_CLIENT_ID=ernest-spa`
- Ernest backend: `OIDC_ISSUER=…/realms/chestertown OIDC_AUDIENCE=ernest-spa OIDC_ROLE_CLAIM=roles OIDC_ROLE_MAP=read-write=read-write,read-only=read-only`
- Prudence backend: `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=…/realms/chestertown`
- Prudence UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=prudence-spa`
- AOS backend: `AOS_JWT_ISSUER=…/realms/chestertown AOS_JWK_URL=…/realms/chestertown/protocol/openid-connect/certs`
- AOS UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=aos-spa`

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
