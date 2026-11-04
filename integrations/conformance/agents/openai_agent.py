"""An OpenAI Agents SDK agent with one governed tool, as a company using the SDK would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.

With GOVERNANCE_MODE=gateway the same agent has no adapter: its model and its tool both go
through an AI gateway (integrations/litellm-gateway), which asks Prudence. It reads LLM_URL and
LLM_API_KEY (the gateway's), MCP_URL (the gateway's MCP endpoint), AGENT_TOKEN (its own access
token, issued for Prudence, sent on both) and DEMO_TOOL; the tool runs on the far side of the
gateway, so it prints {"toolRan": null, "answer": str} and the caller reads the tool server's log.
A model call the gateway refuses ends the run with an error and no tool call.
"""
import asyncio
import json
import os

from agents import Agent, OpenAIChatCompletionsModel, Runner, function_tool, set_tracing_disabled
from openai import AsyncOpenAI

set_tracing_disabled(True)  # no traces to OpenAI from a test
ran: list[str] = []
GATEWAY = os.environ.get("GOVERNANCE_MODE", "adapter") == "gateway"

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


def system_header() -> dict[str, str]:
    """The AI system the agent acts for, when AI_SYSTEM_ID names one (a demo run does)."""
    return {"x-prudence-ai-system": os.environ["AI_SYSTEM_ID"]} if os.getenv("AI_SYSTEM_ID") else {}


def model() -> OpenAIChatCompletionsModel:
    # Through the gateway the model call carries the agent's own token too, in its own header:
    # `Authorization` is the gateway key there.
    headers = {"x-prudence-agent-token": os.environ["AGENT_TOKEN"], **system_header()} if GATEWAY and os.getenv("AGENT_TOKEN") else None
    return OpenAIChatCompletionsModel(
        model=os.environ.get("LLM_MODEL", "conformance-scripted"),
        openai_client=AsyncOpenAI(base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"),
                                  default_headers=headers),
    )


async def through_gateway() -> None:
    from agents.mcp import MCPServerStreamableHttp

    headers = {"x-litellm-api-key": os.environ.get("LLM_API_KEY", ""), **system_header()}
    if os.getenv("AGENT_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['AGENT_TOKEN']}"
    async with MCPServerStreamableHttp(
        params={"url": os.environ["MCP_URL"], "headers": headers}, name="gateway",
        tool_filter=lambda _ctx, tool: tool.name.endswith(TOOL),
    ) as tools:
        agent = Agent(name="claims_assistant", instructions="Use your tools to do what the user asks.",
                      mcp_servers=[tools], model=model())
        result = await Runner.run(agent, TASK)
    print(json.dumps({"toolRan": None, "answer": str(result.final_output)}))


if GATEWAY:
    asyncio.run(through_gateway())
    raise SystemExit(0)

from prudence_governance import ErnestEvidence  # noqa: E402
from prudence_openai_agents import PrudenceGate, prudence_guardrail  # noqa: E402

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
    agent = Agent(name="claims_assistant", instructions="Use your tools to do what the user asks.",
                  tools=[run_tool], model=model())
    result = await Runner.run(agent, TASK)
    print(json.dumps({"toolRan": bool(ran), "answer": str(result.final_output)}))


asyncio.run(main())
