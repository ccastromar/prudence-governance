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


@tool
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


model = ChatOpenAI(model=os.environ.get("LLM_MODEL", "conformance-scripted"), base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
governance = PrudenceMiddleware(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
agent = create_agent(model, tools=[send_customer_letter], middleware=[governance],
                     system_prompt="Use your tools to do what the user asks.")
result = agent.invoke({"messages": [{"role": "user", "content": "Send the letter to customer C-1"}]})
print(json.dumps({"toolRan": bool(ran), "answer": str(result["messages"][-1].content)}))
