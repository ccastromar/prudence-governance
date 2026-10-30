"""Every governed demo a runner can offer: one per framework adapter.

A demo is that framework's conformance agent (`integrations/conformance/agents`), so what the
console shows is what conformance tests, telling a story of its own: the tool, the task and the
result reach the agent through the environment. All of it is synthetic. A runner offers the demos
whose adapter is installed in its image (and, when DEMOS is set, only those listed).
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
    title: str
    story: str         # what the agent does, for the card
    tool: str
    tool_description: str
    task: str          # what the agent is asked; carries the reference the tool acts on
    result: str        # what the tool returns; {reference} is that reference
    anthropic: bool = False  # speaks Anthropic Messages, not Chat Completions

    def manifest(self, pipeline: str) -> dict:
        return {"id": self.id, "framework": self.framework, "title": self.title,
                "detail": f"{self.story}; {self.hook} asks Prudence first and the tool runs only on ALLOW.",
                "pipeline": pipeline}

    def environment(self) -> dict[str, str]:
        return {"DEMO_TOOL": self.tool, "DEMO_TOOL_DESCRIPTION": self.tool_description,
                "DEMO_TASK": self.task, "DEMO_RESULT": self.result}


DEMOS: list[Demo] = [
    Demo("pydantic-ai", "Pydantic AI", "a @governed tool", "pydantic_agent.py", "prudence_pydantic",
         "Bug triage", "An agent classifies and routes a synthetic checkout incident",
         "triage_incident", "Classify an incident and route it to the team that owns it.",
         "Triage the checkout incident INC-4102", "{reference} triaged: priority P2, queue customer-platform"),
    Demo("langgraph", "LangGraph", "a governed node with a routing gate", "langgraph_agent.py",
         "prudence_langgraph",
         "Response drafter", "A graph drafts a synthetic customer reply; delivery only follows ALLOW",
         "deliver_reply", "Draft the reply to a support ticket and deliver it.",
         "Draft and deliver the reply to ticket TCK-2210", "reply delivered on {reference}"),
    Demo("google-adk", "Google ADK", "a runner plugin", "adk_agent.py", "prudence_adk",
         "Claims letter", "An agent queues a synthetic claim acknowledgement",
         "queue_claim_letter", "Queue the acknowledgement letter for a claim.",
         "Queue the acknowledgement letter for claim CL-2041", "letter queued for {reference}"),
    Demo("openai-agents", "OpenAI Agents SDK", "a tool input guardrail", "openai_agent.py",
         "prudence_openai_agents",
         "Refund approval", "An agent approves a small synthetic refund",
         "approve_refund", "Approve a goodwill refund for an order.",
         "Approve the goodwill refund for order ORD-7731", "refund approved for {reference}: 20.00 EUR"),
    Demo("agent-framework", "Microsoft Agent Framework", "function middleware", "maf_agent.py",
         "prudence_agent_framework",
         "CRM update", "An agent logs a synthetic call summary on a CRM account",
         "log_customer_call", "Log the summary of a customer call on the CRM account.",
         "Log the call summary on account ACC-3310", "call summary logged on {reference}"),
    Demo("langchain", "LangChain", "agent middleware", "langchain_agent.py", "prudence_langchain",
         "Contract review", "An agent flags a clause of a synthetic contract for legal review",
         "flag_contract_clause", "Flag a contract clause for legal review.",
         "Flag the liability clause in contract CTR-5120", "liability clause of {reference} flagged for legal"),
    Demo("claude-agent-sdk", "Claude Agent SDK", "a PreToolUse hook", "claude_agent.py",
         "prudence_claude_agent",
         "Production change", "An agent schedules a synthetic change to production",
         "schedule_change", "Schedule a change for the next production window.",
         "Schedule production change CHG-8812", "{reference} scheduled for the next window", anthropic=True),
    Demo("agno", "Agno", "a tool hook", "agno_agent.py", "prudence_agno",
         "Stock reorder", "An agent places a synthetic reorder for a low-stock item",
         "reorder_stock", "Place a reorder for a low-stock item.",
         "Reorder stock for item SKU-3307", "reorder placed for {reference}"),
    Demo("smolagents", "smolagents", "a governed tool wrapper", "smolagents_agent.py", "prudence_smolagents",
         "Report export", "An agent exports a synthetic monthly report",
         "export_report", "Export a report for its recipients.",
         "Export the monthly report RPT-1024", "{reference} exported"),
    Demo("semantic-kernel", "Semantic Kernel", "a function invocation filter", "sk_agent.py",
         "prudence_semantic_kernel",
         "Invoice matching", "An agent matches a synthetic invoice against its purchase order",
         "match_invoice", "Match an invoice against its purchase order.",
         "Match invoice INV-1042 against its purchase order", "{reference} matched, ready for payment"),
    Demo("strands-agents", "Strands Agents", "a hook provider", "strands_agent.py", "prudence_strands",
         "Access request", "An agent grants a synthetic access request",
         "grant_access", "Grant the access an employee requested.",
         "Grant the read access of request ACR-6401", "access granted for {reference}"),
    Demo("crewai", "CrewAI", "tool call hooks", "crewai_agent.py", "prudence_crewai",
         "Supplier onboarding", "A crew onboards a synthetic supplier",
         "onboard_supplier", "Onboard a supplier once its checks are complete.",
         "Onboard supplier SUP-2290", "{reference} onboarded"),
    Demo("llamaindex", "LlamaIndex", "a governed tool wrapper", "llamaindex_agent.py", "prudence_llamaindex",
         "Knowledge article", "An agent publishes a synthetic knowledge base article",
         "publish_article", "Publish an article to the knowledge base.",
         "Publish knowledge article KB-7312", "{reference} published"),
]
