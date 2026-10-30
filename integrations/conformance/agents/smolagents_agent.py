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

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


@tool
def run_tool(reference: str) -> str:
    """Run the tool.

    Args:
        reference: what the tool acts on
    """
    ran.append(reference)
    return RESULT.format(reference=reference)


run_tool.name = TOOL
run_tool.description = DESCRIPTION
model = OpenAIServerModel(model_id=os.environ.get("LLM_MODEL", "conformance-scripted"), api_base=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
tools = govern_tools(
    [run_tool], os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = ToolCallingAgent(tools=tools, model=model, max_steps=2, verbosity_level=0)
answer = agent.run(TASK)
print(json.dumps({"toolRan": bool(ran), "answer": str(answer)}))
