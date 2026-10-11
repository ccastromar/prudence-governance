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
Users: `alice`/`alice` (read-write), `bob`/`bob` (read-write), `auditor`/`auditor` (read-only).

## Point the apps at it (dev)

- Ernest frontend: `PUBLIC_OIDC_ISSUER=http://localhost:8081/realms/chestertown PUBLIC_OIDC_CLIENT_ID=ernest-spa`
- Ernest backend: `OIDC_ISSUER=…/realms/chestertown OIDC_AUDIENCE=ernest-spa OIDC_ROLE_CLAIM=roles OIDC_ROLE_MAP=read-write=read-write,read-only=read-only`
- Prudence backend: `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=…/realms/chestertown`
- Prudence UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=prudence-spa`
- AOS backend: `AOS_JWT_ISSUER=…/realms/chestertown AOS_JWK_URL=…/realms/chestertown/protocol/openid-connect/certs`
- AOS UI: `VITE_OIDC_ISSUER=…/realms/chestertown VITE_OIDC_CLIENT_ID=aos-spa`

## Production

Swap Keycloak for Azure Entra ID (or any OIDC IdP) with no code changes: set the issuer to
the Entra v2.0 endpoint, register the three apps, and model roles as **App Roles** so they
arrive in the `roles` claim the apps already read.
