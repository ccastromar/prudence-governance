"""What a gate that asks Prudence about another agent does with the answer (contract/identity/,
README.md section 12, on top of contract/decision/ README.md section 2).

Standard library only, so the gateway's guardrail and check_gate_vectors.py share it.
"""
from __future__ import annotations

import base64
import json

# contract/decision/ README.md section 2: the status and the decision come in pairs.
PAIRS = {200: "ALLOW", 202: "APPROVAL_REQUIRED", 403: "DENY", 503: "ERROR"}


def agent_named_by(token: str | None, principal_mode: str = "legacy") -> str | None:
    """The agent a token names, exactly as Prudence names a caller
    (`prudence.identity.principal-mode`): `email`, then `preferred_username`, then `sub`; or
    issuer + subject. The signature is not checked here: Prudence validates the token, the gate
    only needs the name to compare the answer with. None when the token names no one."""
    try:
        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except Exception:
        return None
    if not isinstance(claims, dict):
        return None
    if principal_mode == "issuer-subject":
        issuer, subject = claims.get("iss"), claims.get("sub")
        if not (isinstance(issuer, str) and issuer.strip() and isinstance(subject, str) and subject.strip()):
            return None
        return f"{len(issuer)}:{issuer}{subject}"
    for claim in ("email", "preferred_username", "sub"):
        value = claims.get(claim)
        if isinstance(value, str) and value.strip():
            return value
    return None


def gate(api: str, status: int, body: str, asked: str | None) -> dict:
    """Whether the step runs, for an answer of the Decision API (`decision`) or AuthZEN
    (`authzen`) about agent `asked`. Runs only on a well-formed ALLOW whose `identity.agent` is
    `asked`: an answer without `identity` comes from a Prudence that decided about the caller.
    Returns `run`, plus `decisionId` and the decision's facts when it runs, and the `reasonCode`
    (or `reason`) Prudence gave when it stops."""
    try:
        doc = json.loads(body)
    except ValueError:
        return {"run": False}
    if not isinstance(doc, dict):
        return {"run": False}
    if api == "authzen":
        facts = doc.get("context") if isinstance(doc.get("context"), dict) else {}
        allowed = status == 200 and doc.get("decision") is True
    else:
        facts = doc
        allowed = PAIRS.get(status) == doc.get("decision") == "ALLOW"
    if not allowed:
        stop = {"run": False}
        for key in ("reasonCode", "reason"):
            if isinstance(facts.get(key), str) and facts[key]:
                stop[key] = facts[key]
                break
        return stop
    decision_id = facts.get("decisionId")
    if not (isinstance(decision_id, str) and decision_id.strip()):
        return {"run": False}
    identity = facts.get("identity") if isinstance(facts.get("identity"), dict) else {}
    if not asked or identity.get("agent") != asked:
        return {"run": False, "reason": "Prudence did not decide about the agent"}
    return {"run": True, "decisionId": decision_id, "facts": facts}
