#!/usr/bin/env python3
"""End to end: an example agent with no adapter, governed by Prudence through LiteLLM.

Starts the scripted model (../conformance/fake_llm.py), the MCP tool server (mock_tools.py) and
LiteLLM with config.yaml twice (one gateway asking the real Prudence, one asking a closed port),
then runs the example agents (../conformance/agents) in gateway mode and reads which tools
really ran. Each framework: allowed model and tool, a tool not granted, a model not granted; and
once (OpenAI Agents SDK): no agent token, Prudence unreachable.

Needs a running Prudence where:
- the AI system declares the agent (POST /api/registry/systems/{id}/agents) and is approved;
- the policy grants the agent `model:conformance-scripted` (action `invoke`) and
  `tool:send_customer_letter` (action `tools/call`), not `model:unapproved-model` nor
  `tool:delete_customer`, and lists the agent in `speaks_for` of the gateway's identity.

Environment:
  PRUDENCE_URL      Prudence's base URL
  PRUDENCE_TOKEN    the gateway's access token (pdp.decide), or PRUDENCE_TOKEN_URL/CLIENT_ID/SECRET
  AGENT_TOKEN       the agent's own access token, issued for Prudence
  LITELLM           the litellm executable (a venv with `litellm[proxy]` pinned, see README)
  FRAMEWORKS        optional, comma-separated subset (default: all twelve); each runs with
                    ../conformance/.venv-<framework>, as the conformance run sets them up
  READER_TOKEN, AI_SYSTEM_ID   optional: a person's token that can read Prudence and the AI system,
                    to check the gateway's execution report

Prints one line per case and exits 1 if any fails.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFORMANCE = HERE.parent / "conformance"
MASTER_KEY = "sk-local-gateway-only"
FRAMEWORKS = ["openai", "pydantic", "langgraph", "adk", "maf", "strands", "langchain", "claude",
              "crewai", "agno", "llamaindex", "smolagents"]
SCRIPTS = {"openai": "openai_agent.py", "pydantic": "pydantic_agent.py", "langgraph": "langgraph_agent.py",
           "adk": "adk_agent.py", "maf": "maf_agent.py", "strands": "strands_agent.py",
           "langchain": "langchain_agent.py", "claude": "claude_agent.py", "crewai": "crewai_agent.py",
           "agno": "agno_agent.py", "llamaindex": "llamaindex_agent.py", "smolagents": "smolagents_agent.py"}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait(url: str, seconds: float = 90) -> None:
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except urllib.error.HTTPError:
            return  # it answers
        except Exception:
            time.sleep(0.5)
    raise SystemExit(f"{url} did not come up")


def main() -> int:
    work = Path(tempfile.mkdtemp(prefix="litellm-gateway-"))
    tool_log = work / "tools.jsonl"
    procs: list[subprocess.Popen] = []

    def start(cmd, env=None, log="out.log"):
        p = subprocess.Popen(cmd, env={**os.environ, **(env or {})}, cwd=HERE,
                             stdout=open(work / log, "w"), stderr=subprocess.STDOUT)
        procs.append(p)
        return p

    try:
        llm_port, tools_port = free_port(), free_port()
        start([sys.executable, str(CONFORMANCE / "fake_llm.py"), str(llm_port)], log="llm.log")
        start([sys.executable, str(HERE / "mock_tools.py"), str(tools_port)],
              {"TOOL_LOG": str(tool_log)}, log="tools.log")
        wait(f"http://127.0.0.1:{tools_port}/mcp")

        gateways = {}
        for name, prudence in (("real", os.environ["PRUDENCE_URL"]), ("down", "http://127.0.0.1:9")):
            port = free_port()
            start([os.environ.get("LITELLM", "litellm"), "--config", str(HERE / "config.yaml"),
                   "--host", "127.0.0.1", "--port", str(port)],
                  {"FAKE_LLM_URL": f"http://127.0.0.1:{llm_port}/v1",
                   "MOCK_TOOLS_URL": f"http://127.0.0.1:{tools_port}/mcp",
                   "LITELLM_MASTER_KEY": MASTER_KEY, "PRUDENCE_URL": prudence},
                  log=f"litellm-{name}.log")
            gateways[name] = f"http://127.0.0.1:{port}"
        for url in gateways.values():
            wait(url + "/health/liveliness")

        def agent(framework: str, gateway: str, tool: str, token: str | None, model: str) -> dict:
            env = {"GOVERNANCE_MODE": "gateway", "LLM_URL": gateways[gateway] + "/v1", "LLM_MODEL": model,
                   "LLM_API_KEY": MASTER_KEY, "MCP_URL": gateways[gateway] + "/mcp/",
                   "DEMO_TOOL": tool, "DEMO_TASK": "Do it for customer C-1"}
            env = {k: v for k, v in {**os.environ, **env}.items() if k != "AGENT_TOKEN"}
            if token:
                env["AGENT_TOKEN"] = token
            python = CONFORMANCE / f".venv-{framework}" / "bin" / "python"
            out = subprocess.run([str(python), str(CONFORMANCE / "agents" / SCRIPTS[framework])],
                                 env=env, capture_output=True, text=True, timeout=180,
                                 cwd=CONFORMANCE / "agents")
            (work / f"agent-{framework}.log").open("a").write(out.stdout + out.stderr)
            lines = [line for line in out.stdout.splitlines() if line.startswith("{")]
            return json.loads(lines[-1]) if lines else {"error": (out.stderr or out.stdout)[-400:]}

        def ran(tool: str) -> bool:
            return tool_log.exists() and any(json.loads(line)["tool"] == tool
                                             for line in tool_log.read_text().splitlines())

        token = os.environ["AGENT_TOKEN"]
        started = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime())
        chosen = [f.strip() for f in os.environ.get("FRAMEWORKS", ",".join(FRAMEWORKS)).split(",") if f.strip()]
        # (framework, case, gateway, tool, agent token, model, tool should run, what the gateway must say)
        cases = []
        for f in chosen:
            cases += [
                (f, "allowed model and tool", "real", "send_customer_letter", token,
                 "conformance-scripted", True, None),
                (f, "tool the policy does not grant", "real", "delete_customer", token,
                 "conformance-scripted", False, "tool delete_customer: denied"),
                (f, "model the policy does not grant", "real", "send_customer_letter", token,
                 "unapproved-model", False, "model unapproved-model: denied"),
            ]
        once = chosen[0]
        cases += [
            (once, "no agent token: nothing to decide about", "real", "send_customer_letter", None,
             "conformance-scripted", False, "model conformance-scripted: the agent presented no token"),
            (once, "Prudence unreachable: fail closed", "down", "send_customer_letter", token,
             "conformance-scripted", False, "model conformance-scripted: Prudence could not decide"),
        ]
        failures = 0
        for framework, name, gateway, tool, agent_token, model, expected, said in cases:
            name = f"{framework:<10} {name}"
            tool_log.unlink(missing_ok=True)
            log = work / f"litellm-{gateway}.log"
            seen = log.stat().st_size
            result = agent(framework, gateway, tool, agent_token, model)
            with log.open() as f:
                f.seek(seen)
                blocked = f.read()
            ok = ran(tool) == expected and (
                "error" not in result if said is None else f"Blocked by Prudence: {said}" in blocked)
            failures += not ok
            print(f"{'PASS' if ok else 'FAIL'}  {name}: tool ran={ran(tool)} (expected {expected})"
                  + ("" if ok else f"\n        {result}\n        expected the gateway to say: {said}"))
        total = len(cases)

        # With a reader's token and the AI system: the gateway reported exactly the call that ran,
        # citing the ALLOW that let it through, under the agent's name.
        if os.getenv("READER_TOKEN") and os.getenv("AI_SYSTEM_ID"):
            total += 1
            reports = []
            for _ in range(40):
                request = urllib.request.Request(
                    f"{os.environ['PRUDENCE_URL']}/api/executions?aiSystemId={os.environ['AI_SYSTEM_ID']}",
                    headers={"Authorization": f"Bearer {os.environ['READER_TOKEN']}"})
                reports = [r for r in json.load(urllib.request.urlopen(request, timeout=10))
                           if r.get("runtime") == "litellm-gateway" and r.get("startedAt", "") >= started]
                if len(reports) >= len(chosen):
                    break
                time.sleep(0.5)
            ok = (len(reports) == len(chosen) and all(r.get("decisionStatus") == "ALLOW" for r in reports)
                  and all(r.get("agent") == "claims-assistant" for r in reports)
                  and all(r.get("pipeline") == "tool:send_customer_letter" for r in reports))
            failures += not ok
            print(f"{'PASS' if ok else 'FAIL'}  one execution report per framework, citing the ALLOW"
                  + ("" if ok else f"\n        {reports}"))
        print(f"\n{total - failures}/{total} passed (logs in {work})")
        return 1 if failures else 0
    finally:
        for p in procs:
            p.terminate()


if __name__ == "__main__":
    sys.exit(main())
