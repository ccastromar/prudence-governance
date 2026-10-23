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

`PRUDENCE_IDENTITY_ROLES_CLAIM` selects the top-level array claim (default `roles`). `PRUDENCE_IDENTITY_ROLE_MAPPING` accepts comma-separated `external=internal` entries, for example `Operator=author,Viewer=reader`. When nonempty, only mapped roles become Spring authorities; an empty mapping retains legacy pass-through behavior. This mapping does not yet update OPA's separate `roles_of` grants. `PRUDENCE_IDENTITY_PRINCIPAL_MODE=issuer-subject` opts into stable issuer/subject identity; the default `legacy` must remain until stored approvals and OPA grants are migrated. `PRUDENCE_IDENTITY_POLICY_ACTOR_MODE` (default: the principal mode) switches only the policy control plane to `<issuer length>:<issuer><subject>` actors, which managed mode requires; the registry and OPA grants keep legacy names. Approval is refused (409) for revisions with any actor recorded in the legacy format, so such drafts must be re-created; it is also refused for the author's own identity. `PRUDENCE_MANAGED_OPA_CA_FILE` adds a PEM CA for the OPA HTTPS client. `GET /api/policy-admin/status` reports mode, roles and the required publication; `POST /api/policy-admin/publications/probe` checks OPA on demand and records an observation only when the id and digest match. Production requires `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` (or `OIDC_AUDIENCES` with this repository’s Compose file). Spring validates the access token audience against that configured list. Both user and workload access tokens must include an accepted audience. The IdP and every deployment must be configured accordingly before upgrading; provider-specific claims and Entra group overage handling still need implementation and interoperability testing.

## Prudence roles

Prudence's own roles are an application contract, independent of any identity provider; a deployment maps its IdP's claim values onto them.

| Role | Allows |
|---|---|
| `reader` | Read registers, evidence, incidents and policies' status. |
| `author` | Register and edit systems, evidence, incidents, cases and monitoring; draft, edit, validate and simulate policies. |
| `system-approver` | Approve, reject and renew AI systems and issue attestations. A high-risk system needs two different approvers. |
| `policy-reviewer` | Approve a validated policy draft; never its author or editor. |
| `admin` | Everything `author` can do, plus publishing and rolling back policies. It does not grant `system-approver` or `policy-reviewer`. |

Any request other than machine calls (`pdp.decide`, `execution.report` scopes) requires at least one of these roles. `GET /api/me` returns the caller's roles after mapping; the console reads permissions from it rather than from the token, so the IdP's claim layout never reaches the browser. With no mapping configured, claim values are used verbatim, so they must already be these names. Configure the mapping in every non-local deployment.
