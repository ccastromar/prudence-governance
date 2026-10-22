# Candidate evaluator for managed policy bundles. This module is not loaded by the
# current PDP. Runtime callers must provide independently authenticated roles;
# never trust roles supplied directly by an untrusted caller.
package prudence_managed

import rego.v1

# Missing metadata, malformed rules or missing inputs cannot grant access. Version
# and digest are returned in the same decision as the effect. The publisher must
# independently verify the canonical payload digest before activation.
metadata_valid if {
  is_string(data.managed.version)
  data.managed.version != ""
  is_string(data.managed.digest)
  regex.match(`^sha256:[0-9a-f]{64}$`, data.managed.digest)
  is_array(data.managed.rules)
  count(data.managed.rules) > 0
  count(data.managed.rules) <= 100
  every rule in data.managed.rules {
    valid_rule(rule)
  }
}

valid_rule(rule) if {
  is_object(rule)
  is_string(rule.role)
  rule.role != ""
  rule.action == "pipeline.run"
  is_string(rule.pipeline)
  rule.pipeline != ""
  rule.effect in {"ALLOW", "DENY"}
}

input_valid if {
  is_array(input.roles)
  every role in input.roles { is_string(role); role != "" }
  input.action == "pipeline.run"
  is_string(input.pipeline)
  input.pipeline != ""
}

matching_rules contains rule if {
  metadata_valid
  input_valid
  some rule in data.managed.rules
  rule.role in input.roles
  rule.action == input.action
  rule.pipeline == input.pipeline
}

explicit_deny if {
  some rule in matching_rules
  rule.effect == "DENY"
}

explicit_allow if {
  some rule in matching_rules
  rule.effect == "ALLOW"
}

default result := {"decision": "ERROR", "reason": "invalid policy metadata or input"}

result := {"decision": "DENY", "reason": "matching deny rule", "version": data.managed.version, "digest": data.managed.digest} if {
  metadata_valid
  input_valid
  explicit_deny
}

result := {"decision": "ALLOW", "reason": "matching allow rule", "version": data.managed.version, "digest": data.managed.digest} if {
  metadata_valid
  input_valid
  not explicit_deny
  explicit_allow
}

result := {"decision": "DENY", "reason": "no matching rule", "version": data.managed.version, "digest": data.managed.digest} if {
  metadata_valid
  input_valid
  not explicit_deny
  not explicit_allow
}
