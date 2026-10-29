"""An Agno agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import json
import os

from agno.agent import Agent
from agno.models.openai.like import OpenAILike

from prudence_agno import PrudenceGate, PrudenceToolHook
from prudence_governance import ErnestEvidence

ran: list[str] = []


def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


governance = PrudenceToolHook(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = Agent(model=OpenAILike(id=os.environ.get("LLM_MODEL", "conformance-scripted"), base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none")),
              instructions="Use your tools to do what the user asks.",
              tools=[send_customer_letter], tool_hooks=[governance], telemetry=False)
response = agent.run("Send the letter to customer C-1")
print(json.dumps({"toolRan": bool(ran), "answer": str(response.content)}))
