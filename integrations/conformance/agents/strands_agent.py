"""A Strands Agents agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import json
import os
import time

from strands import Agent, tool
from strands.models.openai import OpenAIModel

from prudence_governance import ErnestEvidence
from prudence_strands import PrudenceGate, PrudenceHooks

ran: list[str] = []


@tool
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer.

    Args:
        customer_id: the customer to write to
    """
    ran.append(customer_id)
    return f"sent to {customer_id}"


model = OpenAIModel(client_args={"base_url": os.environ["LLM_URL"], "api_key": os.environ.get("LLM_API_KEY", "none")},
                    model_id=os.environ.get("LLM_MODEL", "conformance-scripted"))
governance = PrudenceHooks(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = Agent(model=model, system_prompt="Use your tools to do what the user asks.",
              tools=[send_customer_letter], hooks=[governance], callback_handler=None)
result = agent("Send the letter to customer C-1")
time.sleep(0.5)  # the adapter reports in the background
print(json.dumps({"toolRan": bool(ran), "answer": str(result)}))
