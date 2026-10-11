# ADR-000: Prudence — the governance vertex

**Status:** Founding design (proposed)
**Date:** 2026-10-10
**Deciders:** project maintainers
**Related:** AOS (agentic orchestration), Ernest (provenance/evidence,
`ai-blockchain-provenance/docs/regulatory-mapping.md`).

## Context

Two vertices exist and work:

- **AOS** — an intent-driven orchestration runtime: YAML pipelines, deterministic
  execution ("if it isn't declared, it can't run"), tools/agents, **human gates, RBAC,
  audit log**. The *enforcement/runtime* plane.
- **Ernest** — a tamper-evident evidence layer: hashchain, anchoring (OTS/EVM), qualified
  eIDAS seals/timestamps, offline-verifiable receipts, OIDC, CycloneDX AI/ML-BOM. The
  *evidence* plane.

What is missing is the *control* plane: where the rules are **decided**, AI use is
**risk-classified and approved**, and the estate's compliance posture is **monitored and
attested**. That is **Prudence**.

The naming follows a simple pattern (a human name whose meaning is the job): Ernest
(*earnest* — honest) + Prudence (*prudential* — the regulator's own word for oversight).

## Decision

### The central idea: PDP / PEP, as a closed control loop

- **Prudence = Policy Decision Point (PDP).** It holds the policies and answers: *"may actor
  X run pipeline P with model M in context C?"* → `allow | deny | approval-required | conditions`.
- **AOS = Policy Enforcement Point (PEP).** AOS already has the hook — its **human gates and
  intents**. A gate calls out to Prudence instead of embedding policy logic. AOS stays dumb
  and deterministic; policy lives outside, versioned.
- **Ernest = evidence of both.** It seals not only the *execution* but the *governance
  decision* (who approved what, under which policy version).

The loop: policy **decides** (Prudence → AOS), execution **runs** (AOS), it is **proved**
(Ernest), and Prudence **reads the evidence back** to compute posture and attest —
`decide → enforce → prove → oversee → decide`. Prudence never duplicates AOS's RBAC/gates
or Ernest's evidence; it orchestrates them.

### What Prudence does (scope)

1. **AI-system inventory + risk classification** — the spine. Every use case registered with
   an EU AI Act risk tier (prohibited/high/limited/minimal; Annex III mapping), intended
   purpose and owner. Every policy, approval and evidence stream attaches to it.
2. **Policy-as-code** — machine-readable rules: approved models, who runs what per tier, data
   residency, allowed intents/pipelines, required guardrails.
3. **PDP service** — the endpoint AOS gates consult.
4. **Approval & lifecycle workflows** — onboarding, change approval, periodic review, sign-offs
   (model owner / risk / DPO), DPIA & FRIA records.
5. **Continuous oversight** — ingests Ernest receipts + AOS events; audit-readiness scoring,
   policy-drift detection (unapproved model in use, missing evidence, expired approval),
   risk/incident register.
6. **Reporting & attestation** — AI Act Annex IV technical documentation, ISO 42001 records,
   NIST RMF profile, board/regulator dashboards — **sealed by Ernest**, so the governance
   reports are themselves tamper-evident. The loop closes.

### How it integrates (concretely, reusing what exists)

- **AOS hook**: a gate type `policy: prudence` that POSTs to the PDP and honours the verdict
  (allow/deny/approval). Minimal change — the gate already exists.
- **Ernest hook**: Prudence is a **consumer of receipts** (reuse Ernest's event/ingestor
  model) and an **emitter of signed attestations** (reuse the qualified-seal path).
- **One identity plane**: the OIDC already built in Ernest — AOS (RBAC), Ernest (caller
  identity) and Prudence (who approves/owns) share the same IdP.
- **The registry is the spine** every vertex references.

### With what (stack & standards)

- **Policy engine**: **OPA + Rego** (general, de-facto standard) or **AWS Cedar** (cleaner
  authz). Policy decisions are themselves sealed in Ernest.
- **Backend**: **Java Spring Boot 4.x on the latest JDK** — greenfield.
  *Scaffold built on Spring Boot 4.1.1 / JDK 24 (the
  latest installed; bump to Java 25 LTS when available).* A credible enterprise/banking choice for a compliance control plane. **Spring Security**
  gives OIDC/OAuth2 resource-server out of the box (better than a hand-rolled verifier);
  JPA for the registry, a state-machine/workflow for approvals, and `@Scheduled` for the
  oversight loop are all mature. The policy engine (OPA/Cedar) is language-agnostic (HTTP
  sidecar), so it does not tie the language. Integrating Ernest needs no code reuse —
  consuming receipts is plain HTTP/JSON, and attestations are *sealed by calling Ernest's
  API*. If Prudence verifies receipts locally, it adds a **Java verifier pinned to the same
  golden fixtures** as the Node/Go/Rust ones — a solved pattern that even strengthens the
  "verify in any language" story. (NestJS, to share Ernest's TS directly, is the alternative;
  it trades the banking-stack credibility and Spring Security for marginal code reuse.)
- **Standards to encode** (what makes it serious, not a CRUD):
  - **OSCAL** (NIST) for the controls catalogue, assessment results and system security plan —
    machine-readable compliance.
  - **EU AI Act** (Annex III tiers, Annex IV technical documentation), **ISO/IEC 42001** (AIMS),
    **NIST AI RMF**; for banking **DORA / EBA**. Ernest's `regulatory-mapping.md` is the seed;
    Prudence operationalizes it from a doc into living controls.
  - **CycloneDX AI/ML-BOM** (Ernest already exports it) as the model-documentation artifact.
- **Frontend**: **React** —
  registry, approvals, posture dashboard, attestations.

### First vertical slice (prove the Chestertown wired, not boil the ocean)

1. **Registry + risk classification** for one use case (e.g. a credit-risk scoring model).
2. **PDP (OPA/Cedar)** + **one AOS gate** that consults it for that pipeline.
3. **Posture view** that ingests Ernest's receipts for that model and shows green/red.

Demonstrates the whole loop: policy decides → AOS runs → Ernest proves → Prudence watches.

## Consequences

**Positive** — completes the Chestertown with zero overlap; a single control loop and one identity
plane across three planes; governance reports that are themselves tamper-evident.

**Negative / honest** — as with Ernest, **engineering is not the bottleneck**. The policy
engine is a weekend; the hard part is the *content* (real policies, risk classification,
approval workflows with real roles) and getting a regulated organization to adopt it. The
technical layer exists to give that conversation something to stand on.

## Not doing
- Duplicating AOS's RBAC/human-gate enforcement or Ernest's evidence — Prudence decides and
  oversees, it does not re-run or re-prove.
- Being a model registry/experiment tracker (that's beside MLflow, per Ernest) — Prudence's
  registry is the *governance* record (risk tier, owner, approvals), not an ML artifact store.
