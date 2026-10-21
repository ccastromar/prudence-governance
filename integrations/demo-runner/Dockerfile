FROM python:3.12-slim

WORKDIR /app
COPY integrations/prudence-core /app/prudence-core
COPY integrations/pydantic-ai /app/pydantic-ai
COPY integrations/langgraph /app/langgraph
COPY integrations/google-adk /app/google-adk
COPY integrations/openai-agents /app/openai-agents
COPY integrations/agent-framework /app/agent-framework
COPY integrations/demo-runner/requirements.txt /app/requirements.txt
COPY integrations/demo-runner/server.py integrations/demo-runner/agent_demos.py /app/
# The scripted model used when PRUDENCE_DEMO_LLM_BASE_URL is not set.
COPY integrations/conformance/fake_llm.py /app/fake_llm.py
RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONUNBUFFERED=1
# pydantic-ai prints an observability banner on every agent run; keep container logs clean.
ENV PYDANTIC_AI_NO_BANNER=1
EXPOSE 9100
CMD ["python", "/app/server.py"]
