from __future__ import annotations

import json
import os
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Protocol

import httpx
from pydantic import ValidationError

from qip_e2e_evaluator.models import (
    PROVIDER_TIMEOUT_SECONDS,
    RETRY_PAUSE_SECONDS,
    ScoreResponse,
)

CODE_INVALID_MODEL_OUTPUT = "EVALUATOR_INVALID_MODEL_OUTPUT"
CODE_UPSTREAM_ERROR = "EVALUATOR_UPSTREAM_ERROR"
CODE_UPSTREAM_UNAVAILABLE = "EVALUATOR_UPSTREAM_UNAVAILABLE"

_RETRYABLE_STATUS_CODES = {429, 502, 503, 504}
_CHAT_COMPLETIONS_SUFFIX = "/chat/completions"


class EvaluatorClientError(Exception):
    def __init__(self, code: str, message: str, http_status: int) -> None:
        self.code = code
        self.message = message
        self.http_status = http_status
        super().__init__(message)


@dataclass(frozen=True)
class LlmSettings:
    base_url: str
    api_key: str
    model: str


class HttpPostClient(Protocol):
    def post(
        self,
        url: str,
        *,
        json: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> httpx.Response: ...


def normalize_base_url(url: str) -> str:
    normalized = url.rstrip("/")
    if normalized.endswith(_CHAT_COMPLETIONS_SUFFIX):
        normalized = normalized[: -len(_CHAT_COMPLETIONS_SUFFIX)].rstrip("/")
    return normalized


def _resolve_value(environ: Mapping[str, str], evaluator_key: str, fallback_key: str) -> str:
    value = environ.get(evaluator_key, "").strip()
    if value:
        return value
    return environ.get(fallback_key, "").strip()


def resolve_settings(environ: Mapping[str, str] | None = None) -> LlmSettings | None:
    env = environ if environ is not None else os.environ
    base_url = _resolve_value(env, "EVALUATOR_LLM_BASE_URL", "LLM_BASE_URL")
    api_key = _resolve_value(env, "EVALUATOR_LLM_API_KEY", "LLM_API_KEY")
    model = _resolve_value(env, "EVALUATOR_LLM_MODEL", "LLM_CHAT_MODEL")
    if not base_url or not api_key or not model:
        return None
    return LlmSettings(base_url=base_url, api_key=api_key, model=model)


def evaluate_with_provider(
    settings: LlmSettings,
    messages: list[dict[str, str]],
    *,
    http_client: HttpPostClient | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> ScoreResponse:
    client = http_client if http_client is not None else httpx.Client()
    owns_client = http_client is None
    try:
        return _evaluate_with_retries(client, settings, messages, sleep)
    finally:
        if owns_client:
            client.close()


def _evaluate_with_retries(
    client: HttpPostClient,
    settings: LlmSettings,
    messages: list[dict[str, str]],
    sleep: Callable[[float], None],
) -> ScoreResponse:
    last_transient_message = "Provider request failed"
    for attempt in range(2):
        try:
            response = _post_chat_completion(client, settings, messages)
        except (httpx.ConnectError, httpx.TimeoutException):
            if attempt == 0:
                sleep(RETRY_PAUSE_SECONDS)
                last_transient_message = "Provider connection failed"
                continue
            raise EvaluatorClientError(
                CODE_UPSTREAM_UNAVAILABLE,
                last_transient_message,
                503,
            ) from None

        if response.status_code >= 400:
            if response.status_code in _RETRYABLE_STATUS_CODES and attempt == 0:
                sleep(RETRY_PAUSE_SECONDS)
                last_transient_message = "Provider returned a retryable error"
                continue
            if response.status_code in _RETRYABLE_STATUS_CODES:
                raise EvaluatorClientError(
                    CODE_UPSTREAM_UNAVAILABLE,
                    "Provider is temporarily unavailable",
                    503,
                )
            raise EvaluatorClientError(
                CODE_UPSTREAM_ERROR,
                "Provider returned a permanent error",
                502,
            )

        return _parse_score_response(response)


def _post_chat_completion(
    client: HttpPostClient,
    settings: LlmSettings,
    messages: list[dict[str, str]],
) -> httpx.Response:
    url = f"{normalize_base_url(settings.base_url)}{_CHAT_COMPLETIONS_SUFFIX}"
    headers = {"Authorization": f"Bearer {settings.api_key}"}
    body = {
        "model": settings.model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": messages,
    }
    return client.post(
        url,
        json=body,
        headers=headers,
        timeout=PROVIDER_TIMEOUT_SECONDS,
    )


def _parse_score_response(response: httpx.Response) -> ScoreResponse:
    try:
        payload = response.json()
    except ValueError as exc:
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is not valid JSON",
            502,
        ) from exc

    content = _extract_message_content(payload)
    parsed = _parse_model_json(content)
    _validate_score_field_types(parsed)
    try:
        return ScoreResponse.model_validate(parsed)
    except ValidationError as exc:
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Model output does not match score schema",
            502,
        ) from exc


def _extract_message_content(payload: Any) -> str:
    if not isinstance(payload, dict):
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is missing choices",
            502,
        )

    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is missing choices",
            502,
        )

    first_choice = choices[0]
    if not isinstance(first_choice, dict):
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is missing message content",
            502,
        )

    message = first_choice.get("message")
    if not isinstance(message, dict):
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is missing message content",
            502,
        )

    content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Provider response is missing message content",
            502,
        )

    return content.strip()


def _parse_model_json(content: str) -> dict[str, Any]:
    if content.startswith("```"):
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Model output must be raw JSON",
            502,
        )

    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Model output is not valid JSON",
            502,
        ) from exc

    if not isinstance(parsed, dict):
        raise EvaluatorClientError(
            CODE_INVALID_MODEL_OUTPUT,
            "Model output must be a JSON object",
            502,
        )

    return parsed


_SCORE_FIELDS = (
    "intentFidelity",
    "completeness",
    "executability",
    "unnecessaryComplexity",
)


def _validate_score_field_types(parsed: dict[str, Any]) -> None:
    for field_name in _SCORE_FIELDS:
        value = parsed.get(field_name)
        if isinstance(value, bool) or not isinstance(value, int):
            raise EvaluatorClientError(
                CODE_INVALID_MODEL_OUTPUT,
                "Model output does not match score schema",
                502,
            )
