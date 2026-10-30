"""A CrewAI crew with one governed tool, as a company using it would write it.

Reads PRUDENCE_URL, LLM_URL, AI_SYSTEM_ID, PIPELINE and, optionally, ERNEST_URL, LLM_MODEL and
LLM_API_KEY; prints one JSON line:
{"toolRan": bool, "answer": str}.
"""
import json
import os

os.environ.update(CREWAI_DISABLE_TELEMETRY="true", OTEL_SDK_DISABLED="true", CREWAI_TRACING_ENABLED="false")

from crewai import LLM, Agent, Crew, Task  # noqa: E402
from crewai.tools import tool  # noqa: E402

from prudence_crewai import PrudenceGate, PrudenceToolHooks  # noqa: E402
from prudence_governance import ErnestEvidence  # noqa: E402

ran: list[str] = []

# The story this run tells: a demo's, set by the demo runner, or conformance's own by default.
TOOL = os.environ.get("DEMO_TOOL", "send_customer_letter")
DESCRIPTION = os.environ.get("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.")
TASK = os.environ.get("DEMO_TASK", "Send the letter to customer C-1")
RESULT = os.environ.get("DEMO_RESULT", "sent to {reference}")


@tool(TOOL)
def run_tool(reference: str) -> str:
    """Do what the task asks."""
    ran.append(reference)
    return RESULT.format(reference=reference)


run_tool.description = DESCRIPTION

PrudenceToolHooks(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={TOOL: os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
).register()

llm = LLM(model=f"openai/{os.environ.get('LLM_MODEL', 'conformance-scripted')}", base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
clerk = Agent(role="Claims assistant", goal="Use your tools to do what the user asks.",
              backstory="You handle claims letters.", tools=[run_tool], llm=llm,
              max_iter=2, verbose=False)
task = Task(description=TASK, expected_output="What the tool said.",
            agent=clerk)
result = Crew(agents=[clerk], tasks=[task], verbose=False).kickoff()
print(json.dumps({"toolRan": bool(ran), "answer": str(result)}))
