# ADR-000: Prudence — the governance vertex

**Status:** Accepted (founding design)
**Date:** 2026-10-10
**Related:** AOS (orchestration), Ernest (evidence).

## Context

AOS runs governed pipelines (the enforcement plane) and Ernest keeps tamper-evident evidence
(the evidence plane). What is missing is the control plane: where AI use is classified and
approved, where policy is decided, and where the estate's posture is overseen.

## Decision

Prudence is the **Policy Decision Point**; AOS is the **Policy Enforcement Point**; Ernest
records the evidence of both. Together they form one loop:
`decide → enforce → prove → oversee → decide`.

Prudence owns:

1. **The AI system registry**, the spine every policy, approval and piece of evidence refers to:
   EU AI Act risk tier, intended purpose and owner.
2. **Policy as code**, evaluated by OPA/Rego and versioned.
3. **The decision API** AOS asks before a governed step: allow, deny or approval required.
4. **Approval and review workflows**, with impact assessments.
5. **Oversight**: posture, drift (an unapproved system in use, an expired approval), incidents.
6. **Attestations**, sealed by Ernest so the governance record is itself tamper-evident.

Integration reuses what exists: an AOS gate that asks Prudence and obeys; evidence delivered
to Ernest; one identity provider for the three applications.

Stack: Spring Boot on the current LTS JDK (with Spring Security as an OAuth 2.0 resource
server), OPA as the policy engine, and a React console.

## Consequences

- Completes the suite without overlap: Prudence decides and oversees; it never re-runs AOS's
  enforcement or re-proves Ernest's evidence.
- Engineering is not the hard part. Real policies, risk classification and approval roles are,
  and the software exists to give that work a structure.

## Not doing

- Duplicating AOS's gates or Ernest's evidence.
- Being a model registry or experiment tracker: Prudence's registry is the governance record.
