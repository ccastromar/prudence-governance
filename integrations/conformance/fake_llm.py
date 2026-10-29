"""A scripted stand-in for an OpenAI-compatible model, so the conformance run needs no model and
no API key. It is not a language model and says so in its name (`conformance-scripted`).

Chat Completions (`POST /v1/chat/completions`) and Anthropic Messages (`POST /v1/messages`, what
the Claude Agent SDK's CLI speaks), streaming or not. Its whole behaviour:
- tools offered and no tool result yet: call the first tool, filling each required argument
  from its JSON schema (strings: the first id-like word in the user's message, such as
  "CL-2041", else "C-1"; numbers 1; booleans true);
- a tool result came back (a tool message, an Anthropic tool_result block, or smolagents'
  "Observation:" user message): answer with it ("Tool result: …");
- no tools: answer "OK".

Run it alone with `python fake_llm.py [port]`, or start it in-process with `serve(port)`.
"""
from __future__ import annotations

import json
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "conformance-scripted"


def _argument(schema: dict, text_default: str = "C-1"):
    kind = schema.get("type")
    if kind in ("number", "integer"):
        return 1
    if kind == "boolean":
        return True
    if kind == "array":
        return []
    if kind == "object":
        return {}
    return text_default


def _user_id(messages: list) -> str:
    """The first id-like word (ORD-7731, CL-2041) the user wrote, so the call reads naturally."""
    for m in messages:
        if m.get("role") == "user" and isinstance(m.get("content"), str):
            found = re.search(r"\b[A-Z]{1,5}-\d+\b", m["content"])
            if found:
                return found.group(0)
    return "C-1"


def reply(request: dict) -> dict:
    """The assistant message for a chat request: a tool call or text."""
    messages = request.get("messages") or []
    tools = request.get("tools") or []
    last = messages[-1] if messages else {}
    content = last.get("content")
    if isinstance(content, list):
        content = " ".join(str(p.get("text", p)) for p in content if isinstance(p, dict))
    if last.get("role") == "tool":
        return {"role": "assistant", "content": f"Tool result: {content}"}
    # smolagents hands a tool's output back as a user message "Observation:\n…" and expects the
    # answer through its final_answer tool.
    if last.get("role") == "user" and str(content).startswith("Observation:"):
        answer = f"Tool result: {str(content)[len('Observation:'):].strip()}"
        if any(t.get("function", t).get("name") == "final_answer" for t in tools):
            return {"role": "assistant", "content": None, "tool_calls": [{
                "id": f"call_{uuid.uuid4().hex[:12]}", "type": "function",
                "function": {"name": "final_answer", "arguments": json.dumps({"answer": answer})}}]}
        return {"role": "assistant", "content": answer}
    if tools:
        fn = tools[0].get("function", tools[0])
        params = fn.get("parameters") or {}
        props = params.get("properties") or {}
        default = _user_id(messages)
        args = {name: _argument(props.get(name, {}), default) for name in params.get("required", list(props))}
        return {"role": "assistant", "content": None, "tool_calls": [{
            "id": f"call_{uuid.uuid4().hex[:12]}", "type": "function",
            "function": {"name": fn["name"], "arguments": json.dumps(args)}}]}
    return {"role": "assistant", "content": "OK"}


def _text(content) -> str:
    if isinstance(content, str):
        return content
    return " ".join(_text(b.get("content", b.get("text", ""))) for b in content or [] if isinstance(b, dict))


