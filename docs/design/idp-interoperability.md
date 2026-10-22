# Identity provider interoperability test matrix

Status: planned; no provider has been validated by this document. Keycloak is the current demo provider.

- Keycloak: preserve the current demonstration and machine-to-machine flows.
- ZITADEL: a second full OIDC provider with users, applications, roles and service accounts; run locally with its supported Docker Compose stack. Verify access-token format, issuer, audience, roles and service credentials rather than assuming compatibility.
- Dex: lightweight local OIDC provider for reproducible discovery, login and key-handling scenarios. Verify whether each required machine flow is supported in the selected configuration; do not use it as a substitute for a complete enterprise IdP.
- Microsoft Entra ID: a real isolated development tenant, not a local emulator, is necessary for a compatibility claim. Verify app roles, access-token audiences and workload identities. Group overage and optional Graph lookup require an explicit implementation; never silently treat an overage as complete membership.
- Optional: Ory Hydra exercises a distinct OAuth/OIDC deployment. Configure JWT access tokens or implement and test token introspection for opaque tokens; the current Prudence backend is a JWT resource server.

Run the same acceptance suite for each provider: authorization code + PKCE and logout; issuer, audience, expiration and signature rejection; JWKS rotation; role mapping and missing or malformed claims; duplicate email across issuers; service-account scope enforcement; role removal and token lifetime; outage and recovery. Verify four-eyes identity consistency and migration before enabling issuer-subject principals. A provider is supported only when the suite passes against the real provider and version.

References: https://zitadel.com/docs/self-hosting/deploy/compose ; https://docs.docker.com/guides/dex/ ; https://learn.microsoft.com/en-us/entra/identity-platform/access-token-claims-reference ; https://www.ory.com/docs/oel/hydra/quickstart .

## Current configuration

`PRUDENCE_IDENTITY_ROLES_CLAIM` selects the top-level array claim (default `roles`). `PRUDENCE_IDENTITY_ROLE_MAPPING` accepts comma-separated `external=internal` entries, for example `Operator=read-write,Viewer=read-only`. When nonempty, only mapped roles become Spring authorities; an empty mapping retains legacy pass-through behavior. This mapping does not yet update OPA's separate `roles_of` grants. `PRUDENCE_IDENTITY_PRINCIPAL_MODE=issuer-subject` opts into stable issuer/subject identity; the default `legacy` must remain until stored approvals and OPA grants are migrated. Production requires `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` (or `OIDC_AUDIENCES` with this repository’s Compose file). Spring validates the access token audience against that configured list. Both user and workload access tokens must include an accepted audience. The IdP and every deployment must be configured accordingly before upgrading; provider-specific claims and Entra group overage handling still need implementation and interoperability testing.
