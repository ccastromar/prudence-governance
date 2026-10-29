"""An OpenAI Agents SDK agent with one governed tool, as a company using the SDK would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os

from agents import Agent, OpenAIChatCompletionsModel, Runner, function_tool, set_tracing_disabled
from openai import AsyncOpenAI

from prudence_governance import ErnestEvidence
from prudence_openai_agents import PrudenceGate, prudence_guardrail

set_tracing_disabled(True)  # no traces to OpenAI from a test
ran: list[str] = []

governance = prudence_guardrail(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)


@function_tool(tool_input_guardrails=[governance])
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


async def main() -> None:
    model = OpenAIChatCompletionsModel(
        model=os.environ.get("LLM_MODEL", "conformance-scripted"),
        openai_client=AsyncOpenAI(base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none")),
    )
    agent = Agent(name="claims_assistant", instructions="Use your tools to do what the user asks.",
                  tools=[send_customer_letter], model=model)
    result = await Runner.run(agent, "Send the letter to customer C-1")
    print(json.dumps({"toolRan": bool(ran), "answer": str(result.final_output)}))


asyncio.run(main())
