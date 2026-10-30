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

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


@tool(name=TOOL, description=DESCRIPTION)
def run_tool(reference: str) -> str:
    """Run the tool.

    Args:
        reference: what the tool acts on
    """
    ran.append(reference)
    return RESULT.format(reference=reference)


model = OpenAIModel(client_args={"base_url": os.environ["LLM_URL"], "api_key": os.environ.get("LLM_API_KEY", "none")},
                    model_id=os.environ.get("LLM_MODEL", "conformance-scripted"))
governance = PrudenceHooks(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = Agent(model=model, system_prompt="Use your tools to do what the user asks.",
              tools=[run_tool], hooks=[governance], callback_handler=None)
result = agent(TASK)
time.sleep(0.5)  # the adapter reports in the background
print(json.dumps({"toolRan": bool(ran), "answer": str(result)}))
