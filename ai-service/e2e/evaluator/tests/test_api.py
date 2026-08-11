import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from qip_e2e_evaluator.app import create_app
from qip_e2e_evaluator.client import EvaluatorClientError, LlmSettings
from qip_e2e_evaluator.models import ScoreResponse

API_KEY = "sk-test-secret-token-12345"
PLAN_SNIPPET = "UNIQUE_PLAN_MARKER_create_greetings_chain"
RUBRIC_TEXT = "# Rubric\nScore each dimension.\n"


def _configure_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
    monkeypatch.setenv("LLM_API_KEY", API_KEY)
    monkeypatch.setenv("LLM_CHAT_MODEL", "gpt-test")
    monkeypatch.delenv("EVALUATOR_LLM_BASE_URL", raising=False)
    monkeypatch.delenv("EVALUATOR_LLM_API_KEY", raising=False)
    monkeypatch.delenv("EVALUATOR_LLM_MODEL", raising=False)


def _clear_llm_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for key in (
        "LLM_BASE_URL",
        "LLM_API_KEY",
        "LLM_CHAT_MODEL",
        "EVALUATOR_LLM_BASE_URL",
        "EVALUATOR_LLM_API_KEY",
        "EVALUATOR_LLM_MODEL",
    ):
        monkeypatch.delenv(key, raising=False)


def _evaluate_payload() -> dict:
    return {
        "scenarioId": "product-greetings",
        "requiredFacts": ["GET /greetings"],
        "forbiddenFacts": ["service-call"],
        "requirementFacts": ["Create HTTP GET /greetings"],
        "terminalState": "PLAN_APPROVED",
        "plan": {"goal": PLAN_SNIPPET, "steps": []},
    }


def _valid_score() -> ScoreResponse:
    return ScoreResponse(
        intentFidelity=4,
        completeness=4,
        executability=4,
        unnecessaryComplexity=4,
        evidence=["plan includes GET /greetings"],
    )


@pytest.fixture
def rubric_path(tmp_path: Path) -> Path:
    path = tmp_path / "semantic-rubric.md"
    path.write_text(RUBRIC_TEXT, encoding="utf-8")
    return path


def test_health_ok_when_config_complete(monkeypatch: pytest.MonkeyPatch, rubric_path: Path):
    _configure_env(monkeypatch)
    client = TestClient(create_app(rubric_path=rubric_path))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "model": "configured"}


def test_health_unhealthy_when_key_missing(monkeypatch: pytest.MonkeyPatch, rubric_path: Path):
    _clear_llm_env(monkeypatch)
    monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
    monkeypatch.setenv("LLM_CHAT_MODEL", "gpt-test")
    client = TestClient(create_app(rubric_path=rubric_path))

    response = client.get("/health")

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "EVALUATOR_CONFIG_INCOMPLETE"
    assert API_KEY not in json.dumps(body)


def test_evaluate_success_with_fake_provider(
    monkeypatch: pytest.MonkeyPatch,
    rubric_path: Path,
):
    _configure_env(monkeypatch)

    def fake_factory(_settings: LlmSettings, _messages: list[dict[str, str]]) -> ScoreResponse:
        return _valid_score()

    client = TestClient(
        create_app(rubric_path=rubric_path, client_factory=fake_factory)
    )

    response = client.post("/evaluate", json=_evaluate_payload())

    assert response.status_code == 200
    assert response.json() == _valid_score().model_dump()


def test_evaluate_incomplete_config_returns_503(
    monkeypatch: pytest.MonkeyPatch,
    rubric_path: Path,
):
    _clear_llm_env(monkeypatch)
    client = TestClient(create_app(rubric_path=rubric_path))

    response = client.post("/evaluate", json=_evaluate_payload())

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "EVALUATOR_CONFIG_INCOMPLETE"
    assert PLAN_SNIPPET not in json.dumps(body)
    assert API_KEY not in json.dumps(body)


@pytest.mark.parametrize(
    ("error", "expected_status"),
    [
        (
            EvaluatorClientError(
                "EVALUATOR_UPSTREAM_UNAVAILABLE",
                "Provider is temporarily unavailable",
                503,
            ),
            503,
        ),
        (
            EvaluatorClientError(
                "EVALUATOR_UPSTREAM_ERROR",
                "Provider returned a permanent error",
                502,
            ),
            502,
        ),
        (
            EvaluatorClientError(
                "EVALUATOR_INVALID_MODEL_OUTPUT",
                "Model output does not match score schema",
                502,
            ),
            502,
        ),
    ],
)
def test_evaluate_maps_provider_errors_to_sanitized_bodies(
    monkeypatch: pytest.MonkeyPatch,
    rubric_path: Path,
    error: EvaluatorClientError,
    expected_status: int,
):
    _configure_env(monkeypatch)

    def failing_factory(
        _settings: LlmSettings,
        _messages: list[dict[str, str]],
    ) -> ScoreResponse:
        raise error

    client = TestClient(
        create_app(rubric_path=rubric_path, client_factory=failing_factory)
    )

    response = client.post("/evaluate", json=_evaluate_payload())

    assert response.status_code == expected_status
    body = response.json()
    assert body == {"code": error.code, "message": error.message}
    serialized = json.dumps(body)
    assert API_KEY not in serialized
    assert "sk-" not in serialized
    assert PLAN_SNIPPET not in serialized


def test_evaluate_invalid_request_returns_422(
    monkeypatch: pytest.MonkeyPatch,
    rubric_path: Path,
):
    _configure_env(monkeypatch)
    client = TestClient(create_app(rubric_path=rubric_path))

    response = client.post("/evaluate", json={"scenarioId": ""})

    assert response.status_code == 422


def test_evaluate_unexpected_defect_returns_500(
    monkeypatch: pytest.MonkeyPatch,
    rubric_path: Path,
):
    _configure_env(monkeypatch)

    def broken_factory(
        _settings: LlmSettings,
        _messages: list[dict[str, str]],
    ) -> ScoreResponse:
        raise RuntimeError("unexpected defect")

    client = TestClient(
        create_app(rubric_path=rubric_path, client_factory=broken_factory)
    )

    response = client.post("/evaluate", json=_evaluate_payload())

    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "EVALUATOR_INTERNAL_ERROR"
    assert PLAN_SNIPPET not in json.dumps(body)
    assert "unexpected defect" not in json.dumps(body)
