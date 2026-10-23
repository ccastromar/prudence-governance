package prudence_managed_test

import rego.v1
import data.prudence_managed

metadata := {
	"version": "publication-1",
	"digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"rules": [
		{"role": "operator", "action": "pipeline.run", "pipeline": "credit", "effect": "ALLOW"},
		{"role": "blocked", "action": "pipeline.run", "pipeline": "credit", "effect": "DENY"},
	],
}

test_allow_returns_the_evaluated_publication if {
	result := prudence_managed.result with data.managed as metadata with input as {"roles": ["operator"], "action": "pipeline.run", "pipeline": "credit"}
	result.decision == "ALLOW"
	result.version == metadata.version
	result.digest == metadata.digest
}

test_deny_overrides_allow if {
	result := prudence_managed.result with data.managed as metadata with input as {"roles": ["operator", "blocked"], "action": "pipeline.run", "pipeline": "credit"}
	result.decision == "DENY"
}

test_unmatched_pipeline_is_denied if {
	result := prudence_managed.result with data.managed as metadata with input as {"roles": ["operator"], "action": "pipeline.run", "pipeline": "other"}
	result.decision == "DENY"
}

test_invalid_metadata_fails_closed if {
	result := prudence_managed.result with data.managed as {"version": "v1", "digest": "bad", "rules": metadata.rules} with input as {"roles": ["operator"], "action": "pipeline.run", "pipeline": "credit"}
	result.decision == "ERROR"
}

test_malformed_roles_fail_closed if {
	result := prudence_managed.result with data.managed as metadata with input as {"roles": "operator", "action": "pipeline.run", "pipeline": "credit"}
	result.decision == "ERROR"
}
