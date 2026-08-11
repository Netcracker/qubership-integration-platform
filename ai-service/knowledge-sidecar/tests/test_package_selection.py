from pathlib import Path

import pytest

from package_fixtures import mutate_fixture
from qip_knowledge_sidecar.package import PackageEligibilityError, validate_package


def test_invalid_package_fails_validation(package_a: Path) -> None:
    mutate_fixture(package_a, "checksum_mismatch")
    with pytest.raises(PackageEligibilityError) as raised:
        validate_package(package_a)
    assert raised.value.code == "KNOWLEDGE_INTEGRITY_FAILURE"


def test_malformed_capabilities_fail_validation(package_a: Path) -> None:
    mutate_fixture(package_a, "malformed_capabilities")
    with pytest.raises(PackageEligibilityError) as raised:
        validate_package(package_a)
    assert raised.value.code == "KNOWLEDGE_CAPABILITY_INDEX_INVALID"


def test_packages_have_different_checksums(
    package_a: Path,
    package_b: Path,
) -> None:
    assert (
        validate_package(package_a).ref.package_checksum
        != validate_package(package_b).ref.package_checksum
    )
