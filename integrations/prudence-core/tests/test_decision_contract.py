"""prudence-core's gate against the Decision API contract (contract/decision/vectors).

    python -m pip install httpx
    python -m unittest discover -s integrations/prudence-core/tests
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

import httpx

CORE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CORE))
from prudence_governance import gate as gate_module  # noqa: E402
from prudence_governance.gate import Decision, PrudenceGate  # noqa: E402

VECTORS = CORE.parent.parent / "contract" / "decision" / "vectors"


def answer(status: int, body: str):
    def post(url, **kwargs):
        return httpx.Response(status, content=body.encode(), request=httpx.Request("POST", url))
    return post


class DecisionContract(unittest.TestCase):
    def test_gate_vectors(self):
        gate = PrudenceGate("http://prudence.test", auth=None)
        for case in json.loads((VECTORS / "gate.json").read_text())["cases"]:
            with self.subTest(case["name"]), mock.patch.object(gate_module.httpx, "post", answer(case["status"], case["body"])):
                got = gate.decide("runtime", "credit_prescreening", "sys-1")
                expect = case["expect"]
                self.assertEqual(expect["run"], got.allowed)
                if "decisionId" in expect:
                    self.assertEqual(expect["decisionId"], got.decision_id)
                if "decision" in expect:
                    self.assertEqual(expect["decision"], got.decision)
                if "approver" in expect:
                    self.assertEqual(expect["approver"], got.approver)

    def test_report_vectors(self):
        gate = PrudenceGate("http://prudence.test", auth=None)
        allowed = Decision("ALLOW", None, None, "4f1c2a5e-9b0d-4c67-8a21-3e5f6d7c8b90")
        for case in json.loads((VECTORS / "report.json").read_text())["cases"]:
            with self.subTest(case["status"]), mock.patch.object(gate_module.httpx, "post", answer(case["status"], "{}")):
                # report_execution says only whether it was recorded; it never retries itself.
                self.assertEqual(case["expect"] == "recorded", gate.report_execution(allowed, "sys-1", "p"))

    def test_requests_carry_the_contract_fields(self):
        sent = []

        def post(url, json=None, **kwargs):
            sent.append(json)
            return httpx.Response(200, content=b'{"decision":"ALLOW","decisionId":"d-1"}', request=httpx.Request("POST", url))

        gate = PrudenceGate("http://prudence.test", auth=None)
        with mock.patch.object(gate_module.httpx, "post", post):
            decision = gate.decide("runtime", "credit_prescreening", "sys-1")
            gate.report_execution(decision, "sys-1", "credit_prescreening", runtime="langgraph", execution_id="run-1")
        provider = json.loads((VECTORS / "provider.json").read_text())
        self.assertEqual(set(provider["request"]), set(sent[0]))
        self.assertEqual(set(provider["report"]["report"]), set(sent[1]))
        self.assertEqual("d-1", sent[1]["decisionId"])
        self.assertTrue(sent[1]["startedAt"].endswith("Z"))


if __name__ == "__main__":
    unittest.main()
