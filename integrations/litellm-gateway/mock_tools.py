"""The example agents' tools as an MCP server, so they run outside the agent and an AI gateway
can stand between the agent and them. Streamable HTTP on `/mcp`. It serves the conformance tools
and every governed demo's tool (../demo-runner/catalog.py), answering as the demo says.

Every call that reaches a tool is appended to TOOL_LOG (one JSON line: tool and arguments), which
is how a run tells whether the gateway let it through: the agent itself never sees the tool run.

    python mock_tools.py [port]      # default 18765; MOCK_TOOLS_HOST (127.0.0.1; 0.0.0.0 in a container)
"""
import json
import os
import sys
from pathlib import Path

from mcp.server.mcpserver import MCPServer  # mcp 2.x (what LiteLLM's proxy requires)

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18765
LOG = Path(os.environ.get("TOOL_LOG", Path(__file__).resolve().parent / ".tools.jsonl"))

HOST = os.environ.get("MOCK_TOOLS_HOST", "127.0.0.1")
mcp = MCPServer("example-tools")


def _ran(tool: str, **arguments) -> None:
    with LOG.open("a") as f:
        f.write(json.dumps({"tool": tool, "arguments": arguments}) + "\n")


@mcp.tool()
def send_customer_letter(reference: str) -> str:
    """Send the standard claims letter to a customer."""
    _ran("send_customer_letter", reference=reference)
    return f"sent to {reference}"


@mcp.tool()
def delete_customer(reference: str) -> str:
    """Delete a customer record."""
    _ran("delete_customer", reference=reference)
    return f"deleted {reference}"


@mcp.tool()
def approve_refund(reference: str) -> str:
    """Approve a refund for a claim."""
    _ran("approve_refund", reference=reference)
    return f"refund approved for {reference}"


def _demo_tool(demo):
    def run(reference: str) -> str:
        _ran(demo.tool, reference=reference)
        return demo.result.format(reference=reference)

    return run


def _demo_tools() -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "demo-runner"))
    from catalog import DEMOS

    for demo in DEMOS:
        mcp.tool(name=demo.tool, description=demo.tool_description)(_demo_tool(demo))


_demo_tools()

if __name__ == "__main__":
    mcp.run(transport="streamable-http", host=HOST, port=PORT)
