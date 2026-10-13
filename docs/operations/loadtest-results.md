# Load test — Prudence + Ernest (2026-10-13)

Two runs: **before** and **after "phase 0"** (Prudence `cb335c3…` → current; Ernest `40ec302`).
The "after" table comes first.

## Bulk write path (Ernest batch endpoint + ingestor worker in bulk, 2026-10-13)

Batching on. The batch endpoint and the ingestor worker now store a whole request/read in a few
round trips (one `insertMany`, model admission once per model, one ack) instead of several per
event. Same host, same scripts.

| Path | Batching on | Bulk write path |
|---|---|---|
| Ernest `POST /api/inferences/batch`, 1 process (isolated bench) | 3,360/s | **~15,300/s** (×4.5) |
| Ernest batch endpoint inside the full load test | 3,360/s | **11,867/s** |
| Ingestor worker, 100k queued events (isolated bench) | 2,793/s | **9,091/s** (×3.3), all chained 1 s later |
| Prudence outbox → Ernest, direct | 1,525/s | **2,998/s** (×2) |
| Prudence outbox → Ernest, ingestor | 2,249/s | 2,327/s (limited by Prudence: one event per request) |
| Chainer (batcher), sustained | — | ~11,000–13,000 events/s; `BATCH_MAX_EVENTS` 1,000 → 10,000 changes little |
| Ernest log volume | 96 MB | 4 MB |

Reading: Ernest's write side is no longer the limit for Prudence. What caps Prudence → Ernest now
is Prudence's own sender (8 senders; one event per request on the ingestor path). Extra
`ERNEST_ROLE=writer` replicas on this one host do not add throughput (they share its 10 cores
with MongoDB and the chainer); horizontal gains need separate hosts. Per-model integrity reads
grow with events (~5 s for one model with ~600k events) — the next read-side item.

## With ADR-004 batching (`EVIDENCE_BATCHING=on`, 2026-10-13)

Same host and script (`EVIDENCE_BATCHING=on ./loadtest/run-local.sh`), Ernest with the Merkle
batcher and a single backend process holding all roles. Throughput counts *chained events*.

| Path | After phase 0 | Batching on |
|---|---|---|
| PDP decision | ~8,800/s | ~8,600/s (unchanged) |
| Prudence outbox → Ernest, direct | 1,418/s | **1,525/s** |
| Prudence outbox → Ernest, ingestor | 2,110/s | **2,249/s** |
| Ernest `POST /api/inferences/batch` | 2,804/s | **3,360/s** |
| Ingestor worker | ~1,300/s | ~1,330/s |
| **Chain size for 350k events** | 350k blocks | **417 blocks** (~840 events per block) |
| `verify` incremental / `mode=full` | 0.002–0.2 s / 5.3 s at 318k | 0.003–0.24 s / 6.7 s at 350k |
| Prudence posture / drift | 0.18–0.26 s / 0.11 s | 0.16–0.23 s / 0.06 s |
| Ingestor, worker stopped | 0 lost | 0 lost (10,013 accepted = 10,013 chained) |
| Ernest log volume | 240 MB | 96 MB |

Reading: batching changes the *shape* — chain-wide work (anchoring, audit bundles, prune) now
scales with ~400 blocks instead of 350k — but write throughput moved only ~10–20 %. The ceiling
is no longer the serialized append; it is one Node process admitting events (validation,
canonicalization, dedup insert). That is what the role split (`ERNEST_ROLE=writer`, N
replicas) addresses; the ADR-004 target of ≥ 5,000 events/s is not reached by one process.
Full verification still re-derives every leaf, so it stays linear in events.


Run with `loadtest/run-local.sh` on one laptop (10 cores, 16 GB). **Every component shares the
same host**, so absolute numbers are indicative; the shape is what matters. Ernest's external
integrations (EVM anchoring, webhooks, MLflow) were forced off.

## After phase 0

| Path | Before | After |
|---|---|---|
| PDP decision | ~8,600/s | ~8,800/s (unchanged — never the bottleneck) |
| Prudence outbox → Ernest, direct | 1.6/s | **1,418/s** (batches of 200, 8 concurrent senders) |
| Prudence outbox → Ernest, ingestor | 148/s | **2,110/s** |
| Ernest append via `POST /api/inferences/batch` | — | **2,804 blocks/s** |
| Ernest append via ingestor worker | ~240/s | **~1,300/s** |
| `GET /api/verify` | full re-scan every call (1.17 s at 64k) | incremental: 0.002–0.2 s; `?mode=full` 5.3 s at 318k, on demand |
| Model integrity | 4.8 s at 64k (scan + 1 query/block) | 0.76–1.05 s for ~70k blocks of that model |
| Drift check | ~1 s and **incomplete** past 20k blocks | **0.11 s**, complete, at 318k blocks (`/api/stats/executions`) |
| Ingestor with worker stopped | **34 % of accepted events lost** | 10,008 accepted, 9,992 refused with 503 + `Retry-After`, **0 lost** |
| Ernest log volume | 2.9 GB in 20 min | 240 MB |

Why the chain itself got faster: every append ran `hasRegistrationBlock`, a query on
`data.modelId` with no index (a collection scan per write), and the ingestor dialed Redis for
every command. Both were fixed alongside the throttle.

Still open:
- ~~Posture 3 s~~ **fixed**: posture now asks Ernest for evidence counts of all approved systems in
  one indexed call (`GET /api/stats/models`); chain integrity comes from the incremental verify.
  Measured on the same 318k-block chain (237k blocks for the one system): **0.18–0.26 s**
  (per-model integrity for that system alone took 5.2 s).
- Single `POST /api/inferences` is capped by design at `THROTTLE_AUTHENTICATED_LIMIT`
  (6,000/min per credential per route); batch submissions are the high-volume path.
- Append races under concurrency (409, retried) — 12 % of single writes at 8 clients.
- The serialized chain remains the structural ceiling; Merkle-batched blocks (phase 1) lift it.

## Before phase 0

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
