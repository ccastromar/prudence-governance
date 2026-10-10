# Prudence governance policy (PDP).
#
# Contract with the Spring OpaPolicyAdapter: it POSTs to /v1/data/prudence/allow with
#   {"input": {"subject", "pipeline", "aiSystemId", "action"}}
# and reads {"result": <boolean>}. `allow` is that boolean.
#
# This is only the *authorization* half of a decision. Governance facts that live in
# Prudence's own domain — is the AI system registered, approved, high-risk? — are decided
# in PolicyDecisionService BEFORE OPA is consulted. Keep policy here about who-may-do-what;
# keep lifecycle/approval state in the registry. (OPA 1.0 / Rego v1 syntax.)
package prudence

import rego.v1

default allow := false

# A subject is allowed when one of its roles grants the requested action on the pipeline.
allow if {
	some role in data.prudence.roles_of[input.subject]
	some perm in data.prudence.role_permissions[role]
	perm.pipeline == input.pipeline
	input.action in perm.actions
}

# Human-readable reasons for denials (handy when debugging / for a richer response later;
# the adapter currently reads only `allow`).
reasons contains "subject has no roles" if {
	not data.prudence.roles_of[input.subject]
}

reasons contains sprintf("no role grants action %q on pipeline %q", [input.action, input.pipeline]) if {
	data.prudence.roles_of[input.subject]
	not allow
}
