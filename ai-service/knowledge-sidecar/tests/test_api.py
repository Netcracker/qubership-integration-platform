from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from package_fixtures import mutate_fixture
from qip_knowledge_sidecar.app import create_app


def test_package_and_queries_return_the_same_ref(client: TestClient) -> None:
    package = client.get("/v1/package")
    exact = client.post(
        "/v1/query/exact",
        json={
            "expectedPackageChecksum": (
                package.json()["packageRef"]["packageChecksum"]
            ),
            "id": "package-a-standard",
        },
    )
    assert package.status_code == 200
    assert "tier" not in package.json()["packageRef"]
    assert exact.status_code == 200
    assert exact.json()["packageRef"] == package.json()["packageRef"]
    assert set(exact.json()["object"]) == {
        "ir_version",
        "id",
        "type",
        "title",
        "summary",
        "metadata",
        "relations",
        "content",
        "version",
        "status",
        "source",
    }


def test_rejects_stale_checksum(client: TestClient) -> None:
    response = client.post(
        "/v1/query/filter",
        json={
            "expectedPackageChecksum": "sha256:stale",
            "type": "Standard",
            "limit": 5,
        },
    )
    assert response.status_code == 409
    assert response.json()["code"] == "KNOWLEDGE_PACKAGE_PIN_MISMATCH"


def test_unknown_exact_id_is_not_found(client: TestClient) -> None:
    checksum = client.get("/v1/package").json()["packageRef"]["packageChecksum"]
    response = client.post(
        "/v1/query/exact",
        json={"expectedPackageChecksum": checksum, "id": "missing-id"},
    )
    assert response.status_code == 404
    assert response.json()["code"] == "KNOWLEDGE_NOT_FOUND"


def test_vector_route_is_absent(client: TestClient) -> None:
    assert client.post("/v1/query/vector", json={}).status_code == 404


def test_invalid_package_keeps_liveness_and_fails_readiness(
    invalid_package_app: FastAPI,
) -> None:
    with TestClient(invalid_package_app) as client:
        assert client.get("/v1/health/live").status_code == 200
        response = client.get("/v1/health/ready")
        assert response.status_code == 503
        assert response.json()["code"] == "KNOWLEDGE_CAPABILITY_INDEX_INVALID"


def test_sdk_parse_failure_keeps_liveness_and_fails_readiness(
    package_a: Path,
) -> None:
    mutate_fixture(package_a, "malformed_canonical_ir")
    app = create_app(package_a)
    with TestClient(app) as client:
        assert client.get("/v1/health/live").status_code == 200
        response = client.get("/v1/health/ready")
        assert response.status_code == 503
        assert response.json()["code"] == "KNOWLEDGE_RUNTIME_INCOMPATIBLE"


def test_invalid_ir_keeps_liveness_and_fails_readiness(
    malformed_ir_package_app: FastAPI,
) -> None:
    with TestClient(malformed_ir_package_app) as client:
        assert client.get("/v1/health/live").status_code == 200
        response = client.get("/v1/health/ready")
        assert response.status_code == 503
        assert response.json()["code"] == "KNOWLEDGE_RUNTIME_INCOMPATIBLE"
