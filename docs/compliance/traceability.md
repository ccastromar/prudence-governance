# Regulatory traceability · Chestertown 0.1.0

Which obligations of the **EU AI Act** (Regulation (EU) 2024/1689) and which controls of
**ISO/IEC 42001:2023** (Annex A) the suite supports, how, and with what evidence; and what it does
not cover. For an auditor, a DPO or a compliance team deciding what Chestertown takes off their
hands and what stays with them.

Chestertown is a governance-readiness platform, not a legal declaration of conformity. It helps
an organization **run, enforce and prove** its obligations; it does not perform the legal
analysis, write the technical documentation or carry out a conformity assessment for it.

## How to read it

| Mark | Meaning |
| --- | --- |
| **Enforced** | The suite makes it happen: a gate refuses, a deadline alerts, a rule blocks |
| **Recorded** | The suite holds the process and its evidence (status, owner, expiry, proof); the organization does the work |
| **Partial** | Part of the obligation is supported; the rest is named in the row |
| **Not covered** | Outside the suite today |

**Evidence** names where the proof lives:

- **Prudence**: its own records, PostgreSQL, audited.
- **Ernest**: tamper-evident and append-only, verifiable offline. It holds Prudence's decisions,
  reviews, incident and policy-administration records and attestations, and AOS's execution
  records. People appear only as keyed pseudonyms.
- **AOS**: its local hash-chained audit journal, anchored in Ernest.

## EU AI Act

### Scope and classification

| Article | Obligation | Support | How, in the suite | Evidence |
| --- | --- | --- | --- | --- |
| Art. 3, 16, 23, 24, 26 | Know your role for each system | Recorded | Registry dossier: regulatory role (provider, deployer, importer, distributor) per system; the role selects its compliance profile | Prudence registry; reviews in Ernest |
| Art. 5 | No prohibited practices | Enforced | A system classified `PROHIBITED` is `BLOCKED`; the PDP denies every run of it and AOS stops before any step | Decisions (DENY) in Ernest; AOS journal |
| Art. 6, Annex I, Annex III | Classify high-risk systems | Recorded | Dossier: Annex I / Annex III, intended purpose, affected population, geography, justification; risk tier | Registry; dossier versions in snapshots |
| Art. 6(3)–(4) | Document why an Annex III system is not high-risk | Partial | The dossier's justification holds the reasoning; there is no dedicated Art. 6(3) assessment or its registration (Art. 49(2)) | Registry |
| Art. 4 | AI literacy of staff | Partial | Training evidence and its expiry are recorded for named overseers only, not for all staff dealing with AI | Oversight assignments |

### High-risk systems: requirements (providers)

The provider profile (`EU_AI_ACT_PROVIDER_HIGH_RISK_V1`) opens one control per requirement. A
control is **Recorded**: it tracks the evidence the provider produces (status, owner, expiry,
failure), and a system cannot be submitted for review while its controls are not satisfied.

