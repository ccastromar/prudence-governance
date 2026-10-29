"""Every governed demo a runner can offer: one per framework adapter.

A demo is that framework's conformance agent (`integrations/conformance/agents`), so what the
console shows is what conformance tests. A runner offers the demos whose adapter is installed
in its image (and, when DEMOS is set, only those listed).
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Demo:
    id: str            # also the runtime name the adapter reports
    framework: str
    hook: str          # how the adapter enforces, for the card
    agent: str         # script in integrations/conformance/agents
    adapter: str       # module whose presence means the image can run it
    anthropic: bool = False  # speaks Anthropic Messages, not Chat Completions

    def manifest(self, pipeline: str) -> dict:
        return {"id": self.id, "framework": self.framework, "title": "Customer letter",
                "detail": f"An agent asks to send a synthetic claims letter; {self.hook} asks "
                          "Prudence first and the tool runs only on ALLOW.",
                "pipeline": pipeline}


DEMOS: list[Demo] = [
    Demo("pydantic-ai", "Pydantic AI", "a @governed tool", "pydantic_agent.py", "prudence_pydantic"),
    Demo("langgraph", "LangGraph", "a governed node with a routing gate", "langgraph_agent.py",
         "prudence_langgraph"),
    Demo("google-adk", "Google ADK", "a runner plugin", "adk_agent.py", "prudence_adk"),
    Demo("openai-agents", "OpenAI Agents SDK", "a tool input guardrail", "openai_agent.py",
         "prudence_openai_agents"),
    Demo("agent-framework", "Microsoft Agent Framework", "function middleware", "maf_agent.py",
         "prudence_agent_framework"),
    Demo("langchain", "LangChain", "agent middleware", "langchain_agent.py", "prudence_langchain"),
    Demo("claude-agent-sdk", "Claude Agent SDK", "a PreToolUse hook", "claude_agent.py",
         "prudence_claude_agent", anthropic=True),
    Demo("agno", "Agno", "a tool hook", "agno_agent.py", "prudence_agno"),
    Demo("smolagents", "smolagents", "a governed tool wrapper", "smolagents_agent.py", "prudence_smolagents"),
    Demo("semantic-kernel", "Semantic Kernel", "a function invocation filter", "sk_agent.py",
         "prudence_semantic_kernel"),
    Demo("strands-agents", "Strands Agents", "a hook provider", "strands_agent.py", "prudence_strands"),
    Demo("crewai", "CrewAI", "tool call hooks", "crewai_agent.py", "prudence_crewai"),
    Demo("llamaindex", "LlamaIndex", "a governed tool wrapper", "llamaindex_agent.py", "prudence_llamaindex"),
]
