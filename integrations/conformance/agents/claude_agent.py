"""A Claude Agent SDK agent with one governed MCP tool, as a company using it would write it.

The SDK drives its bundled Claude CLI, pointed here at the scripted model (ANTHROPIC_BASE_URL is
the root of LLM_URL); the CLI runs with its own built-in tools off, no user or project settings,
and a throw-away config directory, so nothing of the machine's own Claude setup is used.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os
import tempfile

from claude_agent_sdk import (AssistantMessage, ClaudeAgentOptions, ResultMessage, TextBlock,
                              create_sdk_mcp_server, query, tool)

from prudence_claude_agent import PrudenceGate, PrudenceHook
from prudence_governance import ErnestEvidence

ran: list[str] = []


@tool("send_customer_letter", "Send the standard claims letter to a customer.", {"customer_id": str})
async def send_customer_letter(args: dict) -> dict:
    ran.append(args["customer_id"])
    return {"content": [{"type": "text", "text": f"sent to {args['customer_id']}"}]}


async def main() -> None:
    governance = PrudenceHook(
        os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={"mcp__claims__send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
        evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
    )
    with tempfile.TemporaryDirectory() as home:
        options = ClaudeAgentOptions(
            model="conformance-scripted", tools=[], setting_sources=[], max_turns=3, cwd=home,
            mcp_servers={"claims": create_sdk_mcp_server("claims", tools=[send_customer_letter])},
            allowed_tools=["mcp__claims__send_customer_letter"],
            hooks={"PreToolUse": [governance.matcher()]},
            env={"ANTHROPIC_BASE_URL": os.environ["LLM_URL"].removesuffix("/v1"),
                 "ANTHROPIC_API_KEY": "none", "CLAUDE_CONFIG_DIR": home, "ENABLE_TOOL_SEARCH": "false",
                 "DISABLE_TELEMETRY": "1", "DISABLE_AUTOUPDATER": "1",
                 "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
        )
        answer = ""
        async for message in query(prompt="Send the letter to customer C-1", options=options):
            if isinstance(message, AssistantMessage):
                answer += "".join(b.text for b in message.content if isinstance(b, TextBlock))
            elif isinstance(message, ResultMessage) and message.result:
                answer = message.result
    await asyncio.sleep(0.5)  # the adapter reports in the background
    print(json.dumps({"toolRan": bool(ran), "answer": answer}))


asyncio.run(main())
