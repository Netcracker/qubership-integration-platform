import pytest
from pydantic import ValidationError

from qip_e2e_evaluator.models import EvaluateRequest, ScoreResponse


def test_rejects_empty_plan_and_requirement_facts():
    with pytest.raises(ValidationError):
        EvaluateRequest(
            scenarioId="s",
            requiredFacts=[],
            forbiddenFacts=[],
            requirementFacts=[],
            terminalState="PLAN_APPROVED",
            plan={},
        )


def test_rejects_non_plan_approved_terminal_state():
    with pytest.raises(ValidationError):
        EvaluateRequest(
            scenarioId="s",
            requiredFacts=["a"],
            forbiddenFacts=[],
            requirementFacts=["a"],
            terminalState="WAITING_FOR_APPROVAL",
            plan={"goal": "x"},
        )


def test_rejects_empty_fact_strings():
    with pytest.raises(ValidationError):
        EvaluateRequest(
            scenarioId="s",
            requiredFacts=[""],
            forbiddenFacts=[],
            requirementFacts=["a"],
            terminalState="PLAN_APPROVED",
            plan={"goal": "x"},
        )


def test_score_rejects_unknown_fields_and_out_of_range():
    with pytest.raises(ValidationError):
        ScoreResponse(
            intentFidelity=4,
            completeness=4,
            executability=4,
            unnecessaryComplexity=4,
            evidence=["ok"],
            extraField=1,
        )
    with pytest.raises(ValidationError):
        ScoreResponse(
            intentFidelity=6,
            completeness=4,
            executability=4,
            unnecessaryComplexity=4,
            evidence=["ok"],
        )
