# A governed demo runner (demo runner protocol v1, see runner.py). One Dockerfile, two images,
# one per dependency family; build from the repository root:
#
#   docker build -f integrations/demo-runner/Dockerfile --build-arg IMAGE=a -t prudence-demo-runner-a .
#   docker build -f integrations/demo-runner/Dockerfile --build-arg IMAGE=b -t prudence-demo-runner-b .
#
# The image runs the demos whose adapters its requirements install. Python 3.13: CrewAI (image B)
# does not support 3.14 yet.
FROM python:3.13-slim

ARG IMAGE=a
WORKDIR /app/integrations/demo-runner
COPY integrations/ /app/integrations/
RUN pip install --no-cache-dir -r images/${IMAGE}/requirements.txt

ENV PYTHONUNBUFFERED=1
# pydantic-ai prints an observability banner on every agent run; keep container logs clean.
ENV PYDANTIC_AI_NO_BANNER=1
EXPOSE 9100
CMD ["python", "runner.py"]
