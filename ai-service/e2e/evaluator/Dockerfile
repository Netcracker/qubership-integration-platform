FROM python:3.12-slim

RUN useradd --create-home --uid 10001 evaluator \
  && mkdir -p /app \
  && chown -R evaluator:evaluator /app

WORKDIR /app
COPY e2e/evaluator/pyproject.toml /app/pyproject.toml
COPY e2e/evaluator/src /app/src
COPY e2e/product-pipeline/semantic-rubric.md /app/semantic-rubric.md

RUN pip install --no-cache-dir \
      fastapi==0.116.1 \
      uvicorn==0.35.0 \
      pydantic==2.11.7 \
      httpx==0.28.1 \
  && pip install --no-cache-dir -e .

USER evaluator
ENV RUBRIC_PATH=/app/semantic-rubric.md
EXPOSE 8099
CMD ["uvicorn", "qip_e2e_evaluator.app:app", "--host", "0.0.0.0", "--port", "8099"]
