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


@tool("send_customer_letter")
def send_customer_letter(customer_id: str) -> str:
    """Send the standard claims letter to a customer."""
    ran.append(customer_id)
    return f"sent to {customer_id}"


PrudenceToolHooks(
    os.environ["AI_SYSTEM_ID"],
    gate=PrudenceGate(os.environ["PRUDENCE_URL"], timeout=3.0),
    pipelines={"send_customer_letter": os.environ.get("PIPELINE", "send_customer_letter")},
    evidence=ErnestEvidence(os.environ["ERNEST_URL"]) if os.getenv("ERNEST_URL") else None,
).register()

llm = LLM(model=f"openai/{os.environ.get('LLM_MODEL', 'conformance-scripted')}", base_url=os.environ["LLM_URL"], api_key=os.environ.get("LLM_API_KEY", "none"))
clerk = Agent(role="Claims assistant", goal="Use your tools to do what the user asks.",
              backstory="You handle claims letters.", tools=[send_customer_letter], llm=llm,
              max_iter=2, verbose=False)
task = Task(description="Send the letter to customer C-1", expected_output="What the tool said.",
            agent=clerk)
result = Crew(agents=[clerk], tasks=[task], verbose=False).kickoff()
print(json.dumps({"toolRan": bool(ran), "answer": str(result)}))
