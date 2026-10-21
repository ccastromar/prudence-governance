"""A Microsoft Agent Framework agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID and PIPELINE; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os

from agent_framework import Agent, tool
from agent_framework.openai import OpenAIChatCompletionClient

from prudence_agent_framework import PrudenceGate, PrudenceMiddleware

ran: list[str] = []


@tool
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


async def main() -> None:
    client = OpenAIChatCompletionClient(model="conformance-scripted", base_url=os.environ["LLM_URL"], api_key="none")
    governance = PrudenceMiddleware(
        os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    )
    agent = Agent(client=client, instructions="Use your tools to do what the user asks.",
                  tools=[send_customer_letter], middleware=[governance])
    response = await agent.run("Send the letter to customer C-1")
    print(json.dumps({"toolRan": bool(ran), "answer": response.text}))


asyncio.run(main())
