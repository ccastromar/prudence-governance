"""A LlamaIndex agent (FunctionAgent) with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os

from llama_index.core.agent.workflow import FunctionAgent
from llama_index.core.tools import FunctionTool
from llama_index.llms.openai_like import OpenAILike

from prudence_governance import ErnestEvidence
from prudence_llamaindex import PrudenceGate, govern_tools

ran: list[str] = []


def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


async def main() -> None:
    llm = OpenAILike(model=os.environ.get("LLM_MODEL", "conformance-scripted"), api_base=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"),
                     is_chat_model=True, is_function_calling_model=True)
    tools = govern_tools(
        [FunctionTool.from_defaults(send_customer_letter)], os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
        evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
    )
    agent = FunctionAgent(tools=tools, llm=llm, system_prompt="Use your tools to do what the user asks.")
    response = await agent.run("Send the letter to customer C-1")
    print(json.dumps({"toolRan": bool(ran), "answer": str(response)}))


asyncio.run(main())
