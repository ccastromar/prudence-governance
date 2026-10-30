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

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")

governance = prudence_guardrail(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)


@function_tool(name_override=TOOL, description_override=DESCRIPTION, tool_input_guardrails=[governance])
def run_tool(reference: str) -> str:
    ran.append(reference)
    return RESULT.format(reference=reference)


async def main() -> None:
    model = OpenAIChatCompletionsModel(
        model=os.environ.get("LLM_MODEL", "conformance-scripted"),
        openai_client=AsyncOpenAI(base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none")),
    )
    agent = Agent(name="claims_assistant", instructions="Use your tools to do what the user asks.",
                  tools=[run_tool], model=model)
    result = await Runner.run(agent, TASK)
    print(json.dumps({"toolRan": bool(ran), "answer": str(result.final_output)}))


asyncio.run(main())
