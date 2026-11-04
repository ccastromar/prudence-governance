"""A Claude Agent SDK agent with one governed MCP tool, as a company using it would write it.

The SDK drives its bundled Claude CLI, pointed here at the scripted model (ANTHROPIC_BASE_URL is
the root of LLM_URL); the CLI runs with its own built-in tools off, no user or project settings,
and a throw-away config directory, so nothing of the machine's own Claude setup is used.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL; prints one JSON line:
{"toolRan": bool, "answer": str}.

GOVERNANCE_MODE=gateway: no hook; the CLI's model calls go through an AI gateway (its Anthropic
Messages endpoint, the agent's token in ANTHROPIC_CUSTOM_HEADERS) and the tool runs over MCP
through it too (gateway_mode.py).
"""
import asyncio
import json
import os
import tempfile

from claude_agent_sdk import (AssistantMessage, ClaudeAgentOptions, ResultMessage, TextBlock,
                              create_sdk_mcp_server, query, tool)

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


@tool(TOOL, DESCRIPTION, {"reference": str})
async def run_tool(args: dict) -> dict:
    if GATEWAY:
        return {"content": [{"type": "text", "text": await asyncio.to_thread(
            call_tool, TOOL, {"reference": args["reference"]})}]}
    ran.append(args["reference"])
    return {"content": [{"type": "text", "text": RESULT.format(reference=args["reference"])}]}


async def main() -> None:
    hooks = {}
    if not GATEWAY:
        from prudence_claude_agent import PrudenceGate, PrudenceHook
        from prudence_governance import ErnestEvidence

        governance = PrudenceHook(
            os.environ["AI_SYSTEM_ID"],
            gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
            pipelines={f"mcp__claims__{TOOL}": os.environ.get("PIPELINE", "send_customer_letter")},
            evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
        )
        hooks = {"PreToolUse": [governance.matcher()]}
    gateway_env = {}
    if GATEWAY:
        gateway_env = {"ANTHROPIC_API_KEY": os.environ.get("LLM_API_KEY", "none"),
                       "ANTHROPIC_CUSTOM_HEADERS": "\n".join(f"{k}: {v}" for k, v in model_headers().items())}
    with tempfile.TemporaryDirectory() as home:
        options = ClaudeAgentOptions(
            model=os.environ.get("LLM_MODEL", "conformance-scripted") if GATEWAY else "conformance-scripted",
            tools=[], setting_sources=[], max_turns=3, cwd=home,
            mcp_servers={"claims": create_sdk_mcp_server("claims", tools=[run_tool])},
            allowed_tools=[f"mcp__claims__{TOOL}"],
            hooks=hooks,
            env={"ANTHROPIC_BASE_URL": os.environ["LLM_URL"].removesuffix("/v1"),
                 "ANTHROPIC_API_KEY": "none", "CLAUDE_CONFIG_DIR": home, "ENABLE_TOOL_SEARCH": "false",
                 "DISABLE_TELEMETRY": "1", "DISABLE_AUTOUPDATER": "1",
                 "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1", **gateway_env},
        )
        answer = ""
        async for message in query(prompt=TASK, options=options):
            if isinstance(message, AssistantMessage):
                answer += "".join(b.text for b in message.content if isinstance(b, TextBlock))
            elif isinstance(message, ResultMessage) and message.result:
                answer = message.result
    await asyncio.sleep(0.5)  # the adapter reports in the background
    print(json.dumps({"toolRan": None if GATEWAY else bool(ran), "answer": answer}))


asyncio.run(main())