| Article | Requirement | Support | How, in the suite | Evidence |
| --- | --- | --- | --- | --- |
| Art. 9 | Risk management system | Recorded | Control `RISK_MANAGEMENT`; monitoring signals and CAPA feed it | Control evidence; signals; CAPA |
| Art. 10 | Data and data governance | Recorded | Control `DATA_GOVERNANCE`; Ernest can record dataset lineage (`dataset.linked` events) | Control evidence; Ernest provenance |
| Art. 11, Annex IV | Technical documentation | Partial | Control `TECHNICAL_DOCUMENTATION` tracks the dossier; compliance snapshots freeze the system's state. The suite does not produce the Annex IV document | Control evidence; snapshots |
| Art. 12 | Record-keeping (automatic logs) | Enforced (suite) / Recorded (system) | Every governed run is logged by AOS and decided by Prudence, both proved in Ernest. The system's own internal logging is control `LOGGING_TRACEABILITY` | AOS journal; Ernest |
| Art. 13 | Transparency and instructions for use | Recorded | Instructions are part of control `TECHNICAL_DOCUMENTATION`; the deployer's acceptance is `INSTRUCTIONS_OF_USE` | Control evidence |
| Art. 14 | Human oversight | Enforced (suite) / Recorded (design) | Named supervisors with override and stop authority and current training. AOS human-approval steps. **Suspend** in Prudence stops every run through the PDP (14(4)(e)). Overrides are logged and reviewed. Oversight design is control `HUMAN_OVERSIGHT` | Oversight assignments; overrides; decisions in Ernest |
| Art. 15 | Accuracy, robustness, cybersecurity | Recorded | Control `ROBUSTNESS_SECURITY`; `PERFORMANCE_DEGRADATION` monitoring signal | Control evidence; signals |
| Art. 17 | Quality management system | Partial | Control `QUALITY_MANAGEMENT`; policies are changed through drafts, validation, simulation and four-eyes approval, published with an audit trail | Control evidence; policy administration in Ernest |
| Art. 18 | Keep documentation for 10 years | Partial | Snapshots and Ernest records are immutable and kept; there is no retention schedule or 10-year archive policy | Snapshots; Ernest |
| Art. 19, 26(6) | Keep automatically generated logs (≥ 6 months) | Enforced | Ernest is append-only and keeps decision and execution evidence with no expiry | Ernest |
| Art. 20 | Corrective actions | Recorded | CAPA (open → implemented → effectiveness verified → closed) per system | CAPA |
| Art. 43, 47, 48 | Conformity assessment, declaration, CE marking | Recorded | Control `CONFORMITY_ASSESSMENT` tracks the evidence; the assessment itself is outside the suite | Control evidence |
| Art. 49, 71 | Registration in the EU database | Not covered | No registration record or export to the EU database | — |
| Art. 72 | Post-market monitoring | Recorded | Control `POST_MARKET_MONITORING`; monitoring signals (incident, override and complaint rates, performance degradation) with thresholds; posture | Signals; posture; Ernest |

### High-risk systems: deployers

The deployer profile (`EU_AI_ACT_DEPLOYER_HIGH_RISK_V1`) opens these controls. Beyond the
controls, AOS and Prudence enforce what the deployer runs.

| Article | Obligation | Support | How, in the suite | Evidence |
| --- | --- | --- | --- | --- |
| Art. 26(1) | Use in accordance with the instructions | Enforced (use) / Recorded (instructions) | Only approved systems run, only through granted pipelines (PDP); approvals expire and need renewal. Control `INSTRUCTIONS_OF_USE` | Decisions; execution reports; Ernest |
| Art. 26(2) | Assign oversight to competent people | Enforced | The accountabilities the governance model requires (owner, supervisor, compliance, DPO, security) must be assigned before review; named supervisors with training and authority. Control `HUMAN_OVERSIGHT_ASSIGNMENT`. Separation of duties: whoever declares a system cannot approve it | Oversight; reviews in Ernest |
| Art. 26(4) | Relevant, representative input data | Recorded | Control `INPUT_DATA_RELEVANCE` | Control evidence |
| Art. 26(5) | Monitor, suspend, inform the provider | Enforced (suspend, drift) / Recorded (rest) | Monitoring signals; **drift** flags runs of systems that are not approved; runtime reports are compared with Ernest's evidence; suspension stops runs. Control `OPERATION_MONITORING` | Drift alerts; signals; Ernest |
| Art. 26(5), 73 | Report serious incidents | Enforced (deadlines) | Incident register by category with the Art. 73 deadline (15 days; 10 for a death; 2 for widespread infringement or critical infrastructure), an alert 24 hours before it falls due and again when overdue, initial and final report status. Control `INCIDENT_ESCALATION` | Incidents in Ernest |
| Art. 26(7) | Inform workers' representatives | Not covered | — | — |
| Art. 26(8), 49(3) | Public-body deployers register their use | Not covered | — | — |
| Art. 26(11) | Inform people subject to decisions | Not covered | — | — |
| Art. 27 | Fundamental rights impact assessment | Recorded | FRIA per system: jurisdiction, affected groups, assumptions, mitigations, signature, linked DPIA (URI and SHA-256, Art. 27(4)). Notifying the market surveillance authority (27(3)) is not covered | FRIA records |
| Art. 86 | Right to an explanation | Not covered | — | — |

### Importers and distributors

The importer and distributor profiles open controls for Art. 23 (conformity check,
identification, storage, document retention, risk response) and Art. 24 (verification,
storage, withholding non-compliant systems). All **Recorded**.

