from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import yaml

CAPABILITY_FILES: dict[str, Any] = {
    "capability-catalog.yaml": {
        "catalog": {"extraction_version": "1.0", "count": 1},
        "capabilities": [
            {
                "id": "CAP-000001",
                "name": "Neutral",
                "token": "neutral",
                "facet": "general",
                "aliases": [],
                "objects": 1,
            }
        ],
    },
    "capability-index.yaml": {
        "capability_index": {
            "token_to_capability": {"neutral": "neutral"},
        }
    },
    "capability-relations.yaml": {
        "capability_relations": {
            "Neutral": {
                "id": "CAP-000001",
                "contains": [],
                "by_type": {"Standard": 1},
            }
        }
    },
}


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_yaml(path: Path, value: object) -> None:
    path.write_text(
        yaml.safe_dump(value, sort_keys=True),
        encoding="utf-8",
    )


def rewrite_checksums(package: Path) -> None:
    names = [
        "capabilities/capability-catalog.yaml",
        "capabilities/capability-index.yaml",
        "capabilities/capability-relations.yaml",
        "manifest.yaml",
        "objects.jsonl",
    ]
    lines = [
        f"{_sha256((package / name).read_bytes())}  {name}"
        for name in names
        if (package / name).is_file()
    ]
    (package / "CHECKSUMS.sha256").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )


def refresh_fixture_certificate(package: Path) -> None:
    names = sorted(
        ["objects.jsonl", *(f"capabilities/{name}" for name in CAPABILITY_FILES)]
    )
    payload = "\n".join(
        f"{name}:{_sha256((package / name).read_bytes())}" for name in names
    )
    checksum = "sha256:" + _sha256(payload.encode("utf-8"))
    manifest_path = package / "manifest.yaml"
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
    manifest["integrity"]["package_checksum"] = checksum
    _write_yaml(manifest_path, manifest)
    certificate_path = package / "runtime-certification.yaml"
    certificate = json.loads(certificate_path.read_text(encoding="utf-8"))
    certificate["certification"]["manifest_sha256"] = _sha256(
        manifest_path.read_bytes()
    )
    certificate["certification"]["package"]["manifest_hash"] = checksum
    certificate_path.write_text(
        json.dumps(certificate, sort_keys=True), encoding="utf-8"
    )
    rewrite_checksums(package)


