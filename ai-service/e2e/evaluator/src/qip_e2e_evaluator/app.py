from __future__ import annotations

import logging
import os
import time
from collections.abc import Callable
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from qip_e2e_evaluator.client import (
    EvaluatorClientError,
    LlmSettings,
    evaluate_with_provider,
    resolve_settings,
)
from qip_e2e_evaluator.models import (
    ErrorBody,
    EvaluateRequest,
    HealthResponse,
    ScoreResponse,
)
from qip_e2e_evaluator.prompt import build_messages

logger = logging.getLogger(__name__)

CODE_CONFIG_INCOMPLETE = "EVALUATOR_CONFIG_INCOMPLETE"
CODE_INTERNAL_ERROR = "EVALUATOR_INTERNAL_ERROR"

ClientFactory = Callable[[LlmSettings, list[dict[str, str]]], ScoreResponse]


def _default_rubric_path() -> Path:
    container_path = Path("/app/semantic-rubric.md")
    if container_path.is_file():
        return container_path
    return Path(__file__).resolve().parents[3] / "product-pipeline" / "semantic-rubric.md"


def _resolve_rubric_path(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit
    env_path = os.environ.get("RUBRIC_PATH", "").strip()
    if env_path:
        return Path(env_path)
    return _default_rubric_path()


def _config_incomplete_response() -> JSONResponse:
    return JSONResponse(
        status_code=503,
        content=ErrorBody(
            code=CODE_CONFIG_INCOMPLETE,
            message="Evaluator LLM configuration is incomplete",
        ).model_dump(),
    )


def create_app(
    rubric_path: Path | None = None,
    client_factory: ClientFactory | None = None,
) -> FastAPI:
    resolved_rubric_path = _resolve_rubric_path(rubric_path)
    rubric_cache: dict[str, str | None] = {"text": None}
    evaluate_fn = client_factory if client_factory is not None else evaluate_with_provider

    def _get_rubric_text() -> str:
        if rubric_cache["text"] is None:
            rubric_cache["text"] = resolved_rubric_path.read_text(encoding="utf-8")
        return rubric_cache["text"]

    app = FastAPI()

    @app.get("/health", response_model=None)
    def health():
        if resolve_settings() is None:
            return _config_incomplete_response()
        return HealthResponse(status="ok", model="configured")

    @app.post("/evaluate", response_model=None)
    def evaluate(request: EvaluateRequest):
        settings = resolve_settings()
        if settings is None:
            return _config_incomplete_response()

        started_at = time.monotonic()
        try:
            messages = build_messages(request, _get_rubric_text())
            result = evaluate_fn(settings, messages)
        except EvaluatorClientError as exc:
            elapsed = time.monotonic() - started_at
            logger.warning(
                "Evaluate failed for scenario %s code=%s http_status=%s elapsed=%.3fs",
                request.scenarioId,
                exc.code,
                exc.http_status,
                elapsed,
            )
            return JSONResponse(
                status_code=exc.http_status,
                content=ErrorBody(code=exc.code, message=exc.message).model_dump(),
            )
        except Exception:
            elapsed = time.monotonic() - started_at
            logger.exception(
                "Unexpected evaluate error for scenario %s elapsed=%.3fs",
                request.scenarioId,
                elapsed,
            )
            return JSONResponse(
                status_code=500,
                content=ErrorBody(
                    code=CODE_INTERNAL_ERROR,
                    message="An unexpected evaluator error occurred",
                ).model_dump(),
            )

        elapsed = time.monotonic() - started_at
        logger.info(
            "Evaluate succeeded for scenario %s elapsed=%.3fs",
            request.scenarioId,
            elapsed,
        )
        return result

    return app


app = create_app()
