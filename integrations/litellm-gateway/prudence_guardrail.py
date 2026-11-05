"""Prudence as the policy decision point of a LiteLLM gateway (contract/identity/).

Before each MCP tool call and each model call that crosses the gateway, this guardrail asks
Prudence's AuthZEN endpoint whether the agent behind it may run that tool (`{type: tool}`) or use
that model (`{type: model}`), and blocks the call unless the answer is an ALLOW about that very
agent. It fails closed: no agent token, Prudence unreachable, a slow or unreadable answer, or an
answer about another agent all block the call.

Identity, without trusting anything the agent merely says:
- the agent presents its own access token, issued for Prudence: as the `Authorization: Bearer`
  of its MCP connection (the LiteLLM key travels in `x-litellm-api-key`), and in
  `x-prudence-agent-token` on model calls (where `Authorization` is the LiteLLM key); the
  guardrail forwards it as `context.agent.token`, so Prudence validates it and the agent is
  `verified`;
- the gateway authenticates to Prudence as itself (its own workload token), and Prudence lets it
  ask only for the agents its `speaks_for` lists.

Only identifiers go to Prudence: the tool's name, never its arguments.

After an allowed tool ran, it reports the execution (`POST /api/executions`) citing the decision,
the AI system Prudence resolved and the agent, so Prudence's drift sees what went through the
gateway. Reporting never blocks or changes the call. The gateway's identity needs
`execution.report` too.

Configuration (environment):
  PRUDENCE_URL                  Prudence's base URL
  PRUDENCE_TOKEN                the gateway's own access token, or else
  PRUDENCE_TOKEN_URL, PRUDENCE_CLIENT_ID, PRUDENCE_CLIENT_SECRET   client credentials
  PRUDENCE_TIMEOUT              seconds, default 3
  PRUDENCE_PRINCIPAL_MODE       how Prudence names principals (`prudence.identity.principal-mode`):
                                `legacy` (default) or `issuer-subject`; the guardrail names the
                                agent the same way to check Prudence decided about it
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
from fastapi import HTTPException
from litellm.integrations.custom_guardrail import CustomGuardrail

# LiteLLM loads this file by path, without its folder on sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from identity_gate import agent_named_by, gate  # noqa: E402

MCP_TOOL_CALL = "call_mcp_tool"
MODEL_CALLS = {"completion", "acompletion", "anthropic_messages", "responses", "aresponses"}
AGENT_TOKEN_HEADER = "x-prudence-agent-token"
AI_SYSTEM_HEADER = "x-prudence-ai-system"
RUNTIME = os.environ.get("PRUDENCE_RUNTIME", "litellm-gateway")
PRINCIPAL_MODE = os.environ.get("PRUDENCE_PRINCIPAL_MODE", "legacy")
ALLOWED_TTL = 600  # seconds an allowed call waits for its tool before it is forgotten
log = logging.getLogger("prudence_guardrail")


class PrudenceGuardrail(CustomGuardrail):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.url = os.environ["PRUDENCE_URL"].rstrip("/")
        self.timeout = float(os.environ.get("PRUDENCE_TIMEOUT", "3"))
        self._token: str | None = None
        self._token_expires = 0.0
        # Allowed calls waiting for their tool to finish, by LiteLLM call id.
        self._allowed: dict[str, dict] = {}

    async def async_pre_call_hook(self, user_api_key_dict, cache, data: dict, call_type):
        call_type = str(getattr(call_type, "value", call_type))
        if call_type == MCP_TOOL_CALL:
            tool = _tool_name(data)
            context = await self._decide("tool", tool, data.get("incoming_bearer_token"), _header(data, AI_SYSTEM_HEADER))
            # The same logging object reaches the post-call hook: its call id ties the two together.
            logging_obj = data.get("litellm_logging_obj")
            self._remember(getattr(logging_obj, "litellm_call_id", None), tool, context)
        elif call_type in MODEL_CALLS:
            # Read the agent's token and take it out of the request before anything logs it.
            await self._decide("model", str(data.get("model") or ""), _take_agent_token(data),
                               _header(data, AI_SYSTEM_HEADER))
        # Listing tools, and anything else, decides nothing.
        return data

    async def _decide(self, kind: str, name: str, agent_token: str | None, system: str | None = None) -> dict:
        """Asks Prudence about `kind` (`tool` or `model`) `name` for the agent of `agent_token`, in
        the AI system the agent names, if any (Prudence checks the system declares the agent);
        returns the decision's context on an ALLOW about that agent, blocks the call otherwise."""
        if not agent_token:
            _block(f"{kind} {name}: the agent presented no token of its own")
        agent = agent_named_by(agent_token, PRINCIPAL_MODE)
        if not agent:
            _block(f"{kind} {name}: the agent's token names no one")
        try:
            status, body = await self._evaluate(kind, name, agent_token, system)
        except Exception as failure:  # unreachable, timeout, refused: never a yes
            _block(f"{kind} {name}: Prudence could not decide ({type(failure).__name__})")
        # contract/identity/ section 12: an ALLOW about someone else (an older Prudence deciding
        # about the gateway) is no ALLOW.
        verdict = gate("authzen", status, body, agent)
        if not verdict["run"]:
            _block(f"{kind} {name}: {verdict.get('reasonCode') or verdict.get('reason') or f'Prudence answered {status}'}")
        # It forwarded the agent's own token, so Prudence must have validated it.
        if verdict["facts"]["identity"].get("agentAssurance") != "verified":
            _block(f"{kind} {name}: Prudence did not verify the agent's token")
        return verdict["facts"]

    async def async_post_mcp_tool_call_hook(self, kwargs, response_obj, start_time, end_time):
        """After an allowed tool ran: report the execution, citing the decision that allowed it,
        so drift sees what went through the gateway. Never blocks or changes the result."""
        allowed = self._allowed.pop(kwargs.get("litellm_call_id"), None)
        if allowed is not None:
            asyncio.create_task(self._report(kwargs.get("litellm_call_id"), allowed))
        return None

    async def _report(self, call_id: str, allowed: dict) -> None:
        body = {
            "runtime": RUNTIME,
            "executionId": call_id,
            "decisionId": allowed["decisionId"],
            "aiSystemId": allowed["aiSystemId"],
            "pipeline": f"tool:{allowed['tool']}",
            "startedAt": allowed["startedAt"],
            "agent": allowed["agent"],
        }
        # Contract/decision section 3: 5xx or no answer is retried; anything else is dropped.
        for attempt in range(3):
            try:
                async with httpx.AsyncClient(timeout=self.timeout) as client:
                    response = await client.post(
                        f"{self.url}/api/executions", json=body,
                        headers={"Authorization": f"Bearer {await self._gateway_token(client)}"})
                if response.status_code < 500:
                    if response.status_code >= 300:
                        log.warning("execution report %s refused: %s %s", call_id,
                                    response.status_code, response.text[:200])
                    return
            except Exception as failure:
                log.warning("execution report %s failed: %s", call_id, failure)
            await asyncio.sleep(0.5 * 2 ** attempt)
        log.warning("execution report %s given up", call_id)

    def _remember(self, call_id, tool: str, context: dict) -> None:
        now = time.time()
        for stale in [k for k, v in self._allowed.items() if now - v["at"] > ALLOWED_TTL]:
            self._allowed.pop(stale, None)
        if call_id:
            self._allowed[call_id] = {
                "at": now,
                "startedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "tool": tool,
                "decisionId": context.get("decisionId"),
                "aiSystemId": context.get("aiSystemId"),
                "agent": (context.get("identity") or {}).get("agent"),
            }

    async def _evaluate(self, kind: str, name: str, agent_token: str, system: str | None = None) -> tuple[int, str]:
        resource: dict = {"type": kind, "id": name}
        if system:
            resource["properties"] = {"aiSystemId": system}
        body = {
            # AuthZEN requires a subject; Prudence takes the agent from its token.
            "subject": {"type": "agent", "id": "from-token"},
            "resource": resource,
            "action": {"name": "tools/call" if kind == "tool" else "invoke"},
            "context": {"agent": {"token": agent_token}},
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.url}/access/v1/evaluation",
                json=body,
                headers={"Authorization": f"Bearer {await self._gateway_token(client)}"},
            )
        return response.status_code, response.text

    async def _gateway_token(self, client: httpx.AsyncClient) -> str:
        if os.environ.get("PRUDENCE_TOKEN"):
            return os.environ["PRUDENCE_TOKEN"]
        if self._token and time.time() < self._token_expires - 30:
            return self._token
        response = await client.post(
            os.environ["PRUDENCE_TOKEN_URL"],
            data={"grant_type": "client_credentials",
                  "client_id": os.environ["PRUDENCE_CLIENT_ID"],
                  "client_secret": os.environ["PRUDENCE_CLIENT_SECRET"]},
        )
        response.raise_for_status()
        token = response.json()
        self._token = token["access_token"]
        self._token_expires = time.time() + float(token.get("expires_in", 60))
        return self._token


