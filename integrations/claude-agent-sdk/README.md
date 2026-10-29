# prudence-claude-agent — Prudence governance for the Claude Agent SDK

A **PreToolUse hook** that asks Prudence before every tool call of the agent: built-in tools
(Bash, Write, WebFetch, …) and MCP tools (`mcp__<server>__<tool>`). Only `ALLOW` lets the
call through; anything else denies it and Claude gets the refusal as the reason. Allowed calls
are reported to Prudence.

```python
from claude_agent_sdk import ClaudeAgentOptions, query
from prudence_claude_agent import PrudenceHook

prudence = PrudenceHook(ai_system_id="68bf42c1-…",
                        pipelines={"mcp__claims__send_customer_letter": "claims_letter"},
                        ungoverned={"Read", "Grep", "Glob"})
options = ClaudeAgentOptions(mcp_servers={"claims": claims_server},
                             hooks={"PreToolUse": [prudence.matcher()]})
async for message in query(prompt="Send the letter for claim 42", options=options):
    ...
```

- **Only takes permission away:** on `ALLOW` the hook answers nothing, so the SDK's permission
  mode and `allowed_tools` still apply. Prudence never grants a call the host would refuse.
- **Every tool by default:** built-in tools are governed too. List read-only ones in
  `ungoverned`, or pass a pattern to `matcher("mcp__claims__.*")` to govern only some tools.
- **Pipeline per tool:** `pipelines[tool name]` (the full name, `mcp__server__tool` for MCP
  tools), or the tool's name. A tool nobody granted is denied.
- **AI system:** one id for the agent, or a mapping per tool name.
- **Reported when allowed:** the execution is reported when Prudence allows it, under the SDK's
  tool use id. If the SDK's own permissions then refuse the call, Prudence still has the report.
- **With Ernest in the suite:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with claude-agent-sdk 0.2. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
