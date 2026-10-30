"""A LangChain agent (create_agent) with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import json
import os

from langchain.agents import create_agent
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI

from prudence_governance import ErnestEvidence
from prudence_langchain import PrudenceGate, PrudenceMiddleware

ran: list[str] = []

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


@tool(TOOL, description=DESCRIPTION)
def run_tool(reference: str) -> str:
    ran.append(reference)
    return RESULT.format(reference=reference)


model = ChatOpenAI(model=os.environ.get("LLM_MODEL", "conformance-scripted"), base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
governance = PrudenceMiddleware(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = create_agent(model, tools=[run_tool], middleware=[governance],
                     system_prompt="Use your tools to do what the user asks.")
result = agent.invoke({"messages": [{"role": "user", "content": TASK}]})
print(json.dumps({"toolRan": bool(ran), "answer": str(result["messages"][-1].content)}))
