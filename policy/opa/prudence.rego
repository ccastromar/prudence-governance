# Prudence governance policy (PDP).
#
# Contract with the Spring OpaPolicyAdapter: it POSTs to /v1/data/prudence/allow with
#   {"input": {"subject", "caller", "agent", "agentAssurance", "onBehalfOf"?,
#              "pipeline", "aiSystemId", "action"}}
# and reads {"result": <boolean>}. `allow` is that boolean. `subject` is the agent that will
# act; `caller` is who asked (contract/identity/). Before deciding about an agent named by
# another caller, the adapter asks /v1/data/prudence/may_speak_for with {"caller", "agent"}.
#
# This is only the *authorization* half of a decision. Governance facts that live in
# Prudence's own domain — is the AI system registered, approved, high-risk? — are decided
# in PolicyDecisionService BEFORE OPA is consulted. Keep policy here about who-may-do-what;
# keep lifecycle/approval state in the registry. (OPA 1.0 / Rego v1 syntax.)
package prudence

import rego.v1

default allow := false

# A subject is allowed when one of its roles grants the requested action on the pipeline,
# and whoever asked may ask for it.
allow if {
	acting_for_subject
	person_requirement_met
	some role in data.prudence.roles_of[input.subject]
	some perm in data.prudence.role_permissions[role]
	perm.pipeline == input.pipeline
	input.action in perm.actions
}

# `speaks_for` maps a caller (an AI gateway, an MCP gateway) to the agents it may ask about.
default may_speak_for := false

may_speak_for if {
	input.agent in data.prudence.speaks_for[input.caller]
}

# The caller asks for itself, or for an agent it may speak for. An input without `caller`
# comes from an adapter that predates contract/identity/: the subject asked for itself.
acting_for_subject if not input.caller

acting_for_subject if input.caller == input.subject

acting_for_subject if {
	input.caller != input.subject
	input.subject in data.prudence.speaks_for[input.caller]
}

# `person_requires` maps a pipeline to the governance function the person an agent acts for
# must hold (e.g. "ai.use:finance"). Only a verified person counts: one the request merely
# names never satisfies it.
person_requirement_met if not data.prudence.person_requires[input.pipeline]

person_requirement_met if {
	required := data.prudence.person_requires[input.pipeline]
	input.onBehalfOf.assurance == "verified"
	required in input.onBehalfOf.functions
}

# Asked by the adapter only after a denial, to name it (reasonCode person_function_required).
default person_function_missing := false

person_function_missing if not person_requirement_met

# Human-readable reasons for denials (handy when debugging / for a richer response later;
# the adapter currently reads only `allow`).
reasons contains "subject has no roles" if {
	not data.prudence.roles_of[input.subject]
}

reasons contains sprintf("caller %q may not speak for %q", [input.caller, input.subject]) if {
	not acting_for_subject
}

reasons contains sprintf("no role grants action %q on pipeline %q", [input.action, input.pipeline]) if {
	data.prudence.roles_of[input.subject]
	not allow
}
