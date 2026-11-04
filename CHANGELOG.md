# Changelog

All notable changes to Prudence are documented here.

The format follows the spirit of Keep a Changelog, and Prudence uses semantic versioning.

## [Unreleased]

First release, part of Chestertown 0.1.0 together with AOS and Ernest: Prudence is the reference
implementation of Decide and Oversee. It helps an organization get ready for AI governance; it is
not a legal declaration of EU AI Act conformity.

### Added

- AI system registry: each system's classification, risk tier and review cycle
  (submit, approve, reject, renew, suspend).
- Policy decisions: an API that answers ALLOW, DENY or APPROVAL_REQUIRED before an AI system
  acts. If it cannot decide, the answer is no.
- Policy management: drafts are tested, approved by a different person and published.
- Oversight and incidents: human oversight assignments, an incident register and drift detection.
  Drift flags runs that no ALLOW decision covers, and matches each execution a runtime reports
  with its evidence in Ernest, so a missing one is not hidden by a later run.
- Python adapters for twelve agent frameworks (LangGraph, LangChain, CrewAI, OpenAI Agents,
  Claude Agent SDK and more) on a shared core, each tested against a real agent.
- Governed framework demos, run from the console's Integrations tab.
- Evidence: decisions and reviews are sent to Ernest, with people's identities pseudonymized.
- Governance functions: twelve fixed functions mapped to each organization's identity provider,
  with templates for a small organization, a bank and a public body.
- Sign-in with any OpenID Connect provider (tested with Keycloak, Dex and Entra ID).
- Web console.
- Traces, metrics, logs and service objectives shared with AOS and Ernest.