def _header(data: dict, name: str) -> str | None:
    """A request header as LiteLLM hands it to the guardrail (model calls and MCP calls keep the
    client's headers in different places)."""
    copies = [(data.get("proxy_server_request") or {}).get("headers"),
              (data.get("metadata") or {}).get("headers"),
              (data.get("litellm_metadata") or {}).get("headers")]
    for headers in copies:
        if isinstance(headers, dict):
            for key, value in headers.items():
                if key.lower() == name and value:
                    return str(value)
    return None


def _take_agent_token(data: dict) -> str | None:
    """The agent's token on a model call, from `x-prudence-agent-token`. LiteLLM masks only the
    headers it knows carry credentials, and this is not one of them, so the guardrail removes it
    from every copy of the request's headers it finds once it has read it."""
    token = None
    copies = [(data.get("proxy_server_request") or {}).get("headers"),
              (data.get("metadata") or {}).get("headers"),
              (data.get("litellm_metadata") or {}).get("headers")]
    for headers in copies:
        if not isinstance(headers, dict):
            continue
        for key in [k for k in headers if k.lower() == AGENT_TOKEN_HEADER]:
            token = token or headers[key]
            del headers[key]
    return token


def _tool_name(data: dict) -> str:
    """The tool as its server names it: LiteLLM may prefix it with the server's name."""
    tool = str(data.get("mcp_tool_name") or "")
    server = str(data.get("mcp_server_name") or "")
    for separator in ("-", "__", "/"):
        if server and tool.startswith(server + separator):
            return tool[len(server) + len(separator):]
    return tool


def _block(reason: str):
    raise HTTPException(status_code=403, detail=f"Blocked by Prudence: {reason}")
