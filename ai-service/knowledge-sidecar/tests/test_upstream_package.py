import pytest

from qip_knowledge_sidecar.runtime import (
    RuntimeAdapter,
    RuntimeErrorResponse,
)


def test_exact_preserves_canonical_ir(runtime: RuntimeAdapter) -> None:
    result = runtime.exact("gen-04-error-handling-generator")
    assert result["id"] == "CIP:GEN-000005"
    assert result["content"]["body"]
    assert "gen-04-error-handling-generator" in result["metadata"]["aliases"]
    assert result["source"]["document"] == "ai/GENERATOR_CONTRACTS.md"
    assert result["version"]
    assert isinstance(result["relations"], list)


@pytest.mark.parametrize("unknown_id", ["GEN-04", "VR-EH-001", "missing-id"])
def test_exact_does_not_infer_identity(runtime: RuntimeAdapter, unknown_id: str) -> None:
    with pytest.raises(RuntimeErrorResponse) as raised:
        runtime.exact(unknown_id)
    assert raised.value.code == "KNOWLEDGE_NOT_FOUND"


def test_context_uses_compiled_capability_mapping(runtime: RuntimeAdapter) -> None:
    result = runtime.context(
        request_text="Add structured error handling",
        capability_id="cip-error-handling-generator",
        phase="GENERATOR",
        element_types=["try-catch-finally-2", "catch-2"],
        max_objects=12,
        max_chars=20_000,
    )
    ids = {item["id"] for item in result.objects}
    assert "CIP:GEN-000049" in ids
    assert result.content_chars <= 20_000
    assert set(result.capabilities) >= {"error", "handling", "generator", "mapping", "rule"}


def test_filter_uses_the_upstream_type_contract(runtime: RuntimeAdapter) -> None:
    values = runtime.filter(type_name="ValidatorRule", limit=3)
    assert len(values) == 3
    assert all(value["type"] == "ValidatorRule" for value in values)
