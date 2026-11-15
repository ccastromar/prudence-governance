# Changelog

All notable changes to Prudence are documented here.

The format follows the spirit of Keep a Changelog, and Prudence uses semantic versioning.

## [0.1.0] - Unreleased

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
- Adapters for eighteen agent frameworks on one contract (`contract/adapter/`): twelve in Python
  (LangGraph, LangChain, CrewAI, OpenAI Agents, Claude Agent SDK and more) on a shared core, four
  in TypeScript on `@chestertown/prudence-core` (Vercel AI SDK, OpenAI Agents JS, Mastra and
  LangGraph.js) and two in Java on `cc.ccmlab.prudence:prudence-core` (Spring AI and
  LangChain4j). Each asks for a tool call as `tool:<name>` with the action `tools/call`, the grant
  the AI and MCP gateways use, unless the tool is mapped to a pipeline (then `run`), and refuses a
  tool with no AI system without asking. `run()` gives every decision of one agent run the same
  `correlationId`. The cores read `PRUDENCE_PDP_URL`, as AOS does, before `PRUDENCE_URL`.
- A conformance kit (`integrations/conformance-kit/`): any adapter, in any language, can show it
  follows the contract, with no Prudence and no model. The conformance run against a real Prudence
  and OPA covers the eighteen agents (72 cases), and another run adds real login, an MCP gateway
  and Ernest.
- `scripts/provision-runtime.py --tool <name>` grants `tools/call` on `tool:<name>`.
- An AI gateway as the policy enforcement point: a LiteLLM guardrail asks Prudence before each
  model call and tool call, with the agent's own token, lets a call through only on an ALLOW about
  that very agent, and reports the tools that ran. The twelve Python example agents also run
  behind it, with no adapter.
- Who asked, which agent acted and for which person: a gateway may speak only for the agents
  it is allowed to, each AI system declares its agents, and a high-risk system behind a gateway
  needs a verified agent. Decisions and evidence record all three.
- Governed framework demos, run from the console's Integrations tab, governed either by the
  framework adapter or by the AI gateway.
- Evidence: decisions and reviews are sent to Ernest, with people's identities pseudonymized.
- Governance functions: twelve fixed functions mapped to each organization's identity provider,
  with templates for a small organization, a bank and a public body.
- Sign-in with any OpenID Connect provider (tested with Keycloak, Dex and Entra ID).
- Web console.
- Traces, metrics, logs and service objectives shared with AOS and Ernest.
