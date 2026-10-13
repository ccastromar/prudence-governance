# Load test — Prudence + Ernest (2026-10-13)

Run with `loadtest/run-local.sh` on one laptop (10 cores, 16 GB). **Every component shares the
same host**, so absolute numbers are indicative; the shape is what matters. Ernest's external
integrations (EVM anchoring, webhooks, MLflow) were forced off.

## Results

| Path | Measured | Notes |
|---|---|---|
| PDP decision (registry lookup + OPA hop + outbox insert) | **~8,600/s**, p99 10.6 ms at 32 clients (2,100/s, p99 2.6 ms at 1 client) | 0 errors. Not the bottleneck. |
| Prudence outbox → Ernest, **direct** | **1.6/s** (default); **0.8/s** with a 100 ms poll | Capped by Ernest's throttle; retrying faster burns the quota on 429s. |
| Prudence outbox → Ernest, **ingestor** | **~148/s** | Limited by Prudence's single sequential delivery loop; 22 re-sends deduplicated by the ingestor. |
| Ernest `POST /api/inferences`, one client IP | **100 per minute**, then 429 | Hard-coded `ThrottlerModule` 100/60 s **per IP per endpoint**; 10 % of the accepted writes at 8 clients were 409 append races. |
| Chain append, raw (event-ingestor worker) | **~240 blocks/s**, flat from 9k to 49k blocks | Single serialized writer — the chain's ceiling on this host. |
| `GET /api/verify` | 0.95 s at 49k blocks, 1.17 s at 64k | Full re-scan every call (~19 µs/block). |
| `GET /api/models/{id}/integrity` | 3.8 s at 49k, 4.8 s at 64k | One query per block (~75 µs/block). |
| Prudence `GET /api/posture` (1 system) | 1.9 s at 49k, 2.1 s at 64k | Calls both of the above. |
| Ingestor accept | ~26,000/s | Then see the two ingestor defects below. |

## Defects found

1. **Prudence drift was blind beyond 20,000 blocks (fixed).** It pages `/api/blocks` 200 at a time;
   Ernest's throttle stops it at page 101 and the partial count was reported as a clean result.
   Drift now carries `complete=false` with the reason, the UI shows "scan incomplete", and a daily
   `DRIFT_SCAN_INCOMPLETE` alert goes to operations (V7).
2. **Ernest throttle (100/min per IP per endpoint, not configurable)** caps everything Prudence
   does against Ernest: evidence writes, posture reads (51 calls for 50 systems), drift paging.
3. **Ernest crashes when its anchoring provider fails.** With EVM anchoring configured, an Infura
   "Too Many Requests" reply is unhandled and kills the Node process.
4. **Ingestor drops accepted events.** `MAXLEN ~10000` trims unprocessed entries: with the worker
   running, only 10,345 of 15,587 accepted events reached the chain (34 % lost).
5. **Ingestor opens a new TCP connection to Redis per command.** At a few thousand events/s the host
   runs out of ephemeral ports and the ingestor answers 502.

## What it means at 100 governed tool calls/s (1,000 agents, one call per 10 s)

- PDP: ~1 % of measured capacity. Fine.
- Evidence: ~200 writes/s needed. Direct path delivers 1.6/s; ingestor path 148/s; the chain's
  raw ceiling is ~240/s. **The evidence plane, not Prudence's PDP, is the limit.**
- Reads: at ~17 M blocks/day, a full `verify` would take minutes after one day (extrapolated).

## Fix order

1. Ernest: configurable / per-client throttle (or exempt authenticated service clients); handle
   anchoring errors; reuse Redis connections; no trimming of unprocessed events.
2. Prudence: concurrent, draining delivery loop; stop calling O(N) endpoints per request
   (checkpointed health, incremental drift cursor).
3. Ernest: Merkle-batched blocks (one block — and one qualified seal — per batch).
