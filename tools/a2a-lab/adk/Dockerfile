FROM python:3.12-slim

ARG GOOGLE_ADK_VERSION=2.5.0
ARG LITELLM_VERSION=1.95.0

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN pip install --no-cache-dir \
    "google-adk[a2a]==${GOOGLE_ADK_VERSION}" \
    "litellm==${LITELLM_VERSION}"

WORKDIR /app
COPY agents /app/agents

EXPOSE 8000

CMD ["adk", "web", "--host", "0.0.0.0", "--port", "8000", "/app/agents"]
