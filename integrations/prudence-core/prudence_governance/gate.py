"""Prudence PEP client.

The Policy Enforcement Point: consults the Prudence PDP (`POST /api/policy/decisions`)
and returns a typed decision. This is the *same* gate AOS implements in Go — moved to
where the agents actually run. The enforcement point belongs in the runtime; the policy
stays outside, in Prudence.

Fail-closed: any transport error is treated as DENY. A governance engine that is down
must never grant access.

When the PDP is secured (production), pass an `M2MAuth` so the gate obtains an OAuth2
client-credentials token (the `pdp.decide` scope) and presents it as a bearer. The token
is cached and refreshed shortly before expiry.
"""
from __future__ import annotations

import os
import time
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


@dataclass
class M2MAuth:
    """OAuth2 client-credentials for calling a secured PDP. `token_url` is the IdP's token
    endpoint (e.g. Keycloak .../protocol/openid-connect/token)."""
    token_url: str
    client_id: str
    client_secret: str
    scope: str = "pdp.decide"
    _token: str = ""
    _exp: float = 0.0

    @classmethod
    def from_env(cls) -> "M2MAuth | None":
        url = os.getenv("PRUDENCE_PDP_TOKEN_URL")
        cid = os.getenv("PRUDENCE_PDP_CLIENT_ID")
        secret = os.getenv("PRUDENCE_PDP_CLIENT_SECRET")
        if url and cid and secret:
            return cls(url, cid, secret, os.getenv("PRUDENCE_PDP_SCOPE", "pdp.decide"))
        return None

    def bearer(self, timeout: float = 5.0) -> str:
        if self._token and time.time() < self._exp - 30:
            return self._token
        data = {"grant_type": "client_credentials", "client_id": self.client_id,
                "client_secret": self.client_secret, "scope": self.scope}
        r = httpx.post(self.token_url, data=data, timeout=timeout)
        r.raise_for_status()
        tok = r.json()
        self._token = tok["access_token"]
        self._exp = time.time() + float(tok.get("expires_in", 300))
        return self._token


class PrudenceGate:
    def __init__(self, base_url: str | None = None, timeout: float = 3.0, auth: M2MAuth | None = None):
        self.base_url = (base_url or os.getenv("PRUDENCE_URL", "http://127.0.0.1:8099")).rstrip("/")
        self.timeout = timeout
        self.auth = auth if auth is not None else M2MAuth.from_env()

    def decide(self, subject: str, pipeline: str, ai_system_id: str, action: str = "run") -> Decision:
        payload = {"subject": subject, "pipeline": pipeline, "aiSystemId": ai_system_id, "action": action}
        headers = {}
        try:
            if self.auth is not None:
                headers["Authorization"] = f"Bearer {self.auth.bearer()}"
            r = httpx.post(f"{self.base_url}/api/policy/decisions", json=payload, headers=headers, timeout=self.timeout)
            body = r.json()
        except Exception as e:  # noqa: BLE001 — fail closed on any error
            return Decision("DENY", f"PDP unreachable: {e}")
        return Decision(body.get("decision", "DENY"), body.get("reason"), body.get("approver"))
