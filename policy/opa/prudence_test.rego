package prudence

import rego.v1

# Operator may run the credit-risk pipeline.
test_operator_can_run if {
	allow with input as {"subject": "alice@bank.eu", "pipeline": "credit-risk-scoring", "aiSystemId": "sys-1", "action": "run"}
}

# The private framework demo runner may execute exactly its two visible demonstrations.
test_framework_demo_runner_can_run_bug_triage if {
	allow with input as {"subject": "service-account-prudence-demo-runner", "pipeline": "pipeline_bug_triage", "aiSystemId": "sys-1", "action": "run"}
}

test_framework_demo_runner_cannot_run_other_pipeline if {
	not allow with input as {"subject": "service-account-prudence-demo-runner", "pipeline": "credit-risk-scoring", "aiSystemId": "sys-1", "action": "run"}
}

# Operator may NOT approve (only run).
test_operator_cannot_approve if {
	not allow with input as {"subject": "alice@bank.eu", "pipeline": "credit-risk-scoring", "aiSystemId": "sys-1", "action": "approve"}
}

# Risk officer may approve.
test_officer_can_approve if {
	allow with input as {"subject": "bob@bank.eu", "pipeline": "credit-risk-scoring", "aiSystemId": "sys-1", "action": "approve"}
}

# Unknown subject is denied (default false / fail-closed).
test_unknown_subject_denied if {
	not allow with input as {"subject": "mallory@evil.test", "pipeline": "credit-risk-scoring", "aiSystemId": "sys-1", "action": "run"}
}

# Known subject, undeclared pipeline is denied.
test_undeclared_pipeline_denied if {
	not allow with input as {"subject": "alice@bank.eu", "pipeline": "loan-underwriting", "aiSystemId": "sys-2", "action": "run"}
}
