FROM inspector_source AS inspector-source

FROM node:22-alpine AS frontend-builder
WORKDIR /app

COPY --from=inspector-source /frontend/package*.json ./
COPY --from=inspector-source /frontend/tsconfig.json ./
COPY --from=inspector-source /frontend/src ./src
RUN npm ci

COPY --from=inspector-source /frontend/ ./
RUN npm rebuild esbuild && npm run build

FROM python:3.12-slim

WORKDIR /app
RUN pip install --no-cache-dir uv

COPY --from=inspector-source /pyproject.toml /uv.lock ./
RUN uv sync --no-cache \
    && uv pip install validators

COPY --from=inspector-source /backend/ ./backend/
RUN mkdir -p /app/frontend
COPY --from=frontend-builder /app/public /app/frontend/public

WORKDIR /app/backend
EXPOSE 8080

CMD ["uv", "run", "--", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
