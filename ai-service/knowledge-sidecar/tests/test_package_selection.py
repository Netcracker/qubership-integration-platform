from pathlib import Path
import hashlib
import json

import pytest
import yaml
from fastapi.testclient import TestClient
from package_fixtures import mutate_fixture, rewrite_checksums
from qip_knowledge_sidecar.app import create_app
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


def test_rehashed_content_does_not_match_previous_certificate(package_a: Path) -> None:
    names = sorted(
        [
            "objects.jsonl",
            "capabilities/capability-catalog.yaml",
            "capabilities/capability-index.yaml",
            "capabilities/capability-relations.yaml",
        ]
    )
    entries = "\n".join(
        f"{name}:{hashlib.sha256((package_a / name).read_bytes()).hexdigest()}"
        for name in names
    )
    digest = "sha256:" + hashlib.sha256(entries.encode()).hexdigest()
    manifest_path = package_a / "manifest.yaml"
    manifest = yaml.safe_load(manifest_path.read_text())
    manifest["integrity"] = {
        "package_checksum": digest,
        "checksum_algorithm": "rocky-export-v1",
    }
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    certificate_path = package_a / "runtime-certification.yaml"
    certificate = json.loads(certificate_path.read_text())
    certificate["certification"]["profile"] = "rocky-export-v1"
    certificate["certification"]["gates"] = {
        name: "PASS"
        for name in (
            "IR",
            "CAPABILITIES",
            "RELATIONS",
            "EMBEDDINGS",
            "LANCEDB",
            "RETRIEVAL",
        )
    }
    certificate["certification"]["package"]["manifest_hash"] = digest
    certificate["certification"]["package"]["key"] = f"{manifest['product']}@1.0.0"
    certificate["certification"]["manifest_sha256"] = hashlib.sha256(
        manifest_path.read_bytes()
    ).hexdigest()
    certificate_path.write_text(json.dumps(certificate), encoding="utf-8")
    rewrite_checksums(package_a)
    assert validate_package(package_a).ref.package_checksum == digest

    manifest["integrity"].pop("checksum_algorithm")
    certificate["certification"].pop("profile")
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    certificate_path.write_text(json.dumps(certificate), encoding="utf-8")
    rewrite_checksums(package_a)
    with pytest.raises(PackageEligibilityError) as downgrade:
        validate_package(package_a)
    assert downgrade.value.code == "KNOWLEDGE_CERTIFICATION_REQUIRED"
    manifest["integrity"]["checksum_algorithm"] = "rocky-export-v1"
    certificate["certification"]["profile"] = "rocky-export-v1"
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    certificate_path.write_text(json.dumps(certificate), encoding="utf-8")
    rewrite_checksums(package_a)

    store = package_a / "lancedb"
    store.mkdir()
    stale = json.loads((package_a / "objects.jsonl").read_text())
    stale["summary"] = "Stale store content"
    (store / "knowledge_objects.jsonl").write_text(
        json.dumps({"ir": stale}) + "\n", encoding="utf-8"
    )
    with TestClient(create_app(package_a)) as client:
        ready = client.get("/v1/health/ready")
        assert ready.status_code == 503
        assert ready.json()["code"] == "KNOWLEDGE_INTEGRITY_FAILURE"
    (store / "knowledge_objects.jsonl").unlink()
    store.rmdir()

    manifest["knowledge_version"] = "1.0.1"
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    rewrite_checksums(package_a)
    with pytest.raises(PackageEligibilityError) as identity_error:
        validate_package(package_a)
    assert identity_error.value.code == "KNOWLEDGE_INTEGRITY_FAILURE"
    manifest["knowledge_version"] = "1.0.0"
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    rewrite_checksums(package_a)

    manifest["embedding_model"] = {"dim": 128}
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    rewrite_checksums(package_a)
    with pytest.raises(PackageEligibilityError) as manifest_error:
        validate_package(package_a)
    assert manifest_error.value.code == "KNOWLEDGE_INTEGRITY_FAILURE"
    del manifest["embedding_model"]
    manifest_path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    rewrite_checksums(package_a)

    certificate["certification"]["gates"]["IR"] = "FAIL"
    certificate_path.write_text(json.dumps(certificate), encoding="utf-8")
    with pytest.raises(PackageEligibilityError) as gate_error:
        validate_package(package_a)
    assert gate_error.value.code == "KNOWLEDGE_CERTIFICATION_REQUIRED"
    certificate["certification"]["gates"]["IR"] = "PASS"
    certificate_path.write_text(json.dumps(certificate), encoding="utf-8")

    objects_path = package_a / "objects.jsonl"
    obj = json.loads(objects_path.read_text())
    obj["summary"] = "Changed after certification"
    objects_path.write_text(json.dumps(obj) + "\n", encoding="utf-8")
    rewrite_checksums(package_a)
    with pytest.raises(PackageEligibilityError) as raised:
        validate_package(package_a)
    assert raised.value.code == "KNOWLEDGE_INTEGRITY_FAILURE"
