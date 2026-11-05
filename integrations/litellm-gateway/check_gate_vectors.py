#!/usr/bin/env python3
"""Checks the gateway's gate (identity_gate.py) against contract/identity/vectors/gate.json.

    python3 integrations/litellm-gateway/check_gate_vectors.py

Standard library only: no LiteLLM, no Prudence. The guardrail asks only AuthZEN, but the gate
reads both APIs, so every case runs. The rules of contract/decision/ come first: the
not-well-formed ALLOWs of contract/decision/vectors/gate.json must stop here too, even about the
right agent. Then the guardrail's own naming of the agent from its token.

Prints one line per failing check and exits 1 if any fails.
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONTRACT = HERE.parent.parent / "contract"
sys.path.insert(0, str(HERE))
from identity_gate import agent_named_by, gate  # noqa: E402


def token(claims: dict) -> str:
    def part(doc: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(doc).encode()).decode().rstrip("=")
    return f"{part({'alg': 'none'})}.{part(claims)}.x"


def main() -> int:
    failures = []

    for c in json.loads((CONTRACT / "identity" / "vectors" / "gate.json").read_text())["cases"]:
        got = gate(c["api"], c["status"], c["body"], c["asked"])
        if any(got.get(key) != value for key, value in c["expect"].items()):
            failures.append(f"identity gate: {c['name']}: expected {c['expect']}, gate says {got}")

    # A body that the decision contract stops on stops here too, whoever it names.
    for c in json.loads((CONTRACT / "decision" / "vectors" / "gate.json").read_text())["cases"]:
        if c["expect"]["run"]:
            continue
        try:
            doc = json.loads(c["body"])
        except ValueError:
            doc = None
        body = c["body"]
        if isinstance(doc, dict):
            doc["identity"] = {"caller": "tool-gateway", "agent": "claims-assistant", "agentAssurance": "verified"}
            body = json.dumps(doc)
        if gate("decision", c["status"], body, "claims-assistant")["run"]:
            failures.append(f"decision gate: {c['name']}: runs once it names the agent")

    names = [
        ({"email": "a@x", "preferred_username": "a", "sub": "1"}, "legacy", "a@x"),
        ({"preferred_username": "service-account-bot", "sub": "1"}, "legacy", "service-account-bot"),
        ({"email": " ", "sub": "1"}, "legacy", "1"),
        ({"iss": "https://idp", "sub": "1", "email": "a@x"}, "issuer-subject", "11:https://idp1"),
        ({"email": "a@x"}, "issuer-subject", None),
        ({}, "legacy", None),
    ]
    for claims, mode, expected in names:
        if agent_named_by(token(claims), mode) != expected:
            failures.append(f"naming: {claims} ({mode}): expected {expected!r}")
    for broken in ("", "not-a-jwt", "a.!!!.c", f"a.{base64.urlsafe_b64encode(b'[1]').decode()}.c"):
        if agent_named_by(broken) is not None:
            failures.append(f"naming: {broken!r} names someone")
    if agent_named_by(None) is not None:
        failures.append("naming: no token names someone")

    for f in failures:
        print(f)
    print(f"{'FAIL' if failures else 'ok'}: identity gate vectors (litellm-gateway)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
