"""End-to-end demo: a Pydantic AI agent whose tool is governed by Prudence and sealed by
Ernest — the Chestertown control loop with *no AOS in sight*.

Runs against the live local stack (Prudence :8099 + OPA :8181 + Ernest :3001). Uses
Pydantic AI's TestModel, so no LLM API key is needed — the agent still drives the tool,
and the governance layer is the real subject of the demo.

    python example_agent.py
"""
from __future__ import annotations

import hashlib
import os
import uuid

import httpx
from pydantic_ai import Agent, RunContext
from pydantic_ai.models.test import TestModel

from prudence_governance import QtspSealer
from prudence_pydantic import ErnestEvidence, GovDeps, PrudenceGate, governed

PRUDENCE = os.getenv("PRUDENCE_URL", "http://127.0.0.1:8099")


def register_system(risk_tier: str, approve: bool) -> str:
    """Self-contained setup: register a system in the Prudence registry (and optionally
    approve it) using the dev/open chain, so the demo needs no pre-seeded data."""
    r = httpx.post(f"{PRUDENCE}/api/registry/systems", json={
        "name": "Credit risk scoring (Pydantic AI)",
        "intendedPurpose": "Creditworthiness of natural persons",
        "ownerName": "Alice", "ownerEmail": "alice@bank.eu", "riskTier": risk_tier,
    }, timeout=5)
    sid = r.json()["id"]
    if approve:
        httpx.post(f"{PRUDENCE}/api/registry/systems/{sid}/approve", timeout=5)
    return sid


agent = Agent(deps_type=GovDeps)


@agent.tool
@governed(pipeline="credit-risk-scoring", action="run")
def score_credit_risk(ctx: RunContext[GovDeps], applicant_id: str = "A-1001", amount: float = 15000.0) -> str:
    """Score a credit applicant. Governed by Prudence; every call sealed by Ernest."""
    h = int(hashlib.sha256(f"{applicant_id}:{amount}".encode()).hexdigest(), 16) % 1000
    score = round(300 + (h / 1000) * 550)  # 300..850
    band = "approve" if score >= 640 else "refer"
    return f"applicant={applicant_id} amount={amount} score={score} decision={band}"


def run_once(label: str, subject: str, ai_system_id: str, model_id: str,
             gate: PrudenceGate, evidence: ErnestEvidence) -> None:
    deps = GovDeps(subject=subject, ai_system_id=ai_system_id, model_id=model_id, gate=gate, evidence=evidence)
    result = agent.run_sync("Score applicant A-1001 requesting 15000", deps=deps, model=TestModel())
    output = getattr(result, "output", None) or getattr(result, "data", None)
    print(f"[{label:>8}] subject={subject:<18} -> {output}")


def main() -> None:
    gate = PrudenceGate(PRUDENCE)
    csc = os.getenv("QTSP_CSC_URL")  # e.g. http://127.0.0.1:4600/csc/sign
    sealer = QtspSealer(csc) if csc else None
    evidence = ErnestEvidence(sealer=sealer)  # ERNEST_API_KEY from env
    print(f"qualified seal (eIDAS B2): {'ON via ' + csc if sealer else 'off'}")

    approved = register_system("HIGH", approve=True)
    pending = register_system("HIGH", approve=False)
    run = uuid.uuid4().hex[:8]
    model_id = f"credit-risk-scoring-agent-{run}"
    evidence.ensure_model(model_id, f"Credit risk scoring agent (Pydantic AI) {run}", "1.0.0",
                          metadata={"framework": "pydantic-ai", "aiSystemId": approved})

    v = evidence.verify()
    before = v.get("chainLength", v.get("blocksVerified"))  # chainLength on Ernest with incremental verify
    print(f"approved system: {approved}")
    print(f"pending  system: {pending}")
    print(f"Ernest chain before: {before} blocks\n")

    # ALLOW — approved system + a subject OPA authorizes (alice has model-operator/run).
    run_once("ALLOW", "alice@bank.eu", approved, model_id, gate, evidence)
    # DENY — approved system, but OPA grants the subject no role on this pipeline.
    run_once("DENY", "mallory@bank.eu", approved, model_id, gate, evidence)
    # APPROVAL — a registered-but-unapproved system: the gate pauses before any run.
    run_once("APPROVAL", "alice@bank.eu", pending, model_id, gate, evidence)

    after = evidence.verify()
    print(f"\nErnest chain after:  {after.get('chainLength', after.get('blocksVerified'))} blocks  (valid={after.get('isValid')})")
    print("Every governed step above is now sealed in the hashchain — offline-verifiable.")


if __name__ == "__main__":
    main()
