"""Ernest evidence client.

Emits tamper-evident provenance to Ernest: a *model* block per governed agent, an
*inference* block per governed step. Only SHA-256 hashes of inputs/outputs are sent —
raw data never leaves the process. This is the "prove" leg of the loop: the execution is
sealed into Ernest's hashchain (the governance decision is sealed by Prudence itself).

Two transports for step records:
  - direct (default): POST /api/inferences on the Ernest backend — synchronous append, block
    index in the response, and the only path that carries a qualified seal.
  - ingestor: POST /events on Ernest's event-ingestor (set `ingestor_url` or
    ERNEST_INGESTOR_URL) — the request is queued (202) and appended asynchronously, deduped by
    the inference id. Built for volume; it does not carry qualified seals, so it cannot be
    combined with a `QtspSealer`. Model registration always goes direct.

When a `QtspSealer` is supplied (ADR-002 B2), each submission is additionally signed with a
qualified electronic seal over the exact block-data Ernest canonicalizes, so the evidence
carries eIDAS-qualified authorship, not just tamper-evidence.
"""
from __future__ import annotations

import hashlib
import json
import os
import time
import uuid

import httpx

from .seal import QtspSealer


def sha256_json(obj) -> str:
    data = json.dumps(obj, sort_keys=True, separators=(",", ":"), default=str).encode()
    return hashlib.sha256(data).hexdigest()


def _safe_json(r: httpx.Response):
    try:
        return r.json()
    except Exception:  # noqa: BLE001
        return {"status": r.status_code, "text": r.text[:200]}


class ErnestEvidence:
    def __init__(self, base_url: str | None = None, api_key: str | None = None,
                 timeout: float = 5.0, sealer: QtspSealer | None = None,
                 ingestor_url: str | None = None, ingestor_key: str | None = None,
                 source: str = "prudence-agent"):
        self.base_url = (base_url or os.getenv("ERNEST_URL", "http://127.0.0.1:3001")).rstrip("/")
        self.api_key = api_key if api_key is not None else os.getenv("ERNEST_API_KEY", "")
        self.timeout = timeout
        self.sealer = sealer
        self.ingestor_url = (ingestor_url or os.getenv("ERNEST_INGESTOR_URL", "")).rstrip("/")
        self.ingestor_key = ingestor_key if ingestor_key is not None else os.getenv("ERNEST_INGESTOR_KEY", "")
        self.source = source
        if self.ingestor_url and self.sealer is not None:
            # The ingestor stores its own event envelope in the block, so a seal over the
            # direct block-data would not verify. Refuse rather than silently drop the seal.
            raise ValueError("qualified seals need the direct transport: unset ERNEST_INGESTOR_URL or drop the sealer")

    def _headers(self) -> dict[str, str]:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["X-Ernest-Api-Key"] = self.api_key
        return h

    def ensure_model(self, model_id: str, model_name: str, version: str,
                     *, model_hash: str | None = None, git_commit: str = "0000000",
                     metadata: dict | None = None):
        model_hash = model_hash or sha256_json({"model": model_id, "v": version})
        body = {
            "modelId": model_id,
            "modelName": model_name,
            "version": version,
            "mlflow": {"modelHash": model_hash, "gitCommit": git_commit},
        }
        if metadata:
            body["metadata"] = metadata
        if self.sealer is not None:
            # The signed block-data is the flat shape Ernest stores (mlflow flattened).
            signed = {"type": "model_registration", "modelId": model_id, "modelName": model_name,
                      "version": version, "modelHash": model_hash, "gitCommit": git_commit}
            if metadata:
                signed["metadata"] = metadata
            body["signature"] = self.sealer.seal(signed)
        try:
            r = httpx.post(f"{self.base_url}/api/models", json=body, headers=self._headers(), timeout=self.timeout)
            return r.status_code, _safe_json(r)
        except Exception as e:  # noqa: BLE001
            return None, {"error": str(e)}

    def log_step(self, model_id: str, *, inputs, output, metadata: dict | None = None):
        body = {
            "modelId": model_id,
            "inferenceId": str(uuid.uuid4()),
            "inputHash": sha256_json(inputs),
            "outputHash": sha256_json(output),
        }
        if metadata:
            body["metadata"] = metadata
        if self.ingestor_url:
            return self._enqueue(body)
        if self.sealer is not None:
            signed = {"type": "inference", "modelId": model_id, "inferenceId": body["inferenceId"],
                      "inputHash": body["inputHash"], "outputHash": body["outputHash"]}
            if metadata:
                signed["metadata"] = metadata
            body["signature"] = self.sealer.seal(signed)
        try:
            r = httpx.post(f"{self.base_url}/api/inferences", json=body, headers=self._headers(), timeout=self.timeout)
            return r.status_code, _safe_json(r)
        except Exception as e:  # noqa: BLE001
            return None, {"error": str(e)}

    def log_execution(self, ai_system_id: str, *, pipeline: str, runtime: str,
                      decision_id: str | None, inputs=None):
        """Seal that a governed execution started, under the AI system's id: the execution
        evidence Ernest counts per system (`stage: execution`), which Prudence compares with
        the runtime's own report. Only a hash of `inputs` travels. Best effort: never raises.

        Prudence registers the system in Ernest when it delivers the system's first decision,
        which can land a moment after the run started. Ernest then answers 404 or 409 ("not yet
        committed"); the seal is retried for a few seconds, so call this off the hot path."""
        metadata = {"stage": "execution", "aiSystemId": ai_system_id, "pipeline": pipeline,
                    "runtime": runtime, "decisionId": decision_id}
        result = (None, {"error": "not attempted"})
        for delay in (0.5, 1, 2, 2, 3, 0):
            try:
                result = self.log_step(ai_system_id, inputs=inputs or {}, output={"decisionId": decision_id},
                                       metadata=metadata)
            except Exception as e:  # noqa: BLE001 — evidence must never break the governed run
                result = (None, {"error": str(e)})
            if result[0] not in (None, 404, 409) or not delay:
                return result
            time.sleep(delay)
        return result

    def _enqueue(self, body: dict):
        """Queue a step record on the event-ingestor. The inference id doubles as the
        ingestor's source event id, so a re-sent record is recognised as a duplicate."""
        headers = {"Content-Type": "application/json",
                   "X-Ernest-Event-Source": self.source,
                   "X-Ernest-Event-Type": "inference.logged",
                   "X-Ernest-Source-Event-Id": body["inferenceId"]}
        if self.ingestor_key:
            headers["X-Ernest-Ingest-Key"] = self.ingestor_key
        try:
            r = httpx.post(f"{self.ingestor_url}/events", json=body, headers=headers, timeout=self.timeout)
            return r.status_code, _safe_json(r)
        except Exception as e:  # noqa: BLE001
            return None, {"error": str(e)}

    def verify(self) -> dict:
        try:
            r = httpx.get(f"{self.base_url}/api/verify", headers=self._headers(), timeout=self.timeout)
            return _safe_json(r)
        except Exception as e:  # noqa: BLE001
            return {"error": str(e)}
