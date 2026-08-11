import os
from collections.abc import Iterator
from pathlib import Path
import shutil

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from knowledge_sdk import KnowledgeSDK

from package_fixtures import mutate_fixture, rewrite_checksums, write_package
from qip_knowledge_sidecar.app import create_app
from qip_knowledge_sidecar.runtime import RuntimeAdapter


@pytest.fixture
def package_a(tmp_path: Path) -> Path:
    return write_package(
        tmp_path,
        key="package-a",
        object_id="CIP:STD-000001",
        alias="package-a-standard",
    )


@pytest.fixture
def package_b(tmp_path: Path) -> Path:
    return write_package(
        tmp_path,
        key="package-b",
        object_id="CIP:STD-000002",
        alias="package-b-standard",
    )


@pytest.fixture
def upstream_package() -> Path:
    configured = os.getenv("QIP_KNOWLEDGE_TEST_PACKAGE")
    if not configured:
        pytest.skip("QIP_KNOWLEDGE_TEST_PACKAGE is not set")
    package = Path(configured).expanduser().resolve()
    if not package.is_dir():
        pytest.fail(f"QIP_KNOWLEDGE_TEST_PACKAGE is not a directory: {package}")
    return package


@pytest.fixture
def runtime(upstream_package: Path) -> Iterator[RuntimeAdapter]:
    sdk = KnowledgeSDK.create(
        provider="lancedb",
        runtime_knowledge_dir=str(upstream_package),
        product="CIP",
        sdk_version="1.0.0",
    )
    try:
        yield RuntimeAdapter(sdk, upstream_package)
    finally:
        sdk.close()


@pytest.fixture
def client(package_a: Path) -> Iterator[TestClient]:
    app = create_app(package_a)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def invalid_package_app(tmp_path: Path, package_a: Path) -> FastAPI:
    invalid = tmp_path / "invalid-package"
    shutil.copytree(package_a, invalid)
    os.chmod(invalid / "CHECKSUMS.sha256", 0o644)
    (invalid / "capabilities" / "capability-index.yaml").write_text(
        "capability_index: [",
        encoding="utf-8",
    )
    rewrite_checksums(invalid)
    return create_app(invalid)


@pytest.fixture
def malformed_ir_package_app(tmp_path: Path) -> FastAPI:
    package = write_package(
        tmp_path,
        key="malformed-package",
        object_id="CIP:STD-000003",
        alias="malformed-package-standard",
    )
    mutate_fixture(package, "malformed_canonical_ir")
    return create_app(package)
