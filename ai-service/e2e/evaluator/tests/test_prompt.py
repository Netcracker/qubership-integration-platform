from qip_e2e_evaluator.models import EvaluateRequest
from qip_e2e_evaluator.prompt import build_messages

RUBRIC = "# Product CREATE semantic rubric\nScore each dimension as an integer from 0 to 5.\n"


def _req(**overrides) -> EvaluateRequest:
    payload = dict(
        scenarioId="product-greetings",
        requiredFacts=["GET /greetings"],
        forbiddenFacts=["service-call"],
        requirementFacts=["Create HTTP GET /greetings"],
        terminalState="PLAN_APPROVED",
        plan={"goal": "Create greetings chain", "steps": []},
    )
    payload.update(overrides)
    return EvaluateRequest(**payload)


def test_prompt_includes_rubric_facts_plan_and_complexity_direction():
    messages = build_messages(_req(), RUBRIC)
    blob = "\n".join(m["content"] for m in messages)
    assert "Product CREATE semantic rubric" in blob
    assert "product-greetings" in blob
    assert "PLAN_APPROVED" in blob
    assert "GET /greetings" in blob
    assert "service-call" in blob
    assert "Create HTTP GET /greetings" in blob
    assert "Create greetings chain" in blob
    assert "unnecessaryComplexity" in blob
    assert "0" in blob and "5" in blob
    assert "never infer" in blob.lower() or "only the supplied evidence" in blob.lower()
    assert "intentFidelity" in blob


def test_prompt_excludes_credentials():
    messages = build_messages(_req(), RUBRIC)
    blob = "\n".join(m["content"] for m in messages)
    for forbidden in ("API_KEY", "sk-", "Bearer", "EVALUATOR_LLM"):
        assert forbidden not in blob


def test_prompt_distinguishes_property_less_else_from_else_condition_fact():
    messages = build_messages(
        _req(
            scenarioId="product-lang-router",
            requiredFacts=["/lang-router", "even minute", "odd minute"],
            forbiddenFacts=["else.condition", "else.priority"],
            plan={
                "branchFacts": ["${exchangeProperty.currentMinute} % 2 == 0", "else"],
                "endpointFacts": ["GET", "/lang-router", "external"],
            },
        ),
        RUBRIC,
    )
    system = messages[0]["content"]
    assert "property-less else" in system.lower() or "bare" in system.lower()
    assert "else.condition" in system
    assert "exact strings" in system.lower() or "exact string" in system.lower()
    assert "externalRoute" in system or "HTTP route visibility" in system
    assert "service-call" in system.lower()


def test_prompt_keeps_else_condition_as_real_forbidden_when_listed():
    messages = build_messages(
        _req(forbiddenFacts=["else.condition", "else.priority"]),
        RUBRIC,
    )
    blob = "\n".join(m["content"] for m in messages)
    assert "else.condition" in blob
    assert "else.priority" in blob
    assert "must not treat" in blob.lower() or "do not treat" in blob.lower()
