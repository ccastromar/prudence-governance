"""ADR-002 B2 — qualified electronic seal (qeseal) over an Ernest submission.

To seal a submission, the client signs the *exact* bytes Ernest will re-canonicalize and
verify: the block `data` (minus `signature` and server-augmented fields), cleaned the way
Ernest cleans it, JCS-canonicalized (RFC 8785), wrapped in a DSSE PAE. The signature itself
is produced by a QTSP remote-signing endpoint (CSC) — here the local emulator's `/csc/sign`,
in production a real QTSP — so the private key never touches this process. The returned
envelope (alg `qeseal-ecdsa-p256`, X.509 chain in `x5c`) goes into the submission's
`signature` field; Ernest admits it only if the chain validates to its trusted CA.

The byte construction mirrors backend `src/common/signing.util.ts` and `cleanObject`.
"""
from __future__ import annotations

import base64
import json

import httpx

PAYLOAD_TYPE = "application/vnd.ernest.provenance+json"
EXCLUDED_KEYS = {"__v", "_id", "createdAt", "updatedAt", "hash"}


def clean_object(obj):
    """Replicates Ernest's cleanObject: drop null/undefined/empty-string values and
    empty nested objects/arrays, recursively."""
    if obj is None:
        return {}
    if not isinstance(obj, (dict, list)):
        return obj
    if isinstance(obj, list):
        out = []
        for item in obj:
            c = clean_object(item)
            if c is not None:
                out.append(c)
        return out
    cleaned = {}
    for key, value in obj.items():
        if value is None or value == "":
            continue
        if isinstance(value, (dict, list)):
            cv = clean_object(value)
            if len(cv) > 0:
                cleaned[key] = cv
        else:
            cleaned[key] = value
    return cleaned


def _strip_excluded(obj):
    if isinstance(obj, dict):
        return {k: _strip_excluded(v) for k, v in obj.items() if k not in EXCLUDED_KEYS}
    if isinstance(obj, list):
        return [_strip_excluded(x) for x in obj]
    return obj


def jcs(obj) -> bytes:
    """RFC 8785 JSON Canonicalization — the subset exercised by Ernest's string/number
    submission payloads (sorted keys, minimal separators, UTF-8)."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def dsse_pae(payload_type: str, body: bytes) -> bytes:
    header = f"DSSEv1 {len(payload_type)} {payload_type} {len(body)} ".encode("utf-8")
    return header + body


def signed_bytes(block_data: dict) -> bytes:
    """The exact bytes an emitter signs (see signing.util.signedBytes)."""
    stripped = {k: v for k, v in clean_object(block_data).items() if k != "signature"}
    canonical = jcs(_strip_excluded(stripped))
    return dsse_pae(PAYLOAD_TYPE, canonical)


class QtspSealer:
    """Produces a qeseal envelope by asking a CSC remote-signing endpoint to sign the DSSE
    PAE of a submission's block data."""

    def __init__(self, csc_url: str, timeout: float = 5.0):
        self.csc_url = csc_url.rstrip("/")
        self.timeout = timeout

    def seal(self, block_data: dict) -> dict:
        pae = signed_bytes(block_data)
        r = httpx.post(self.csc_url, json={"bytes": base64.b64encode(pae).decode("ascii")}, timeout=self.timeout)
        r.raise_for_status()
        return r.json()
