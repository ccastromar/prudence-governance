"""Ernest evidence client.

Emits tamper-evident provenance to Ernest: a *model* block per governed agent, an
*inference* block per governed step. Only SHA-256 hashes of inputs/outputs are sent —
raw data never leaves the process. This is the "prove" leg of the loop: the governance
decision and the execution are both sealed into Ernest's hashchain.

When a `QtspSealer` is supplied (ADR-002 B2), each submission is additionally signed with a
qualified electronic seal over the exact block-data Ernest canonicalizes, so the evidence
carries eIDAS-qualified authorship, not just tamper-evidence.
"""
from __future__ import annotations

import hashlib
import json
import os
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
                 timeout: float = 5.0, sealer: QtspSealer | None = None):
        self.base_url = (base_url or os.getenv("ERNEST_URL", "http://127.0.0.1:3001")).rstrip("/")
        self.api_key = api_key if api_key is not None else os.getenv("ERNEST_API_KEY", "")
        self.timeout = timeout
        self.sealer = sealer

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

    def verify(self) -> dict:
        try:
            r = httpx.get(f"{self.base_url}/api/verify", headers=self._headers(), timeout=self.timeout)
            return _safe_json(r)
        except Exception as e:  # noqa: BLE001
            return {"error": str(e)}
