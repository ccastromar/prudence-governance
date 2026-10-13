# Prudence OPA policy bundle

The authorization half of the PDP. Prudence's `OpaPolicyAdapter` calls OPA; this bundle
answers "may this subject perform this action on this pipeline?". Governance lifecycle
(registered? approved? high-risk?) is decided in Prudence's domain *before* OPA is asked.

- `prudence.rego` — the policy (`data.prudence.allow` → boolean).
- `data.json` — role bindings and role→permission data (swap for your directory/IdP groups).
- `prudence_test.rego` — unit tests.

## Run (dev)

```bash
# serve the bundle on 127.0.0.1:8181 (where the Spring adapter points by default);
# runs the policy tests first and reloads on edits
./scripts/opa.sh

# the exact call the adapter makes:
curl -s http://127.0.0.1:8181/v1/data/prudence/allow \
  -d '{"input":{"subject":"alice@bank.eu","pipeline":"credit-risk-scoring","aiSystemId":"sys-1","action":"run"}}'
# -> {"result":true}
```

## Test

```bash
opa test policy/opa/ -v
```

In production the policy is a signed OPA bundle served to the sidecar; `data.json` comes
from the enterprise directory, not a static file.
