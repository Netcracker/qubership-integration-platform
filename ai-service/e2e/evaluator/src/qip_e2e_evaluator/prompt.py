import json

from qip_e2e_evaluator.models import EvaluateRequest

_SYSTEM_SUFFIX = """\
Score every dimension as an integer from 0 to 5, where 0 is worst and 5 is best.
For unnecessaryComplexity, higher scores mean less unnecessary complexity.

Forbidden-fact matching rules:
- Match forbidden facts as exact strings (or exact key=value tokens such as else.condition=...).
- Must not treat bare / property-less else as forbidden when only else.condition or else.priority is listed.
- A branchFacts entry that is exactly "else" is valid CIP default-branch presentation.

Endpoint vocabulary:
- endpointFacts token "external" means HTTP route visibility (externalRoute), not a service-call.
- Do not treat "external" as violating "no external service calls" unless that exact forbidden fact is listed.

Respond with JSON containing exactly these fields:
- intentFidelity (integer 0-5)
- completeness (integer 0-5)
- executability (integer 0-5)
- unnecessaryComplexity (integer 0-5)
- evidence (non-empty array of strings citing only the supplied evidence)

Use only the supplied evidence; never infer missing bindings.\
"""


def build_messages(request: EvaluateRequest, rubric_text: str) -> list[dict[str, str]]:
    system_content = f"{rubric_text.rstrip()}\n\n{_SYSTEM_SUFFIX}"
    user_content = "\n".join(
        [
            f"Scenario ID: {request.scenarioId}",
            f"Terminal state: {request.terminalState}",
            "",
            "Required facts:",
            *_indent_list(request.requiredFacts),
            "",
            "Forbidden facts:",
            *_indent_list(request.forbiddenFacts),
            "",
            "Requirement facts:",
            *_indent_list(request.requirementFacts),
            "",
            "Plan:",
            json.dumps(request.plan, indent=2, sort_keys=True),
        ]
    )
    return [
        {"role": "system", "content": system_content},
        {"role": "user", "content": user_content},
    ]


def _indent_list(items: list[str]) -> list[str]:
    return [f"- {item}" for item in items]
