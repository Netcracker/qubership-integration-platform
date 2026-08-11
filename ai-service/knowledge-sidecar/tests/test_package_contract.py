from pathlib import Path

import pytest

from package_fixtures import mutate_fixture
from qip_knowledge_sidecar.package import PackageEligibilityError, validate_package


def test_accepts_certified_package_and_preserves_identity(package_a: Path) -> None:
    selected = validate_package(package_a)
    assert selected.ref.package_key == "package-a@1.0.0"
    assert selected.ref.certification_status == "CERTIFIED"
    assert selected.ref.package_checksum.startswith("sha256:")
    assert selected.ref.certification_digest.startswith("sha256:")


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        ("missing_manifest", "KNOWLEDGE_MANIFEST_INVALID"),
        ("unsupported_schema", "KNOWLEDGE_SCHEMA_UNSUPPORTED"),
        ("wrong_product", "KNOWLEDGE_RUNTIME_INCOMPATIBLE"),
        ("unsupported_runtime", "KNOWLEDGE_RUNTIME_INCOMPATIBLE"),
        ("missing_objects", "KNOWLEDGE_PACKAGE_INCOMPLETE"),
        ("missing_capabilities", "KNOWLEDGE_PACKAGE_INCOMPLETE"),
        ("malformed_capabilities", "KNOWLEDGE_CAPABILITY_INDEX_INVALID"),
        ("missing_capability_shape", "KNOWLEDGE_CAPABILITY_INDEX_INVALID"),
        ("checksum_mismatch", "KNOWLEDGE_INTEGRITY_FAILURE"),
        ("uncertified", "KNOWLEDGE_CERTIFICATION_REQUIRED"),
        ("count_mismatch", "KNOWLEDGE_INTEGRITY_FAILURE"),
        ("duplicate_id", "KNOWLEDGE_INTEGRITY_FAILURE"),
    ],
)
def test_rejects_each_eligibility_failure(
    package_a: Path,
    mutation: str,
    code: str,
) -> None:
    mutate_fixture(package_a, mutation)
    with pytest.raises(PackageEligibilityError) as raised:
        validate_package(package_a)
    assert raised.value.code == code
