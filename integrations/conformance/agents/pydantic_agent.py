"""A Pydantic AI agent with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line: {"toolRan": bool, "answer": str}.

GOVERNANCE_MODE=gateway: no adapter, model and tool through an AI gateway (gateway_mode.py).
"""
import json
import os

from openai import AsyncOpenAI
from pydantic_ai import Agent, RunContext
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

GATEWAY = os.environ.get("GOVERNANCE_MODE", "adapter") == "gateway"
if GATEWAY:  # runners start agents with python -I, which leaves this directory off the path
    import sys

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from gateway_mode import call_tool, model_headers
else:
    def model_headers() -> dict:
        return {}

ran: list[str] = []

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")
model = OpenAIChatModel(os.environ.get("LLM_MODEL", "conformance-scripted"),
                        provider=OpenAIProvider(openai_client=AsyncOpenAI(
                            base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"),
                            default_headers=model_headers() or None)))

if GATEWAY:
    agent = Agent(model, instructions="Use your tools to do what the user asks.")

    @agent.tool_plain(name=TOOL, description=DESCRIPTION)
    def run_tool(reference: str) -> str:
        """Send the standard claims letter to a customer."""
        return call_tool(TOOL, {"reference": reference})

    result = agent.run_sync(TASK)
    print(json.dumps({"toolRan": None, "answer": str(result.output)}))
    raise SystemExit(0)

from prudence_governance import ErnestEvidence, PrudenceGate  # noqa: E402
from prudence_pydantic import GovDeps, governed  # noqa: E402

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
