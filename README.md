# Prudence

**The governance vertex of the Chestertown trio: Orchestrate (AOS) · Prove (Ernest) · Govern (Prudence).**

Prudence is the **control plane** for AI systems. Where [AOS](https://github.com/ccastromar/aos-agentic-orchestration-system)
runs intent-driven pipelines under control and [Ernest](https://github.com/ccastromar/ai-blockchain-provenance) proves
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
| `docs/integration/` | How to connect any orchestrator to Prudence: register it, ask, report, keep the evidence |
| `contract/governance/` | Governance functions v1: the contract AOS, Prudence and Ernest authorize by, with its default model, test vectors, organization templates and the `ct-model` tool |
| `contract/decision/` | Decision API v1 as test vectors: when a gate may run a step, what a runtime does with each report answer, what Prudence must answer. Prudence, AOS and prudence-core run them |
| `contract/ernest/` | What Prudence sends to Ernest and reads back, as interactions: Prudence's adapters and Ernest's backend both run them |
| `contract/decision-events/` | The Kafka record each PDP decision becomes on its way to Ernest's event-ingestor, and what Ernest makes of it |
| `docs/api/` | Published contracts: Decision API v1 (OpenAPI) and evidence events v1 (AsyncAPI) |
| `loadtest/` | Local load test of Prudence + Ernest |
| `scripts/` | Run everything locally without Docker: OPA, Keycloak, backend, console, seed data |
| `docs/adr/` | Architecture decisions |
| `ui/vendor/` | `@chestertown/design`, the shared design system (tokens and React components), as a pinned tarball released from the `chestertown-design` repository |
| `docs/operations/` | Load-test results |
| `docs/functional/` | Manual functional-test guides (Spanish) — start with `00-arrancar.md` |

**Connect your own orchestrator:** see [docs/integration/README.md](docs/integration/README.md).

**Which EU AI Act and ISO/IEC 42001 obligations it supports**, how and with what evidence, and
the gaps: [docs/compliance/traceability.md](docs/compliance/traceability.md).

Run locally: see [docs/functional/00-arrancar.md](docs/functional/00-arrancar.md) (step by step) or
[scripts/README.md](scripts/README.md).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Author

Developed and maintained by Carlos Castro Martos.

This project was developed independently. It is not sponsored, endorsed, or licensed by any employer, organization, or company.
