"""A Google ADK agent with one governed tool, as a company using ADK would write it.

The model is any OpenAI-compatible Chat Completions endpoint, through a small ADK model class
(`ChatCompletionsLlm`) rather than LiteLLM: LiteLLM pins `openai` 2.x, and this agent shares an
environment with frameworks that need 3.x (the demo runner's image A).

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line: {"toolRan": bool, "answer": str}.
"""
import asyncio
import json
import os
from typing import Any, AsyncGenerator

import httpx
from google.adk.agents import LlmAgent
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.adk.runners import InMemoryRunner
from google.genai import types

from prudence_adk import PrudenceGate, PrudencePlugin
from prudence_governance import ErnestEvidence

ran: list[str] = []

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


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


def run_tool(reference: str) -> dict:
    ran.append(reference)
    return {"status": "done", "result": RESULT.format(reference=reference)}


run_tool.__name__ = TOOL
run_tool.__doc__ = DESCRIPTION


async def main() -> None:
    agent = LlmAgent(
        name="claims_assistant",
        model=ChatCompletionsLlm(model=os.environ.get("LLM_MODEL", "conformance-scripted"),
                                 base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none")),
        instruction="Use your tools to do what the user asks.",
        tools=[run_tool],
    )
    governance = PrudencePlugin(
        os.environ["AI_SYSTEM_ID"],
        gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
        pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
        evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
    )
    runner = InMemoryRunner(agent=agent, app_name="conformance", plugins=[governance])
    session = await runner.session_service.create_session(app_name="conformance", user_id="tester")
    answer = ""
    message = types.Content(role="user", parts=[types.Part(text=TASK)])
    async for event in runner.run_async(user_id="tester", session_id=session.id, new_message=message):
        for part in (event.content.parts if event.content and event.content.parts else []):
            if part.text:
                answer = part.text
    await asyncio.sleep(0.5)  # the plugin reports in the background
    print(json.dumps({"toolRan": bool(ran), "answer": answer}))


asyncio.run(main())
