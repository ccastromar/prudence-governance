# Prudence

**The governance vertex of the Chestertown trio: Orchestrate (AOS) · Prove (Ernest) · Govern (Prudence).**

Prudence is the **control plane** for AI systems. Where [AOS](../aos-agentic-orchestration-system)
runs intent-driven pipelines under control and [Ernest](../ai-blockchain-provenance) proves
what ran (tamper-evident, independently verifiable), Prudence is where the rules are
**decided**, risk is **classified**, use is **approved**, and the whole estate's compliance
posture is **watched** and **attested**.

- **AOS executes** — orchestration-as-code, deterministic, with human gates and RBAC.
- **Ernest proves** — provenance, anchoring, qualified eIDAS seals/timestamps, offline receipts.
- **Prudence governs** — policy-as-code decisions, AI-system registry with risk tiers,
  approval workflows, continuous oversight, and signed regulatory attestations.

The three form a closed control loop: **decide → enforce → prove → oversee → decide.**

See [docs/adr/adr-000-prudence-the-governance-vertex.md](docs/adr/adr-000-prudence-the-governance-vertex.md)
for the founding architecture.

> Status: active implementation. The current vertical slice covers a classification dossier
> (role, Annex I/III, version/hash, geography, affected population, justification and
> obligations), policy decisions, review audit, approval expiry and periodic review, posture/drift,
> an AI incident register with AI Act Art. 73 deadlines, email alerts and attestations. It is a
> governance-readiness platform, not a legal declaration of EU AI Act conformity.

## Repository layout

| Path | What |
|---|---|
| `src/`, `ui/` | Spring Boot backend and React console |
| `policy/opa/` | OPA/Rego policy bundle (the authorization half of the PDP) |
| `integrations/` | Python adapters for agent runtimes (LangGraph, Pydantic AI) |
| `loadtest/` | Local load test of Prudence + Ernest |
| `scripts/` | Run everything locally without Docker: OPA, Keycloak, backend, console, seed data |
| `docs/adr/` | Architecture decisions |
| `docs/design/` | Shared design language (tokens) of the three apps |
| `docs/operations/` | Deployment and load-test results |
| `docs/functional/` | Manual functional-test guides (Spanish) — start with `00-arrancar.md` |

Run locally: see [docs/functional/00-arrancar.md](docs/functional/00-arrancar.md) (step by step) or
[scripts/README.md](scripts/README.md).
