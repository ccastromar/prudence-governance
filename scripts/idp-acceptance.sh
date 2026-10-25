#!/usr/bin/env bash
# Acceptance checks for an identity provider: does Prudence, with a given governance model, give
# each token exactly the functions it should? The same checks run against Keycloak, Dex or a real
# Entra ID tenant; only the tokens change. This script never signs anyone in: you obtain the tokens
# (see docs/design/idp-interoperability.md) and pass them in the environment.
#
#   PRUDENCE_URL=http://localhost:8099 \
#   TOKEN_APPROVER=… TOKEN_AUDITOR=… TOKEN_DECLARER=… TOKEN_DECIDE=… TOKEN_REPORT=… \
#   [TOKEN_OTHER_AUDIENCE=…] ./scripts/idp-acceptance.sh
#
# TOKEN_APPROVER holds only system.approve, TOKEN_AUDITOR only audit and TOKEN_DECLARER
# system.declare (people); TOKEN_DECIDE runtime.decide and TOKEN_REPORT runtime.report (workloads).
# TOKEN_OTHER_AUDIENCE, if given, is a valid token of the same issuer for another audience.
# Prudence must already run against the provider with the model under test. Exit 1 if a check fails.
set -uo pipefail

P="${PRUDENCE_URL:-http://localhost:8099}"
for v in TOKEN_APPROVER TOKEN_AUDITOR TOKEN_DECLARER TOKEN_DECIDE TOKEN_REPORT; do
  [ -n "${!v:-}" ] || { echo "$v is not set" >&2; exit 2; }
done

pass=0 fail=0
check() {  # check NAME EXPECTED ACTUAL
  if [ "$2" = "$3" ]; then pass=$((pass + 1)); printf 'PASS  %-62s %s\n' "$1" "$3"
  else fail=$((fail + 1)); printf 'FAIL  %-62s got %s, want %s\n' "$1" "$3" "$2"; fi
}
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
bearer() { printf 'Authorization: Bearer %s' "$1"; }
functions() {
  curl -s -H "$(bearer "$1")" "$P/api/me" |
    python3 -c 'import json,sys; print(",".join(json.load(sys.stdin).get("functions", [])))' 2>/dev/null
}
json='Content-Type: application/json'
unknown='{"subject":"acceptance","pipeline":"acceptance","aiSystemId":"not-registered-acceptance","action":"invoke"}'

echo "Prudence $P"
check "no token is refused" 401 "$(code "$P/api/me")"
check "approver resolves to system.approve" system.approve "$(functions "$TOKEN_APPROVER")"
check "auditor resolves to audit" audit "$(functions "$TOKEN_AUDITOR")"
check "declarer holds system.declare" yes \
  "$(functions "$TOKEN_DECLARER" | tr , '\n' | grep -qx system.declare && echo yes || echo no)"
check "auditor cannot approve" 403 "$(code -X POST -H "$(bearer "$TOKEN_AUDITOR")" "$P/api/registry/systems/x/approve")"
check "approver may approve (unknown system: 404)" 404 \
  "$(code -X POST -H "$(bearer "$TOKEN_APPROVER")" "$P/api/registry/systems/x/approve")"
check "approver cannot register a system" 403 \
  "$(code -X POST -H "$(bearer "$TOKEN_APPROVER")" -H "$json" -d '{}' "$P/api/registry/systems")"
check "a person cannot call the PDP" 403 \
  "$(code -X POST -H "$(bearer "$TOKEN_APPROVER")" -H "$json" -d '{}' "$P/api/policy/decisions")"
check "decide workload reaches the PDP (bad body: 400)" 400 \
  "$(code -X POST -H "$(bearer "$TOKEN_DECIDE")" -H "$json" -d '{}' "$P/api/policy/decisions")"
check "decide workload: unregistered system is DENY" "403 DENY" \
  "$(curl -s -w ' %{http_code}' -X POST -H "$(bearer "$TOKEN_DECIDE")" -H "$json" -d "$unknown" \
    "$P/api/policy/decisions" | python3 -c 'import json,sys
body, _, status = sys.stdin.read().rpartition(" ")
try: decision = json.loads(body).get("decision")
except ValueError: decision = None
print(status, decision)')"
check "report workload cannot call the PDP" 403 \
  "$(code -X POST -H "$(bearer "$TOKEN_REPORT")" -H "$json" -d '{}' "$P/api/policy/decisions")"
check "report workload reaches executions (bad body: 400)" 400 \
  "$(code -X POST -H "$(bearer "$TOKEN_REPORT")" -H "$json" -d '{}' "$P/api/executions")"
check "tampered signature is refused" 401 "$(code -H "$(bearer "${TOKEN_AUDITOR%?????}AAAAA")" "$P/api/me")"
if [ -n "${TOKEN_OTHER_AUDIENCE:-}" ]; then
  check "token for another audience is refused" 401 "$(code -H "$(bearer "$TOKEN_OTHER_AUDIENCE")" "$P/api/me")"
fi

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
