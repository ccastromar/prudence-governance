"""A smolagents agent (ToolCallingAgent) with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import json
import os

from smolagents import OpenAIServerModel, ToolCallingAgent, tool

from prudence_governance import ErnestEvidence
from prudence_smolagents import PrudenceGate, govern_tools

ran: list[str] = []


@tool
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer.

    Args:
        customer_id: the customer to write to
    """
    ran.append(customer_id)
    return f"sent to {customer_id}"


model = OpenAIServerModel(model_id=os.environ.get("LLM_MODEL", "conformance-scripted"), api_base=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
tools = govern_tools(
    [send_customer_letter], os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = ToolCallingAgent(tools=tools, model=model, max_steps=2, verbosity_level=0)
answer = agent.run("Send the letter to customer C-1")
print(json.dumps({"toolRan": bool(ran), "answer": str(answer)}))
