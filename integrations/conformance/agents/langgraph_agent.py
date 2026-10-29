"""A LangGraph graph with one governed node, as a company using it would write it.

The `send` node drafts the letter with the model and sends it; it is wrapped by the gate, and a
conditional edge only reaches `confirm` on ALLOW.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line: {"toolRan": bool, "answer": str}.
"""
import json
import os
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

from prudence_governance import ErnestEvidence, PrudenceGate
from prudence_langgraph import gate_route, governed_node

ran: list[str] = []


class LetterState(TypedDict, total=False):
    subject: str
    ai_system_id: str
    customer_id: str
    gate: str
    reason: str
    output: str


def send(state: LetterState) -> dict:
    r = httpx.post(f"{os.environ['LLM_URL']}/chat/completions", timeout=20,
                   headers={"Authorization": f"Bearer {os.environ.get('LLM_API_KEY', 'none')}"},
                   json={"model": os.environ.get("LLM_MODEL", "conformance-scripted"),
                         "messages": [{"role": "user", "content": "Draft the standard claims letter."}]})
    r.raise_for_status()
    draft = r.json()["choices"][0]["message"]["content"]
    ran.append(state["customer_id"])
    return {"output": f"sent to {state['customer_id']} ({draft})"}


graph = StateGraph(LetterState)
graph.add_node("send", governed_node(
    send, pipeline=os.environ.get("PIPELINE", "send_customer_letter"),
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None))
graph.add_node("confirm", lambda state: {"output": state["output"] + "; confirmed"})
graph.add_edge(START, "send")
graph.add_conditional_edges("send", gate_route, {"allow": "confirm", "stop": END})
graph.add_edge("confirm", END)

system = os.environ["AI_SYSTEM_ID"]
out = graph.compile().invoke({
    "subject": "langgraph",  # the dev profile's caller; a secured PDP takes it from the token
    "ai_system_id": system,
    "customer_id": "C-1",
})
print(json.dumps({"toolRan": bool(ran), "answer": out.get("output", "")}))
