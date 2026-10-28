FROM node:22-slim AS documentation

WORKDIR /workspace
COPY package.json package-lock.json ./
COPY .npmrc .npmrc
COPY schemas/package.json schemas/package.json
COPY ui/package.json ui/package.json
COPY vscode-extension/package.json vscode-extension/package.json
RUN npm ci --workspace @netcracker/qip-ui --include-workspace-root=false --omit=dev --ignore-scripts
COPY ui/scripts/build-doc-index.mjs ui/scripts/build-doc-index.mjs
COPY ui/scripts/element-documentation-mapping.mjs ui/scripts/element-documentation-mapping.mjs
COPY help/docs help/docs
RUN node ui/scripts/build-doc-index.mjs help/docs /tmp/ui-index \
  && mkdir -p /documentation \
  && cp /tmp/ui-index/documentation.json /documentation/documentation.json

FROM python:3.12-slim AS sidecar-build

WORKDIR /app
ENV PYTHONPATH=/app/src:/app/vendor/cip-runtime
COPY ai-service/knowledge-sidecar/pyproject.toml /app/pyproject.toml
COPY ai-service/knowledge-sidecar/src /app/src
COPY ai-service/knowledge-sidecar/vendor/cip-runtime/knowledge_sdk /app/vendor/cip-runtime/knowledge_sdk

RUN pip install --no-cache-dir \
      fastapi==0.116.1 \
      uvicorn==0.35.0 \
      lancedb==0.34.0 \
      pydantic==2.11.7 \
      PyYAML==6.0.2 \
      httpx==0.28.1 \
      pyarrow==25.0.0 \
  && pip install --no-cache-dir .

COPY --from=documentation /documentation /documentation

RUN python -m qip_knowledge_sidecar.documentation build /documentation \
  && chmod -R a+rX /documentation

FROM python:3.12-slim

RUN useradd --create-home --uid 10001 sidecar \
  && mkdir -p /knowledge /app \
  && chown -R sidecar:sidecar /knowledge /app

WORKDIR /app
COPY --from=sidecar-build /usr/local /usr/local
COPY --from=sidecar-build /app /app
COPY --from=sidecar-build /documentation /documentation

USER sidecar
ENV PYTHONPATH=/app/src:/app/vendor/cip-runtime \
    QIP_KNOWLEDGE_PATH=/knowledge \
    QIP_DOCUMENTATION_PATH=/documentation
EXPOSE 8095
CMD ["uvicorn", "qip_knowledge_sidecar.app:app", "--host", "0.0.0.0", "--port", "8095"]
