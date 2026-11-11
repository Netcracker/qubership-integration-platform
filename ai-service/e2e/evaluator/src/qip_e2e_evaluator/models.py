from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

PROVIDER_TIMEOUT_SECONDS = 60
RETRY_PAUSE_SECONDS = 1
CALLER_TIMEOUT_SECONDS = 135


def _nonempty_stripped(value: str) -> str:
    stripped = value.strip()
    if not stripped:
        raise ValueError("must be a non-empty string")
    return stripped


class EvaluateRequest(BaseModel):
    scenarioId: str
    requiredFacts: list[str]
    forbiddenFacts: list[str]
    requirementFacts: list[str]
    terminalState: Literal["PLAN_APPROVED"]
    plan: dict[str, Any]

    @field_validator("scenarioId")
    @classmethod
    def validate_scenario_id(cls, value: str) -> str:
        return _nonempty_stripped(value)

    @field_validator("requiredFacts", "forbiddenFacts", "requirementFacts")
    @classmethod
    def validate_fact_lists(cls, value: list[str]) -> list[str]:
        return [_nonempty_stripped(item) for item in value]

    @field_validator("plan")
    @classmethod
    def validate_plan(cls, value: dict[str, Any]) -> dict[str, Any]:
        if not value:
            raise ValueError("plan must be a non-empty mapping")
        return value

    @model_validator(mode="after")
    def validate_requirement_facts_length(self) -> "EvaluateRequest":
        if len(self.requirementFacts) < 1:
            raise ValueError("requirementFacts must contain at least one item")
        return self


class ScoreResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    intentFidelity: int = Field(ge=0, le=5)
    completeness: int = Field(ge=0, le=5)
    executability: int = Field(ge=0, le=5)
    unnecessaryComplexity: int = Field(ge=0, le=5)
    evidence: list[str] = Field(min_length=1)

    @field_validator("evidence")
    @classmethod
    def validate_evidence(cls, value: list[str]) -> list[str]:
        return [_nonempty_stripped(item) for item in value]


class ErrorBody(BaseModel):
    code: str
    message: str


class HealthResponse(BaseModel):
    status: Literal["ok"]
    model: Literal["configured"]
