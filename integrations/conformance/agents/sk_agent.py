"""A Semantic Kernel agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os

from openai import AsyncOpenAI
from semantic_kernel import Kernel
from semantic_kernel.agents import ChatCompletionAgent
from semantic_kernel.connectors.ai.open_ai import OpenAIChatCompletion
from semantic_kernel.filters import FilterTypes
from semantic_kernel.functions import kernel_function

from prudence_governance import ErnestEvidence
from prudence_semantic_kernel import PrudenceFilter, PrudenceGate

ran: list[str] = []


class Claims:
    @kernel_function
    def send_customer_letter(self, customer_id: str) -> str:
        """Send the standard claims letter to a customer."""
        ran.append(customer_id)
        return f"sent to {customer_id}"


async def main() -> None:
    kernel = Kernel()
    kernel.add_service(OpenAIChatCompletion(
        ai_model_id=os.environ.get("LLM_MODEL", "conformance-scripted"),
        async_client=AsyncOpenAI(base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))))
    kernel.add_plugin(Claims(), plugin_name="Claims")
    kernel.add_filter(FilterTypes.FUNCTION_INVOCATION, PrudenceFilter(
        os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
        evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
    ))
    agent = ChatCompletionAgent(kernel=kernel, name="claims_assistant",
                                instructions="Use your tools to do what the user asks.")
    response = await agent.get_response(messages="Send the letter to customer C-1")
    await asyncio.sleep(0.5)  # the adapter reports in the background
    print(json.dumps({"toolRan": bool(ran), "answer": str(response.content)}))


asyncio.run(main())