def write_package(root: Path, *, key: str, object_id: str, alias: str) -> Path:
    package = root / key
    package.mkdir()
    (package / "capabilities").mkdir()
    knowledge_version = "1.0.0"
    object_ir = {
        "ir_version": "1.0",
        "id": object_id,
        "type": "Standard",
        "title": f"{key} standard",
        "summary": f"{key} summary",
        "metadata": {
            "aliases": [alias],
            "capabilities": ["neutral", key],
            "tags": ["fixture"],
        },
        "relations": [],
        "content": {
            "format": "markdown",
            "body": f"# {key}\n\nNeutral fixture body.",
            "raw": f"# {key}\n\nNeutral fixture body.",
            "sections": [],
        },
        "version": knowledge_version,
        "status": "active",
        "source": {
            "format": "markdown",
            "document": f"fixtures/{key}.md",
            "section_id": key,
            "hash": "sha256:" + ("1" * 64 if key == "package-a" else "2" * 64),
            "knowledge_version": knowledge_version,
        },
    }
    object_bytes = (
        json.dumps(object_ir, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode()
    (package / "objects.jsonl").write_bytes(object_bytes)

    relations = CAPABILITY_FILES["capability-relations.yaml"]
    relations["capability_relations"]["Neutral"]["contains"] = [object_id]
    for name, payload in CAPABILITY_FILES.items():
        _write_yaml(package / "capabilities" / name, payload)

    names = sorted(
        ["objects.jsonl", *(f"capabilities/{name}" for name in CAPABILITY_FILES)]
    )
    payload = "\n".join(
        f"{name}:{_sha256((package / name).read_bytes())}" for name in names
    )
    package_checksum = "sha256:" + _sha256(payload.encode("utf-8"))
    manifest = {
        "manifest_schema": "1.0",
        "product": key,
        "knowledge_version": knowledge_version,
        "runtime_sdk_version": ">=1.0.0 <2.0",
        "provider": "lancedb",
        "schema_version": "1.0.0",
        "relation_schema_version": "1.0.0",
        "collections": {"Standard": 1},
        "total_objects": 1,
        "relations_count": 0,
        "integrity": {
            "package_checksum": package_checksum,
            "checksum_algorithm": "rocky-export-v1",
        },
    }
    _write_yaml(package / "manifest.yaml", manifest)

    certification = {
        "certification": {
            "status": "CERTIFIED",
            "profile": "rocky-export-v1",
            "manifest_sha256": _sha256((package / "manifest.yaml").read_bytes()),
            "gates": {
                name: "PASS"
                for name in (
                    "IR",
                    "CAPABILITIES",
                    "RELATIONS",
                    "EMBEDDINGS",
                    "LANCEDB",
                    "RETRIEVAL",
                )
            },
            "pipeline_version": "1.0",
            "package": {
                "key": f"{key}@{knowledge_version}",
                "manifest_hash": package_checksum,
            },
            "total_objects": 1,
        }
    }
    (package / "runtime-certification.yaml").write_text(
        json.dumps(certification, sort_keys=True),
        encoding="utf-8",
    )
    rewrite_checksums(package)
    return package


def mutate_fixture(package: Path, mutation: str) -> None:
    if mutation == "missing_manifest":
        (package / "manifest.yaml").unlink()
    elif mutation == "unsupported_schema":
        manifest = yaml.safe_load((package / "manifest.yaml").read_text())
        manifest["schema_version"] = "2.0.0"
        _write_yaml(package / "manifest.yaml", manifest)
    elif mutation == "wrong_product":
        manifest = yaml.safe_load((package / "manifest.yaml").read_text())
        manifest["product"] = "   "
        _write_yaml(package / "manifest.yaml", manifest)
    elif mutation == "unsupported_runtime":
        manifest = yaml.safe_load((package / "manifest.yaml").read_text())
        manifest["runtime_sdk_version"] = ">=2.0.0 <3.0"
        _write_yaml(package / "manifest.yaml", manifest)
    elif mutation == "missing_objects":
        (package / "objects.jsonl").unlink()
    elif mutation == "missing_capabilities":
        (package / "capabilities" / "capability-index.yaml").unlink()
    elif mutation == "malformed_capabilities":
        (package / "capabilities" / "capability-index.yaml").write_text(
            "capability_index: [",
            encoding="utf-8",
        )
        rewrite_checksums(package)
    elif mutation == "missing_capability_shape":
        _write_yaml(
            package / "capabilities" / "capability-relations.yaml",
            {"wrong_root": {}},
        )
        rewrite_checksums(package)
    elif mutation == "checksum_mismatch":
        with (package / "objects.jsonl").open("ab") as stream:
            stream.write(b" ")
    elif mutation == "uncertified":
        certification = json.loads((package / "runtime-certification.yaml").read_text())
        certification["certification"]["status"] = "REJECTED"
        (package / "runtime-certification.yaml").write_text(
            json.dumps(certification, sort_keys=True),
            encoding="utf-8",
        )
    elif mutation == "count_mismatch":
        manifest = yaml.safe_load((package / "manifest.yaml").read_text())
        manifest["total_objects"] = 2
        _write_yaml(package / "manifest.yaml", manifest)
        rewrite_checksums(package)
    elif mutation == "duplicate_id":
        row = (package / "objects.jsonl").read_bytes()
        (package / "objects.jsonl").write_bytes(row + row)
        refresh_fixture_certificate(package)
    elif mutation == "malformed_canonical_ir":
        row = json.loads((package / "objects.jsonl").read_text())
        row["relations"] = [{"from": row["id"], "to": "CIP:RULE-000001"}]
        (package / "objects.jsonl").write_text(
            json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        refresh_fixture_certificate(package)
    else:
        raise AssertionError(f"unknown fixture mutation: {mutation}")
