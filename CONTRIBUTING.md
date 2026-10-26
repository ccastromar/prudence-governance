# Contributing to Prudence

Thank you for helping. Prudence is the governance vertex of the Chestertown suite (AOS
orchestrates, Ernest proves, Prudence governs); a change here can change what the other two
see, so the contracts below matter as much as the code.

## Before you open a pull request

Run what CI runs, from the repository root:

```bash
mvn -B -ntp verify                      # backend: Java 25, tests and formatting (Spotless)
cd ui && npm ci && npm run format:check && npm run build && npm run test:e2e
python -m compileall -q -x '/\.venv/' integrations
python contract/governance/reference/check_vectors.py    # needs pyyaml
python contract/decision/reference/check_vectors.py
python -m unittest discover -s integrations/prudence-core/tests   # needs httpx
```

`mvn spotless:apply` and `npm run format` fix formatting. The console's browser tests also check
WCAG 2.1 AA with axe, in light and dark themes.

## Contracts with the other apps

Prudence owns three contracts the other apps run as tests:

| Directory | Who else runs it |
| --- | --- |
| `contract/governance/` | AOS, Ernest |
| `contract/decision/` (with `docs/api/decision-api-v1.yaml`) | AOS, `integrations/prudence-core` |
| `contract/ernest/` | Ernest (Prudence is the consumer there) |

Change the contract and its vectors in the same pull request as the code, then run that
directory's `sync.sh` and open the matching change in the other repositories. A change that
alters what an existing vector expects is a new contract version. `docs/compatibility.md`
lists every contract and what checks it.

## Style

- Small pull requests, one change each, with a message that says why.
- Tests with the change. Fail closed: when Prudence cannot decide, it must not allow.
- No secrets, real personal data or local paths in code, fixtures or docs.

## Conduct

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md); report problems to
conduct@ccmlab.cc.

## Security

Report vulnerabilities privately, as `SECURITY.md` describes, never in a public issue.

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0, the
license of this repository (section 5 of the license).
