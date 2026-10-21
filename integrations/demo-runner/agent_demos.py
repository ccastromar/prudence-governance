"""Governed demos for Google ADK, the OpenAI Agents SDK and Microsoft Agent Framework.

Each is a real agent of its framework with one governed tool, behind that framework's Prudence
adapter (an ADK runner plugin, an Agents SDK tool guardrail, Agent Framework middleware). The
model is an OpenAI-compatible Chat Completions endpoint: PRUDENCE_DEMO_LLM_BASE_URL when set
(e.g. AOS's deterministic mock LLM), otherwise a scripted stand-in started in this process
(fake_llm.py, the conformance run's). Neither is a language model, and each run says which
endpoint answered.

ADK reaches the endpoint through `ChatCompletionsLlm` below rather than LiteLLM: LiteLLM pins
`openai` 2.x, while the Agents SDK and Agent Framework need 3.x, and this runner is one image.
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import threading
from pathlib import Path
from typing import Any, AsyncGenerator, Callable

import httpx
from google.adk.agents import LlmAgent
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.adk.runners import InMemoryRunner
from google.genai import types

LLM_BASE_URL = os.getenv("PRUDENCE_DEMO_LLM_BASE_URL", "").strip().rstrip("/")
LLM_MODEL = os.getenv("PRUDENCE_DEMO_LLM_MODEL", "aos-deterministic-mock").strip()
LLM_API_KEY = os.getenv("PRUDENCE_DEMO_LLM_API_KEY", "deterministic-demo-only").strip()
SUBJECT = "service-account-prudence-demo-runner"

_scripted: dict[str, str] = {}
_scripted_lock = threading.Lock()


def endpoint() -> tuple[str, str, str]:
    """(base URL, model name, label shown in the console)."""
    if LLM_BASE_URL:
        return LLM_BASE_URL, LLM_MODEL, f"{LLM_MODEL} via {LLM_BASE_URL}"
    with _scripted_lock:
        if "url" not in _scripted:
            here = Path(__file__).resolve().parent
            sys.path.extend([str(here), str(here.parent / "conformance")])
            from fake_llm import MODEL, serve  # in the image it sits next to this file

            _, _scripted["url"] = serve()
            _scripted["model"] = MODEL
    return _scripted["url"], _scripted["model"], f"{_scripted['model']} (scripted, in-process; not a language model)"


# --- Google ADK -------------------------------------------------------------------------------

def _json_schema(schema: Any) -> dict:
    """A google.genai Schema (uppercase types) as plain JSON Schema."""
    raw = schema.model_dump(exclude_none=True, mode="json") if hasattr(schema, "model_dump") else dict(schema or {})

    def fix(node: dict) -> dict:
        out = {}
        for key, value in node.items():
            if key == "type" and isinstance(value, str):
                out[key] = value.lower()
            elif key == "properties" and isinstance(value, dict):
                out[key] = {k: fix(v) for k, v in value.items()}
            elif key == "items" and isinstance(value, dict):
                out[key] = fix(value)
            elif key in ("required", "description", "enum"):
                out[key] = value
        return out

    return fix(raw) or {"type": "object", "properties": {}}


class ChatCompletionsLlm(BaseLlm):
    """An ADK model backed by any OpenAI-compatible Chat Completions endpoint (non-streaming)."""

    base_url: str
    api_key: str = "none"

    async def generate_content_async(self, llm_request: LlmRequest, stream: bool = False) -> AsyncGenerator[LlmResponse, None]:
        messages: list[dict[str, Any]] = []
        system = getattr(llm_request.config, "system_instruction", None)
        if system:
            text = system if isinstance(system, str) else " ".join(p.text or "" for p in system.parts or [])
            messages.append({"role": "system", "content": text})
        for content in llm_request.contents:
            for part in content.parts or []:
                if part.function_call:
                    fc = part.function_call
                    messages.append({"role": "assistant", "content": None, "tool_calls": [{
                        "id": fc.id or fc.name, "type": "function",
                        "function": {"name": fc.name, "arguments": json.dumps(fc.args or {})}}]})
                elif part.function_response:
                    fr = part.function_response
                    messages.append({"role": "tool", "tool_call_id": fr.id or fr.name,
                                     "content": json.dumps(fr.response or {})})
                elif part.text:
                    messages.append({"role": "assistant" if content.role == "model" else "user", "content": part.text})
        tools = []
        for tool in getattr(llm_request.config, "tools", None) or []:
            for decl in getattr(tool, "function_declarations", None) or []:
                params = decl.parameters_json_schema or (_json_schema(decl.parameters) if decl.parameters else None)
                tools.append({"type": "function", "function": {
                    "name": decl.name, "description": decl.description or "",
                    "parameters": params or {"type": "object", "properties": {}}}})
        body: dict[str, Any] = {"model": self.model, "messages": messages}
        if tools:
            body["tools"] = tools
        async with httpx.AsyncClient(timeout=20) as client:
            r = await client.post(f"{self.base_url}/chat/completions", json=body,
                                  headers={"Authorization": f"Bearer {self.api_key}"})
            r.raise_for_status()
        message = r.json()["choices"][0]["message"]
        if message.get("tool_calls"):
            parts = [types.Part(function_call=types.FunctionCall(
                id=c.get("id"), name=c["function"]["name"], args=json.loads(c["function"].get("arguments") or "{}")))
                for c in message["tool_calls"]]
        else:
            parts = [types.Part(text=message.get("content") or "")]
        yield LlmResponse(content=types.Content(role="model", parts=parts))


def run_claims_letter(system_id: str, gate, evidence) -> dict[str, Any]:
    from prudence_adk import PrudencePlugin

    ran: list[str] = []

    def send_claims_letter(claim_id: str) -> dict:
        """Send the standard acknowledgement letter for a claim."""
        ran.append(claim_id)
        return {"status": "queued", "claim": claim_id, "template": "acknowledgement-v3"}

    base, model, label = endpoint()

    async def main() -> str:
        agent = LlmAgent(name="claims_assistant", model=ChatCompletionsLlm(model=model, base_url=base, api_key=LLM_API_KEY),
                         instruction="Use your tools to do what the user asks.", tools=[send_claims_letter])
        plugin = PrudencePlugin(system_id, gate=gate, evidence=evidence,
                                pipelines={"send_claims_letter": "pipeline_claims_letter"})
        runner = InMemoryRunner(agent=agent, app_name="prudence-demo", plugins=[plugin])
        session = await runner.session_service.create_session(app_name="prudence-demo", user_id="demo")
        answer = ""
        message = types.Content(role="user", parts=[types.Part(text="Send the acknowledgement letter for claim CL-2041")])
        async for event in runner.run_async(user_id="demo", session_id=session.id, new_message=message):
            for part in (event.content.parts if event.content and event.content.parts else []):
                if part.text:
                    answer = part.text
        return answer

    return {"output": asyncio.run(main()), "continued": bool(ran), "model": label}


# --- OpenAI Agents SDK ------------------------------------------------------------------------

def run_refund_check(system_id: str, gate, evidence) -> dict[str, Any]:
    from agents import Agent, OpenAIChatCompletionsModel, Runner, function_tool, set_tracing_disabled
    from openai import AsyncOpenAI

    from prudence_openai_agents import prudence_guardrail

    set_tracing_disabled(True)  # nothing leaves for OpenAI's tracing service
    ran: list[str] = []
    guard = prudence_guardrail(system_id, gate=gate, evidence=evidence,
                               pipelines={"approve_refund": "pipeline_refund_check"})

    @function_tool(tool_input_guardrails=[guard])
    def approve_refund(order_id: str) -> str:
        """Approve a goodwill refund under 50 EUR for an order."""
        ran.append(order_id)
        return f"refund approved for {order_id}: 20.00 EUR goodwill"

    base, model, label = endpoint()

    async def main() -> str:
        agent = Agent(name="refunds_assistant", instructions="Use your tools to do what the user asks.",
                      tools=[approve_refund],
                      model=OpenAIChatCompletionsModel(model=model, openai_client=AsyncOpenAI(base_url=base, api_key=LLM_API_KEY)))
        result = await Runner.run(agent, "Approve the goodwill refund for order ORD-7731")
        return str(result.final_output)

    return {"output": asyncio.run(main()), "continued": bool(ran), "model": label}


# --- Microsoft Agent Framework ----------------------------------------------------------------

def run_crm_update(system_id: str, gate, evidence) -> dict[str, Any]:
    from agent_framework import Agent, tool
    from agent_framework.openai import OpenAIChatCompletionClient

    from prudence_agent_framework import PrudenceMiddleware

    ran: list[str] = []

    @tool
    def log_customer_call(account_id: str) -> str:
        """Log a summary of the customer call on the CRM account."""
        ran.append(account_id)
        return f"call summary logged on {account_id}"

    base, model, label = endpoint()

    async def main() -> str:
        agent = Agent(
            client=OpenAIChatCompletionClient(model=model, base_url=base, api_key=LLM_API_KEY),
            instructions="Use your tools to do what the user asks.", tools=[log_customer_call],
            middleware=[PrudenceMiddleware(system_id, gate=gate, evidence=evidence,
                                           pipelines={"log_customer_call": "pipeline_crm_update"})])
        return (await agent.run("Log the call summary on account ACC-3310")).text

    return {"output": asyncio.run(main()), "continued": bool(ran), "model": label}


# demo key -> (framework, step, run function)
AGENT_DEMOS: dict[str, tuple[str, str, Callable[..., dict[str, Any]]]] = {
    "claims-letter": ("Google ADK", "Claims letter tool", run_claims_letter),
    "refund-check": ("OpenAI Agents SDK", "Refund approval tool", run_refund_check),
    "crm-update": ("Microsoft Agent Framework", "CRM update tool", run_crm_update),
}
