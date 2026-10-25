# Security Policy

Prudence is the governance control plane of Chestertown: AI system registry, policy decision
point, approvals and oversight. It is a governance-readiness platform, not a legal declaration of
EU AI Act conformity. What it guarantees, and what it does not: [docs/threat-model.md](docs/threat-model.md).

## Reporting a vulnerability

Please do not report vulnerabilities in public issues. Use GitHub's private vulnerability
reporting: the repository's **Security** tab → **Report a vulnerability**. Include the version or
commit, what an attacker can do and how to reproduce it; leave out real secrets and personal data.
Only the latest release receives fixes.

## Scope

Security-sensitive areas include:

- The policy decision point (`/api/policy/decisions`, AuthZEN): it must fail closed, and only
  workloads with `runtime.decide` may call it.
- Authorization by governance function and the rules of the governance model
  (`PRUDENCE_GOVERNANCE_MODEL`): independent approvals, incompatible functions, required
  accountabilities.
- Token validation: issuer, audience and signature (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_*`).
- The policy control plane: draft review by a different person, signed bundles to managed OPA.
- The evidence outbox to Ernest, and the database holding approvals and decisions.

## Deployment expectations

- Production refuses to start without an issuer, an audience, database credentials, an Ernest
  key and CORS origins; do not work around that with the development profile.
- Keep the governance model file read-only and under change control.
- Store database, Ernest, Kafka and bundle-signing secrets in a secrets manager.
- Validate your identity provider with `scripts/idp-acceptance.sh` before going live.

## Automated checks

Every push and pull request runs CodeQL static analysis and a secret scan of the whole history
(`.github/workflows/security.yml`); Dependabot proposes dependency updates weekly; release images
are scanned with Trivy and not published if a critical vulnerability with a fix is found.

## Verifying release images

Release tags publish images to GHCR, each signed with Sigstore cosign (keyless, by the release
workflow itself) and carrying an SBOM and build provenance. Check an image before you run it:

```bash
cosign verify ghcr.io/ccastromar/prudence-governance:0.1.0 \
  --certificate-identity-regexp '^https://github.com/ccastromar/prudence-governance/\.github/workflows/images\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
docker buildx imagetools inspect ghcr.io/ccastromar/prudence-governance:0.1.0 --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/ccastromar/prudence-governance:0.1.0 --format '{{ json .Provenance }}'
```
