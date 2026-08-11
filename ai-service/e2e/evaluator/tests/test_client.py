import json
from typing import Any

import httpx
import pytest

from qip_e2e_evaluator.client import (
    EvaluatorClientError,
    LlmSettings,
    evaluate_with_provider,
    normalize_base_url,
    resolve_settings,
)
from qip_e2e_evaluator.models import PROVIDER_TIMEOUT_SECONDS, RETRY_PAUSE_SECONDS

API_KEY = "sk-test-secret-token-12345"
SETTINGS = LlmSettings(
    base_url="https://api.example.com/v1/",
    api_key=API_KEY,
    model="gpt-test",
)
MESSAGES = [{"role": "user", "content": "score this"}]


def _valid_score_payload() -> dict[str, Any]:
    return {
        "intentFidelity": 4,
        "completeness": 4,
        "executability": 4,
        "unnecessaryComplexity": 4,
        "evidence": ["plan includes GET /greetings"],
    }


def _completion_response(content: str) -> dict[str, Any]:
    return {"choices": [{"message": {"content": content}}]}


class RecordingClient:
    def __init__(self, handler):
        self.handler = handler
        self.calls: list[dict[str, Any]] = []

    def post(self, url: str, *, json=None, headers=None, timeout=None):
        self.calls.append(
            {"url": url, "json": json, "headers": headers, "timeout": timeout}
        )
        return self.handler(url, json, headers, timeout)


def _client_for_responses(responses: list[httpx.Response]) -> RecordingClient:
    state = {"index": 0}

    def handler(_url, _json, _headers, _timeout):
        response = responses[state["index"]]
        state["index"] += 1
        return response

    return RecordingClient(handler)


def test_normalize_base_url_strips_trailing_slash_and_chat_completions():
    assert normalize_base_url("https://api.example.com/v1/") == "https://api.example.com/v1"
    assert (
        normalize_base_url("https://api.example.com/v1/chat/completions")
        == "https://api.example.com/v1"
    )


def test_resolve_settings_prefers_evaluator_env_with_llm_fallback():
    settings = resolve_settings(
        {
            "EVALUATOR_LLM_BASE_URL": "https://eval.example/v1",
            "LLM_BASE_URL": "https://llm.example/v1",
            "EVALUATOR_LLM_API_KEY": "",
            "LLM_API_KEY": "key-from-llm",
            "EVALUATOR_LLM_MODEL": "",
            "LLM_CHAT_MODEL": "model-from-llm",
        }
    )
    assert settings == LlmSettings(
        base_url="https://eval.example/v1",
        api_key="key-from-llm",
        model="model-from-llm",
    )


def test_resolve_settings_returns_none_when_any_value_missing():
    assert resolve_settings({"LLM_BASE_URL": "u", "LLM_API_KEY": "k"}) is None
    assert (
        resolve_settings(
            {
                "LLM_BASE_URL": "u",
                "LLM_API_KEY": "k",
                "LLM_CHAT_MODEL": "   ",
            }
        )
        is None
    )


def test_valid_chat_completion_returns_score_response():
    payload = _valid_score_payload()
    client = _client_for_responses(
        [
            httpx.Response(
                200,
                json=_completion_response(json.dumps(payload)),
            )
        ]
    )

    result = evaluate_with_provider(SETTINGS, MESSAGES, http_client=client)

    assert result.intentFidelity == 4
    assert result.evidence == ["plan includes GET /greetings"]
    assert len(client.calls) == 1
    call = client.calls[0]
    assert call["url"] == "https://api.example.com/v1/chat/completions"
    assert call["timeout"] == PROVIDER_TIMEOUT_SECONDS
    assert call["json"] == {
        "model": "gpt-test",
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": MESSAGES,
    }
    assert call["headers"]["Authorization"] == f"Bearer {API_KEY}"


