# loadtest

`loadgen` — a dependency-free Go load generator (`http` mode, plus a `fake-opa` stand-in) and
`run-local.sh`, which starts a throwaway local stack (PostgreSQL, MongoDB, Redis, Ernest backend
and event-ingestor, fake OPA, Prudence), runs the scenarios and writes `results.txt`.

```bash
ERNEST_REPO=/path/to/ai-blockchain-provenance ./loadtest/run-local.sh
```

Needs Go, PostgreSQL 16, MongoDB (+ mongosh), Redis and Ernest's built backend (`dist/`). Ernest's
external integrations are forced off; nothing leaves the machine. Takes ~20 minutes (it waits for
Ernest's per-minute throttle window between read measurements). Latest results:
[docs/operations/loadtest-results.md](../docs/operations/loadtest-results.md).
