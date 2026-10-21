"""A scripted stand-in for an OpenAI-compatible model, so the conformance run needs no model and
no API key. It is not a language model and says so in its name (`conformance-scripted`).

Chat Completions only (`POST /v1/chat/completions`), streaming or not. Its whole behaviour:
- tools offered and no tool result yet: call the first tool, filling each required argument
  from its JSON schema (strings "C-1", numbers 1, booleans true);
- a tool result came back: answer with it ("Tool result: …");
- no tools: answer "OK".

Run it alone with `python fake_llm.py [port]`, or start it in-process with `serve(port)`.
"""
from __future__ import annotations

import json
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "conformance-scripted"


def _argument(schema: dict):
    kind = schema.get("type")
    if kind in ("number", "integer"):
        return 1
    if kind == "boolean":
        return True
    if kind == "array":
        return []
    if kind == "object":
        return {}
    return "C-1"


def reply(request: dict) -> dict:
    """The assistant message for a chat request: a tool call or text."""
    messages = request.get("messages") or []
    tools = request.get("tools") or []
    last = messages[-1] if messages else {}
    if last.get("role") == "tool":
        content = last.get("content")
        if isinstance(content, list):
            content = " ".join(str(p.get("text", p)) for p in content if isinstance(p, dict))
        return {"role": "assistant", "content": f"Tool result: {content}"}
    if tools:
        fn = tools[0].get("function", tools[0])
        params = fn.get("parameters") or {}
        props = params.get("properties") or {}
        args = {name: _argument(props.get(name, {})) for name in params.get("required", list(props))}
        return {"role": "assistant", "content": None, "tool_calls": [{
            "id": f"call_{uuid.uuid4().hex[:12]}", "type": "function",
            "function": {"name": fn["name"], "arguments": json.dumps(args)}}]}
    return {"role": "assistant", "content": "OK"}


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

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
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