### Transparency (limited risk)

| Article | Obligation | Support | How | Evidence |
| --- | --- | --- | --- | --- |
| Art. 50(1) | Tell people they are interacting with AI | Recorded | Transparency notice `DIRECT_AI_INTERACTION` | Notices |
| Art. 50(2) | Mark synthetic content | Not covered | No notice kind or control for machine-readable marking | — |
| Art. 50(3) | Emotion recognition, biometric categorisation | Recorded | Notices `EMOTION_RECOGNITION`, `BIOMETRIC_CATEGORISATION` | Notices |
| Art. 50(4) | Deep fakes | Recorded | Notice `DEEPFAKE` | Notices |

### Changes

| Article | Obligation | Support | How | Evidence |
| --- | --- | --- | --- | --- |
| Art. 3(23), 25(1)(b), 43(4) | Substantial modification | Enforced | A change assessment classifies a change as minor, needing control review, or substantial; a substantial (or prohibited) change suspends the system, so it cannot run until it is approved again | Change assessments; reviews |

## ISO/IEC 42001:2023, Annex A

Control numbers as in Annex A of the 2023 edition. The standard is not freely available: check
the numbering against your licensed copy before relying on it in an audit.

| Control | Topic | Support | How, in the suite |
| --- | --- | --- | --- |
| A.2 | AI policy | Partial | Policies as code: drafted, validated, simulated, approved by someone other than their authors, published and enforced by the PDP. The organization's AI policy document itself is its own |
| A.3.2 | Roles and responsibilities | Enforced | Governance functions and the governance model; per-system accountabilities; separation of duties |
| A.3.3 | Reporting of concerns | Partial | The incident register; there is no anonymous reporting channel |
| A.4 | Resources | Not covered | — |
| A.5.2–A.5.5 | AI system impact assessment | Recorded | FRIA (affected groups, mitigations), linked DPIA; impact requirements per system |
| A.6.1 | Responsible development objectives and processes | Partial | Provider controls and the review workflow |
| A.6.2.2–A.6.2.4 | Requirements, design, verification and validation | Recorded | Provider controls (`TECHNICAL_DOCUMENTATION`, `ROBUSTNESS_SECURITY`) |
| A.6.2.5 | Deployment | Enforced | Approval before use; the PDP gate; approvals expire |
| A.6.2.6 | Operation and monitoring | Enforced | Monitoring signals, drift, posture, alerts |
| A.6.2.7 | Technical documentation | Partial | As Art. 11 |
| A.6.2.8 | Event logs | Enforced | AOS journal, Prudence decisions, Ernest |
| A.7 | Data for AI systems | Recorded | Control `DATA_GOVERNANCE`; dataset lineage in Ernest |
| A.8.2 | Documentation for users | Recorded | As Art. 13 |
| A.8.3–A.8.4 | External reporting, communication of incidents | Partial | Incident deadlines and reports; attestations for third parties |
| A.8.5 | Information for interested parties | Partial | Signed attestations; transparency notices |
| A.9 | Use of AI systems | Enforced | Use only of approved systems, through granted pipelines (`ai.use:<domain>`), within their approval |
| A.10 | Third-party relationships | Partial | Regulatory role per system; importer and distributor controls; provider instructions accepted by the deployer |

## Gaps to close

In order of how often an adopter will ask:

1. **Art. 26(11) and 86**: informing people subject to a decision, and explaining it.
2. **Art. 49 and 71**: registration in the EU database (providers; public-body deployers).
3. **Art. 50(2)**: marking synthetic content.
4. **Art. 27(3)**: notifying the FRIA's results to the market surveillance authority.
5. **Art. 4**: AI literacy beyond named overseers.
6. **Art. 26(7)**: informing workers' representatives.
7. **Art. 18**: a retention schedule for documentation (10 years), reconciled with data-protection
   retention for personal data.

## Keeping it true

A row changes when the code does: the compliance profiles live in
`compliance/application/service/ComplianceProfileCatalog.java`, the incident deadlines in
`incident/domain/Incident.java`, and the transparency kinds in `impact/TransparencyKind.java`.
Review this matrix with every release, next to `docs/compatibility.md`.
