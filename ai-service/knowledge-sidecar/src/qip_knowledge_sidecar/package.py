from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, NoReturn

import yaml


CAPABILITY_FILES = (
    "capabilities/capability-catalog.yaml",
    "capabilities/capability-index.yaml",
    "capabilities/capability-relations.yaml",
)
REQUIRED_CHECKSUMS = ("manifest.yaml", "objects.jsonl", *CAPABILITY_FILES)


class PackageEligibilityError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class PackageRef:
    package_key: str
    knowledge_version: str
    schema_version: str
    package_checksum: str
    certification_status: str
    certification_digest: str


@dataclass(frozen=True)
class ValidatedPackage:
    path: Path
    ref: PackageRef
    manifest: dict[str, Any]


def _fail(code: str, message: str) -> NoReturn:
    raise PackageEligibilityError(code, message)


def _mapping(value: Any, *, code: str, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(code, f"{name} must be a mapping")
    return value


def _yaml_mapping(path: Path, *, code: str) -> dict[str, Any]:
    try:
        return _mapping(
            yaml.safe_load(path.read_text(encoding="utf-8")),
            code=code,
            name=path.name,
        )
    except PackageEligibilityError:
        raise
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        _fail(code, f"Cannot read {path.name}: {error}")


def _major(value: Any, *, name: str) -> int:
    try:
        return int(str(value).split(".", 1)[0])
    except (TypeError, ValueError):
        _fail("KNOWLEDGE_SCHEMA_UNSUPPORTED", f"{name} is invalid: {value!r}")


def _checksums(package: Path) -> dict[str, str]:
    path = package / "CHECKSUMS.sha256"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        _fail("KNOWLEDGE_PACKAGE_INCOMPLETE", f"Cannot read CHECKSUMS.sha256: {error}")

    checksums: dict[str, str] = {}
    for line_number, line in enumerate(lines, start=1):
        digest, separator, name = line.partition("  ")
        relative = PurePosixPath(name)
        if (
            not separator
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
            or not name
            or relative.is_absolute()
            or ".." in relative.parts
            or name in checksums
        ):
            _fail(
                "KNOWLEDGE_INTEGRITY_FAILURE",
                f"Invalid checksum entry at line {line_number}",
            )
        checksums[name] = digest

    missing = sorted(set(REQUIRED_CHECKSUMS) - checksums.keys())
    if missing:
        _fail(
            "KNOWLEDGE_PACKAGE_INCOMPLETE",
            f"Missing checksum entries: {', '.join(missing)}",
        )

    for name, expected in checksums.items():
        target = package.joinpath(*PurePosixPath(name).parts)
        if not target.is_file():
            _fail("KNOWLEDGE_PACKAGE_INCOMPLETE", f"Missing package file: {name}")
        try:
            actual = hashlib.sha256(target.read_bytes()).hexdigest()
        except OSError as error:
            _fail("KNOWLEDGE_INTEGRITY_FAILURE", f"Cannot hash {name}: {error}")
        if actual != expected:
            _fail("KNOWLEDGE_INTEGRITY_FAILURE", f"Checksum mismatch for {name}")
    return checksums


def _certification(path: Path) -> tuple[dict[str, Any], bytes]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        _fail("KNOWLEDGE_CERTIFICATION_REQUIRED", f"Cannot read certification: {error}")
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        try:
            parsed = yaml.safe_load(raw)
        except yaml.YAMLError as error:
            _fail("KNOWLEDGE_CERTIFICATION_REQUIRED", f"Invalid certification: {error}")
    root = _mapping(
        parsed,
        code="KNOWLEDGE_CERTIFICATION_REQUIRED",
        name="runtime-certification.yaml",
    )
    return (
        _mapping(
            root.get("certification"),
            code="KNOWLEDGE_CERTIFICATION_REQUIRED",
            name="certification",
        ),
        raw,
    )


def validate_package(package: Path) -> ValidatedPackage:
    manifest = _yaml_mapping(
        package / "manifest.yaml",
        code="KNOWLEDGE_MANIFEST_INVALID",
    )
    if manifest.get("manifest_schema") != "1.0":
        _fail("KNOWLEDGE_MANIFEST_INVALID", "manifest_schema must be 1.0")
    if (
        manifest.get("product") != "CIP"
        or manifest.get("runtime_sdk_version") != ">=1.0.0 <2.0"
    ):
        _fail(
            "KNOWLEDGE_RUNTIME_INCOMPATIBLE",
            "Package product or runtime SDK range is incompatible",
        )
    if (
        _major(manifest.get("schema_version"), name="schema_version") != 1
        or _major(
            manifest.get("relation_schema_version"),
            name="relation_schema_version",
        )
        != 1
    ):
        _fail("KNOWLEDGE_SCHEMA_UNSUPPORTED", "Package schema major must be 1")

    required_files = ("objects.jsonl", *CAPABILITY_FILES)
    missing_files = [name for name in required_files if not (package / name).is_file()]
    if missing_files:
        _fail(
            "KNOWLEDGE_PACKAGE_INCOMPLETE",
            f"Missing package files: {', '.join(missing_files)}",
        )
    _checksums(package)

    capability_code = "KNOWLEDGE_CAPABILITY_INDEX_INVALID"
    catalog = _yaml_mapping(
        package / CAPABILITY_FILES[0],
        code=capability_code,
    )
    index = _yaml_mapping(
        package / CAPABILITY_FILES[1],
        code=capability_code,
    )
    relations = _yaml_mapping(
        package / CAPABILITY_FILES[2],
        code=capability_code,
    )
    if not isinstance(catalog.get("capabilities"), list):
        _fail(capability_code, "capability-catalog.yaml has no capabilities list")
    index_root = _mapping(
        index.get("capability_index"),
        code=capability_code,
        name="capability_index",
    )
    token_map = _mapping(
        index_root.get("token_to_capability"),
        code=capability_code,
        name="token_to_capability",
    )
    relation_map = _mapping(
        relations.get("capability_relations"),
        code=capability_code,
        name="capability_relations",
    )
    if not token_map or not all(
        isinstance(token, str)
        and token
        and isinstance(capability, str)
        and capability
        for token, capability in token_map.items()
    ):
        _fail(capability_code, "token_to_capability has invalid entries")
    if not all(isinstance(name, str) and name for name in relation_map):
        _fail(capability_code, "capability_relations has invalid names")
    relation_names = {name.casefold() for name in relation_map}
    missing_relations = sorted(
        {
            capability
            for capability in token_map.values()
            if capability.casefold() not in relation_names
        }
    )
    if missing_relations:
        _fail(
            capability_code,
            "Capabilities have no relation entry: "
            + ", ".join(missing_relations),
        )
    for capability, relation in relation_map.items():
        value = _mapping(
            relation,
            code=capability_code,
            name=f"capability_relations.{capability}",
        )
        contains = value.get("contains")
        if not isinstance(contains, list) or not all(
            isinstance(object_id, str) and object_id
            for object_id in contains
        ):
            _fail(
                capability_code,
                f"Capability {capability} has an invalid contains list",
            )

    certification, certification_bytes = _certification(
        package / "runtime-certification.yaml"
    )
    if certification.get("status") != "CERTIFIED":
        _fail("KNOWLEDGE_CERTIFICATION_REQUIRED", "Package is not CERTIFIED")
    certified_package = _mapping(
        certification.get("package"),
        code="KNOWLEDGE_CERTIFICATION_REQUIRED",
        name="certification.package",
    )
    integrity = _mapping(
        manifest.get("integrity"),
        code="KNOWLEDGE_MANIFEST_INVALID",
        name="manifest.integrity",
    )
    package_checksum = integrity.get("package_checksum")
    if (
        not isinstance(package_checksum, str)
        or certified_package.get("manifest_hash") != package_checksum
    ):
        _fail(
            "KNOWLEDGE_INTEGRITY_FAILURE",
            "Certification manifest hash does not match the package checksum",
        )
    expected_count = manifest.get("total_objects")
    if (
        not isinstance(expected_count, int)
        or certification.get("total_objects") != expected_count
    ):
        _fail(
            "KNOWLEDGE_INTEGRITY_FAILURE",
            "Certification object count does not match the manifest",
        )

    seen: set[str] = set()
    try:
        with (package / "objects.jsonl").open(encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, start=1):
                row = json.loads(line)
                object_id = row.get("id") if isinstance(row, dict) else None
                if not isinstance(object_id, str) or not object_id.strip():
                    _fail(
                        "KNOWLEDGE_INTEGRITY_FAILURE",
                        f"Object at line {line_number} has no canonical ID",
                    )
                if object_id in seen:
                    _fail(
                        "KNOWLEDGE_INTEGRITY_FAILURE",
                        f"Duplicate canonical ID: {object_id}",
                    )
                seen.add(object_id)
    except PackageEligibilityError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        _fail("KNOWLEDGE_INTEGRITY_FAILURE", f"Invalid objects.jsonl: {error}")
    if len(seen) != expected_count:
        _fail(
            "KNOWLEDGE_INTEGRITY_FAILURE",
            f"Expected {expected_count} objects, found {len(seen)}",
        )

    package_key = certified_package.get("key")
    knowledge_version = manifest.get("knowledge_version")
    schema_version = manifest.get("schema_version")
    if not all(
        isinstance(value, str) and value
        for value in (package_key, knowledge_version, schema_version)
    ):
        _fail("KNOWLEDGE_MANIFEST_INVALID", "Package identity is incomplete")
    ref = PackageRef(
        package_key=package_key,
        knowledge_version=knowledge_version,
        schema_version=schema_version,
        package_checksum=package_checksum,
        certification_status="CERTIFIED",
        certification_digest=(
            "sha256:" + hashlib.sha256(certification_bytes).hexdigest()
        ),
    )
    return ValidatedPackage(path=package, ref=ref, manifest=manifest)