def anthropic_reply(request: dict) -> list[dict]:
    """The content blocks for an Anthropic Messages request, by the same rules as `reply`. An MCP
    tool (`mcp__…`) is preferred over the CLI's own tools."""
    messages = request.get("messages") or []
    last = messages[-1] if messages else {}
    blocks = last.get("content") if isinstance(last.get("content"), list) else []
    results = [b for b in blocks if isinstance(b, dict) and b.get("type") == "tool_result"]
    if results:
        return [{"type": "text", "text": f"Tool result: {_text(results[-1].get('content'))}"}]
    tools = [t for t in request.get("tools") or [] if "input_schema" in t]
    if tools:
        fn = next((t for t in tools if t["name"].startswith("mcp__")), tools[0])
        schema = fn.get("input_schema") or {}
        props = schema.get("properties") or {}
        users = [{"role": "user", "content": _text(m.get("content"))} for m in messages if m.get("role") == "user"]
        default = _user_id(users)
        args = {name: _argument(props.get(name, {}), default) for name in schema.get("required", list(props))}
        return [{"type": "tool_use", "id": f"toolu_{uuid.uuid4().hex[:12]}", "name": fn["name"], "input": args}]
    return [{"type": "text", "text": "OK"}]


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):  # quiet
        pass

    def _json(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            return self._json(200, {"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        self._json(404, {"error": {"message": "not found"}})

    def _messages(self, request: dict):
        content = anthropic_reply(request)
        stop = "tool_use" if content[0]["type"] == "tool_use" else "end_turn"
        message = {"id": f"msg_{uuid.uuid4().hex[:12]}", "type": "message", "role": "assistant",
                   "model": MODEL, "content": content, "stop_reason": stop, "stop_sequence": None,
                   "usage": {"input_tokens": 1, "output_tokens": 1}}
        if not request.get("stream"):
            return self._json(200, message)
        # Streaming: each block in one delta, then the stop.
        events = [("message_start", {"message": {**message, "content": [], "stop_reason": None}})]
        for i, block in enumerate(content):
            if block["type"] == "tool_use":
                events += [("content_block_start", {"index": i, "content_block": {**block, "input": {}}}),
                           ("content_block_delta", {"index": i, "delta": {
                               "type": "input_json_delta", "partial_json": json.dumps(block["input"])}})]
            else:
                events += [("content_block_start", {"index": i, "content_block": {"type": "text", "text": ""}}),
                           ("content_block_delta", {"index": i, "delta": {"type": "text_delta", "text": block["text"]}})]
            events.append(("content_block_stop", {"index": i}))
        events += [("message_delta", {"delta": {"stop_reason": stop, "stop_sequence": None},
                                      "usage": {"output_tokens": 1}}),
                   ("message_stop", {})]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for name, data in events:
            self.wfile.write(f"event: {name}\ndata: {json.dumps({'type': name, **data})}\n\n".encode())

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        if path.endswith("/messages/count_tokens"):
            return self._json(200, {"input_tokens": 1})
        if path.endswith("/messages"):
            return self._messages(json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}"))
        if not path.endswith("/chat/completions"):
            return self._json(404, {"error": {"message": f"unsupported path {self.path}"}})
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        message = reply(request)
        finish = "tool_calls" if message.get("tool_calls") else "stop"
        base = {"id": f"chatcmpl-{uuid.uuid4().hex[:12]}", "created": int(time.time()), "model": MODEL}
        if not request.get("stream"):
            return self._json(200, {**base, "object": "chat.completion",
                                    "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
        # Streaming: the whole message in one delta, then the finish chunk.
        delta = {"role": "assistant", "content": message.get("content")}
        if message.get("tool_calls"):
            delta["tool_calls"] = [{**c, "index": i} for i, c in enumerate(message["tool_calls"])]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for chunk in ({"choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
                      {"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]}):
            self.wfile.write(f"data: {json.dumps({**base, 'object': 'chat.completion.chunk', **chunk})}\n\n".encode())
        self.wfile.write(b"data: [DONE]\n\n")


def serve(port: int = 0) -> tuple[ThreadingHTTPServer, str]:
    """Start in a background thread; returns the server and its /v1 base URL."""
    server = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}/v1"


if __name__ == "__main__":
    srv, url = serve(int(sys.argv[1]) if len(sys.argv) > 1 else 11500)
    print(f"{MODEL} on {url}")
    srv.serve_forever()
