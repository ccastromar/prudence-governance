"""Prudence PEP client for Pydantic AI.

The Policy Enforcement Point: consults the Prudence PDP (`POST /api/policy/decisions`)
and returns a typed decision. This is the *same* gate AOS implements in Go — moved to
where the agents actually run. The enforcement point belongs in the runtime; the policy
stays outside, in Prudence.

Fail-closed: any transport error is treated as DENY. A governance engine that is down
must never grant access.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

import httpx


@dataclass(frozen=True)
class Decision:
    decision: str                 # ALLOW | DENY | APPROVAL_REQUIRED
    reason: str | None = None
    approver: str | None = None

    @property
    def allowed(self) -> bool:
        return self.decision == "ALLOW"


class PrudenceGate:
    def __init__(self, base_url: str | None = None, timeout: float = 3.0):
        self.base_url = (base_url or os.getenv("PRUDENCE_URL", "http://127.0.0.1:8099")).rstrip("/")
        self.timeout = timeout

    def decide(self, subject: str, pipeline: str, ai_system_id: str, action: str = "run") -> Decision:
        payload = {"subject": subject, "pipeline": pipeline, "aiSystemId": ai_system_id, "action": action}
        try:
            r = httpx.post(f"{self.base_url}/api/policy/decisions", json=payload, timeout=self.timeout)
            body = r.json()
        except Exception as e:  # noqa: BLE001 — fail closed on any error
            return Decision("DENY", f"PDP unreachable: {e}")
        return Decision(body.get("decision", "DENY"), body.get("reason"), body.get("approver"))
