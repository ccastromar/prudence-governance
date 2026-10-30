"""A Pydantic AI agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line: {"toolRan": bool, "answer": str}.
"""
import json
import os

from pydantic_ai import Agent, RunContext
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

from prudence_governance import ErnestEvidence, PrudenceGate
from prudence_pydantic import GovDeps, governed

ran: list[str] = []

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")
model = OpenAIChatModel(os.environ.get("LLM_MODEL", "conformance-scripted"),
                        provider=OpenAIProvider(base_url=os.environ["LLM_URL"],
                                                api_key=os.environ.get("LLM_API_KEY", "none")))
agent = Agent(model, deps_type=GovDeps, instructions="Use your tools to do what the user asks.")


@agent.tool(name=TOOL, description=DESCRIPTION)
@governed(pipeline=os.environ.get("PIPELINE", "send_customer_letter"))
def run_tool(ctx: RunContext[GovDeps], reference: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(reference)
    return RESULT.format(reference=reference)


system = os.environ["AI_SYSTEM_ID"]
deps = GovDeps(
    subject="pydantic-ai",  # the dev profile's caller; a secured PDP takes it from the token
    ai_system_id=system,
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
)
result = agent.run_sync(TASK, deps=deps)
print(json.dumps({"toolRan": bool(ran), "answer": str(result.output)}))
