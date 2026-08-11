import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TypeVar

import yaml
from knowledge_sdk import (
    ErrorCode,
    KnowledgeObjectType,
    KnowledgeQuery,
    KnowledgeReference,
    KnowledgeSDK,
    Result,
    to_ir,
)


T = TypeVar("T")


@dataclass(frozen=True)
class RuntimeContext:
    capabilities: list[str]
    objects: list[dict[str, Any]]
    content_chars: int


class RuntimeErrorResponse(Exception):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        status: int,
        retryable: bool,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.retryable = retryable


SDK_ERROR_MAP: dict[ErrorCode, tuple[str, int, bool]] = {
    ErrorCode.NOT_FOUND: ("KNOWLEDGE_NOT_FOUND", 404, False),
    ErrorCode.PROVIDER_UNAVAILABLE: (
        "KNOWLEDGE_TEMPORARILY_UNAVAILABLE",
        503,
        True,
    ),
    ErrorCode.VECTOR_UNAVAILABLE: ("KNOWLEDGE_INVALID_REQUEST", 400, False),
    ErrorCode.SCHEMA_UNSUPPORTED: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.VERSION_MISMATCH: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.MANIFEST_INVALID: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.PRODUCT_MISMATCH: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.INTEGRITY: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.CONFIG_INVALID: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
    ErrorCode.UNKNOWN_PROVIDER: (
        "KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
        503,
        False,
    ),
}


def unwrap(result: Result[T]) -> T:
    if result.error is not None:
        code, status, retryable = SDK_ERROR_MAP[result.error.code]
        raise RuntimeErrorResponse(
            code=code,
            message=result.error.message,
            status=status,
            retryable=retryable,
        )
    if result.value is None:
        raise RuntimeErrorResponse(
            code="KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
            message="Knowledge SDK returned an empty successful result",
            status=503,
            retryable=False,
        )
    return result.value


TOKEN_PATTERN = re.compile(r"[a-z0-9]+")


class RuntimeAdapter:
    def __init__(self, sdk: KnowledgeSDK, package_path: Path) -> None:
        self._sdk = sdk
        index = yaml.safe_load(
            (package_path / "capabilities/capability-index.yaml").read_text(
                encoding="utf-8"
            )
        )
        relations = yaml.safe_load(
            (package_path / "capabilities/capability-relations.yaml").read_text(
                encoding="utf-8"
            )
        )
        self._token_to_capability: dict[str, str] = index[
            "capability_index"
        ]["token_to_capability"]
        self._relations_by_capability: dict[str, dict[str, Any]] = {
            name.casefold(): value
            for name, value in relations["capability_relations"].items()
        }

    def exact(self, reference: str) -> dict[str, Any]:
        value = unwrap(
            self._sdk.get(
                KnowledgeReference(id=reference),
                include_body=True,
            )
        )
        return to_ir(value)

    def filter(
        self,
        *,
        type_name: str | None,
        limit: int,
    ) -> list[dict[str, Any]]:
        types = (
            [KnowledgeObjectType.coerce(type_name)]
            if type_name is not None
            else None
        )
        values = unwrap(
            self._sdk.search(
                KnowledgeQuery(
                    types=types,
                    limit=limit,
                    include_body=True,
                )
            )
        )
        return [to_ir(value) for value in values]

    def relations(
        self,
        reference: str,
        kinds: list[str],
    ) -> list[dict[str, Any]]:
        values = unwrap(self._sdk.related(reference, kinds or None))
        return [
            {
                "from": value.from_id,
                "kind": value.kind,
                "to": value.to_id,
                "attributes": dict(value.attributes),
            }
            for value in values
        ]

    def context(
        self,
        *,
        request_text: str,
        capability_id: str,
        phase: str,
        element_types: list[str],
        max_objects: int,
        max_chars: int,
    ) -> RuntimeContext:
        text = " ".join(
            [request_text, capability_id, phase, *element_types]
        ).lower()
        tokens = set(TOKEN_PATTERN.findall(text))
        if phase.upper() == "GENERATOR":
            tokens.update(("generator", "mapping", "rule"))
        elif phase.upper() == "VALIDATOR":
            tokens.update(("validation", "rule"))

        capabilities = sorted(
            {
                self._token_to_capability[token]
                for token in tokens
                if token in self._token_to_capability
            }
        )
        scores: dict[str, int] = {}
        for capability in capabilities:
            relation = self._relations_by_capability.get(capability.casefold())
            if relation is None:
                raise RuntimeErrorResponse(
                    code="KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
                    message=(
                        "Capability index references a capability without "
                        f"relations: {capability}"
                    ),
                    status=503,
                    retryable=False,
                )
            for object_id in relation.get("contains", []):
                scores[object_id] = scores.get(object_id, 0) + 1

        ranked_ids = sorted(scores, key=lambda value: (-scores[value], value))
        selected: list[dict[str, Any]] = []
        content_chars = 0
        example_count = 0
        for object_id in ranked_ids:
            try:
                value = self.exact(object_id)
            except RuntimeErrorResponse as error:
                if error.code != "KNOWLEDGE_NOT_FOUND":
                    raise
                raise RuntimeErrorResponse(
                    code="KNOWLEDGE_RUNTIME_CONTRACT_FAILURE",
                    message=(
                        "Capability relations reference a missing canonical "
                        f"object: {object_id}"
                    ),
                    status=503,
                    retryable=False,
                ) from error

            if value["type"] == "Example" and example_count == 2:
                continue
            body = value.get("content", {}).get("body")
            body_chars = len(body) if isinstance(body, str) else 0
            if len(selected) == max_objects or content_chars + body_chars > max_chars:
                break
            selected.append(value)
            content_chars += body_chars
            if value["type"] == "Example":
                example_count += 1

        return RuntimeContext(
            capabilities=capabilities,
            objects=selected,
            content_chars=content_chars,
        )