@pytest.mark.parametrize(
    "content",
    [
        "```json\n" + json.dumps(_valid_score_payload()) + "\n```",
        "not-json",
        json.dumps([]),
        json.dumps(True),
        json.dumps(_valid_score_payload() | {"extraField": 1}),
        json.dumps(
            {
                "intentFidelity": True,
                "completeness": 4,
                "executability": 4,
                "unnecessaryComplexity": 4,
                "evidence": ["ok"],
            }
        ),
        json.dumps(
            {
                "intentFidelity": 4.5,
                "completeness": 4,
                "executability": 4,
                "unnecessaryComplexity": 4,
                "evidence": ["ok"],
            }
        ),
        json.dumps(
            {
                "intentFidelity": 6,
                "completeness": 4,
                "executability": 4,
                "unnecessaryComplexity": 4,
                "evidence": ["ok"],
            }
        ),
        json.dumps(
            {
                "intentFidelity": 4,
                "completeness": 4,
                "executability": 4,
                "unnecessaryComplexity": 4,
                "evidence": [],
            }
        ),
        json.dumps(
            {
                "intentFidelity": 4,
                "completeness": 4,
                "executability": 4,
                "unnecessaryComplexity": 4,
                "evidence": ["   "],
            }
        ),
    ],
)
def test_rejects_invalid_model_output_without_retry(content: str):
    sleeps: list[float] = []
    client = _client_for_responses(
        [httpx.Response(200, json=_completion_response(content))]
    )

    with pytest.raises(EvaluatorClientError) as exc_info:
        evaluate_with_provider(
            SETTINGS,
            MESSAGES,
            http_client=client,
            sleep=sleeps.append,
        )

    error = exc_info.value
    assert error.code == "EVALUATOR_INVALID_MODEL_OUTPUT"
    assert error.http_status == 502
    assert API_KEY not in error.message
    assert len(client.calls) == 1
    assert sleeps == []


@pytest.mark.parametrize(
    "response_json",
    [
        {},
        {"choices": []},
        {"choices": [{"message": {}}]},
        {"choices": [{"message": {"content": ""}}]},
    ],
)
def test_rejects_missing_or_empty_completion_content(response_json: dict[str, Any]):
    client = _client_for_responses([httpx.Response(200, json=response_json)])

    with pytest.raises(EvaluatorClientError) as exc_info:
        evaluate_with_provider(SETTINGS, MESSAGES, http_client=client)

    assert exc_info.value.code == "EVALUATOR_INVALID_MODEL_OUTPUT"
    assert len(client.calls) == 1


@pytest.mark.parametrize("status_code", [429, 502, 503, 504])
def test_retries_once_on_retryable_http_status(status_code: int):
    sleeps: list[float] = []
    payload = _valid_score_payload()
    client = _client_for_responses(
        [
            httpx.Response(status_code, json={"error": "busy"}),
            httpx.Response(
                200,
                json=_completion_response(json.dumps(payload)),
            ),
        ]
    )

    result = evaluate_with_provider(
        SETTINGS,
        MESSAGES,
        http_client=client,
        sleep=sleeps.append,
    )

    assert result.intentFidelity == 4
    assert len(client.calls) == 2
    assert sleeps == [RETRY_PAUSE_SECONDS]


@pytest.mark.parametrize(
    "exception",
    [httpx.ConnectError("connection refused"), httpx.ReadTimeout("timed out")],
)
def test_retries_once_on_connect_or_timeout_error(exception: Exception):
    sleeps: list[float] = []
    payload = _valid_score_payload()
    attempts = {"count": 0}

    def handler(_url, _json, _headers, _timeout):
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise exception
        return httpx.Response(
            200,
            json=_completion_response(json.dumps(payload)),
        )

    client = RecordingClient(handler)

    result = evaluate_with_provider(
        SETTINGS,
        MESSAGES,
        http_client=client,
        sleep=sleeps.append,
    )

    assert result.completeness == 4
    assert len(client.calls) == 2
    assert sleeps == [RETRY_PAUSE_SECONDS]


def test_transient_exhausted_after_retryable_status_fails_twice():
    sleeps: list[float] = []
    client = _client_for_responses(
        [
            httpx.Response(503, json={"error": "down"}),
            httpx.Response(503, json={"error": "still down"}),
        ]
    )

    with pytest.raises(EvaluatorClientError) as exc_info:
        evaluate_with_provider(
            SETTINGS,
            MESSAGES,
            http_client=client,
            sleep=sleeps.append,
        )

    error = exc_info.value
    assert error.code == "EVALUATOR_UPSTREAM_UNAVAILABLE"
    assert error.http_status == 503
    assert API_KEY not in error.message
    assert len(client.calls) == 2
    assert sleeps == [RETRY_PAUSE_SECONDS]


@pytest.mark.parametrize("status_code", [400, 401])
def test_no_retry_on_permanent_upstream_http_error(status_code: int):
    sleeps: list[float] = []
    client = _client_for_responses(
        [httpx.Response(status_code, json={"error": "bad request"})]
    )

    with pytest.raises(EvaluatorClientError) as exc_info:
        evaluate_with_provider(
            SETTINGS,
            MESSAGES,
            http_client=client,
            sleep=sleeps.append,
        )

    error = exc_info.value
    assert error.code == "EVALUATOR_UPSTREAM_ERROR"
    assert error.http_status == 502
    assert API_KEY not in error.message
    assert len(client.calls) == 1
    assert sleeps == []
