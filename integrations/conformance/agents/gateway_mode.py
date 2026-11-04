"""What an example agent needs to run with no adapter behind an AI gateway (GOVERNANCE_MODE=gateway,
integrations/litellm-gateway). Nothing here governs: the gateway asks Prudence.

- `model_headers()`: the agent's own token for its model calls, in `x-prudence-agent-token`
  (`Authorization` is the gateway's key there), and the AI system it acts for, when AI_SYSTEM_ID
  names one, in `x-prudence-ai-system` (sent on tool calls too).
- `call_tool(name, arguments)`: runs the tool on the far side of the gateway, over MCP
  (streamable HTTP, JSON-RPC), presenting the agent's token as `Authorization: Bearer` and the
  gateway key in `x-litellm-api-key`. A refusal comes back as the tool's text, as a tool error
  would, so the agent goes on and the run reads the tool server's log for what really ran.

The OpenAI Agents SDK example uses the SDK's own MCP client instead; the other frameworks call
the tool through this small client so their example code stays the same apart from the tool body.
Reads GOVERNANCE_MODE, MCP_URL, LLM_API_KEY, AGENT_TOKEN and AI_SYSTEM_ID. Agents import it only
in gateway mode, from their own directory (runners start them with python -I).
"""
from __future__ import annotations

import json
import os

import httpx

GATEWAY = os.environ.get("GOVERNANCE_MODE", "adapter") == "gateway"
_PROTOCOL = "2025-06-18"


def _system() -> dict[str, str]:
    """The AI system the agent acts for, when it knows it (a demo run names one): the gateway
    passes it to Prudence, which checks that the system declares this agent."""
    system = os.environ.get("AI_SYSTEM_ID")
    return {"x-prudence-ai-system": system} if system else {}


def model_headers() -> dict[str, str]:
    token = os.environ.get("AGENT_TOKEN")
    return {"x-prudence-agent-token": token, **_system()} if GATEWAY and token else {}


def call_tool(name: str, arguments: dict) -> str:
    headers = {"x-litellm-api-key": os.environ.get("LLM_API_KEY", ""),
               "Accept": "application/json, text/event-stream", "Content-Type": "application/json",
               **_system()}
    if os.getenv("AGENT_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['AGENT_TOKEN']}"
    url = os.environ["MCP_URL"]
    with httpx.Client(timeout=30) as client:
        init = client.post(url, headers=headers, json={
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": _PROTOCOL, "capabilities": {},
                       "clientInfo": {"name": "example-agent", "version": "1"}}})
        if init.status_code >= 400:
            return f"tool unavailable: {init.status_code} {init.text[:200]}"
        if init.headers.get("mcp-session-id"):
            headers["mcp-session-id"] = init.headers["mcp-session-id"]
        headers["mcp-protocol-version"] = _PROTOCOL
        client.post(url, headers=headers, json={"jsonrpc": "2.0", "method": "notifications/initialized"})
        listed = _rpc(client, url, headers, 2, "tools/list", {})
        names = [t["name"] for t in (listed.get("result") or {}).get("tools", [])]
        # The gateway may prefix a tool with its server's name.
        full = next((n for n in names if n == name or n.endswith(f"-{name}") or n.endswith(f"/{name}")), name)
        called = _rpc(client, url, headers, 3, "tools/call", {"name": full, "arguments": arguments})
    if "error" in called:
        return f"refused: {called['error'].get('message')}"
    content = (called.get("result") or {}).get("content") or []
    return " ".join(c.get("text", "") for c in content if c.get("type") == "text") or json.dumps(called)


def _rpc(client: httpx.Client, url: str, headers: dict, id_: int, method: str, params: dict) -> dict:
    r = client.post(url, headers=headers, json={"jsonrpc": "2.0", "id": id_, "method": method, "params": params})
    if r.status_code >= 400:
        return {"error": {"message": f"{r.status_code} {r.text[:300]}"}}
    if r.headers.get("content-type", "").startswith("text/event-stream"):
        for line in r.text.splitlines():
            if line.startswith("data:"):
                message = json.loads(line[5:].strip())
                if message.get("id") == id_:
                    return message
        return {"error": {"message": "no answer in the event stream"}}
    return r.json()
