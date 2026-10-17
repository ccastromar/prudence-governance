FROM python:3.12-slim

WORKDIR /app
COPY integrations/prudence-core /app/prudence-core
COPY integrations/pydantic-ai /app/pydantic-ai
COPY integrations/langgraph /app/langgraph
COPY integrations/demo-runner/requirements.txt /app/requirements.txt
COPY integrations/demo-runner/server.py /app/server.py
RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONUNBUFFERED=1
EXPOSE 9100
CMD ["python", "/app/server.py"]
