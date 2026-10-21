"""A Google ADK agent with one governed tool, as a company using ADK would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID and PIPELINE; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import InMemoryRunner
from google.genai import types

from prudence_adk import PrudenceGate, PrudencePlugin

ran: list[str] = []


def send_customer_letter(customer_id: str) -> dict:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return {"status": "sent", "customer": customer_id}


async def main() -> None:
    agent = LlmAgent(
        name="claims_assistant",
        model=LiteLlm(model="openai/conformance-scripted", api_base=os.environ["LLM_URL"], api_key="none"),
        instruction="Use your tools to do what the user asks.",
        tools=[send_customer_letter],
    )
    governance = PrudencePlugin(
        os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    )
    runner = InMemoryRunner(agent=agent, app_name="conformance", plugins=[governance])
    session = await runner.session_service.create_session(app_name="conformance", user_id="tester")
    answer = ""
    message = types.Content(role="user", parts=[types.Part(text="Send the letter to customer C-1")])
    async for event in runner.run_async(user_id="tester", session_id=session.id, new_message=message):
        for part in (event.content.parts if event.content and event.content.parts else []):
            if part.text:
                answer = part.text
    print(json.dumps({"toolRan": bool(ran), "answer": answer}))


asyncio.run(main())
