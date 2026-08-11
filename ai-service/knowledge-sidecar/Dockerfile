FROM python:3.12-slim

RUN useradd --create-home --uid 10001 sidecar \
  && mkdir -p /knowledge /app \
  && chown -R sidecar:sidecar /knowledge /app

WORKDIR /app
COPY pyproject.toml /app/pyproject.toml
COPY src /app/src
COPY vendor/cip-runtime/knowledge_sdk /app/vendor/cip-runtime/knowledge_sdk

RUN pip install --no-cache-dir \
      fastapi==0.116.1 \
      uvicorn==0.35.0 \
      lancedb==0.34.0 \
      pydantic==2.11.7 \
      PyYAML==6.0.2 \
      httpx==0.28.1 \
      pyarrow \
  && pip install --no-cache-dir -e . \
  && chmod a-w /knowledge

USER sidecar
ENV PYTHONPATH=/app/src:/app/vendor/cip-runtime
ENV QIP_KNOWLEDGE_PATH=/knowledge
EXPOSE 8095
CMD ["uvicorn", "qip_knowledge_sidecar.app:app", "--host", "0.0.0.0", "--port", "8095"]
