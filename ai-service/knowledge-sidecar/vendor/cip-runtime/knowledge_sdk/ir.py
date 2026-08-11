"""Knowledge IR — the canonical JSON Intermediate Representation.

Knowledge Objects serialize to a stable, storage-neutral JSON document. This IR
is the contract exchanged between Markdown, the Knowledge Runtime, the (future)
Knowledge Compiler, the LanceDB Builder, and an Enterprise Knowledge Graph — see
``../KNOWLEDGE_IR.md``. Consumers exchange knowledge as IR, never as Markdown.

The mapping is lossless and round-trips: ``from_ir(to_ir(obj)) == obj``.
"""

from __future__ import annotations

import json
from typing import Any

from .models import (
    KnowledgeContent,
    KnowledgeObject,
    KnowledgeObjectType,
    KnowledgeSource,
    ObjectStatus,
    RelationEdge,
)

#: version of the IR shape itself (independent of any object's content version)
IR_VERSION = "1.0"

#: keys that would betray a storage-specific representation and must never appear
STORAGE_BANNED_KEYS = {
    "vector", "vectors", "embedding", "embeddings", "_lance", "lance",
    "table", "row_id", "rowid", "arrow", "parquet", "connection",
}

REQUIRED_KEYS = {"ir_version", "id", "type", "title", "summary", "metadata",
                 "relations", "content", "version", "status", "source"}


# ── serialization ────────────────────────────────────────────────────────────


def to_ir(obj: KnowledgeObject) -> dict[str, Any]:
    """Serialize a Knowledge Object into the canonical IR dict (ordered)."""
    return {
        "ir_version": IR_VERSION,
        "id": obj.id,
        "type": obj.type.value,
        "title": obj.title,
        "summary": obj.summary,
        "metadata": _plain(obj.metadata),
        "relations": [
            {
                "from": e.from_id,
                "kind": e.kind,
                "to": e.to_id,
                "attributes": _plain(e.attributes),
            }
            for e in obj.relations
        ],
        "content": {
            "format": obj.content.format,
            "body": obj.content.body,
            "raw": obj.content.raw,
            "sections": list(obj.content.sections),
        },
        "version": obj.version,
        "status": obj.status.value,
        "source": {
            "format": obj.source.format,
            "document": obj.source.source_document,
            "section_id": obj.source.source_id,
            "hash": obj.source.source_hash,
            "knowledge_version": obj.source.knowledge_version,
        },
    }


def from_ir(data: dict[str, Any]) -> KnowledgeObject:
    """Reconstruct a Knowledge Object from its canonical IR dict."""
    content = data.get("content") or {}
    source = data.get("source") or {}
    return KnowledgeObject(
        id=data["id"],
        type=KnowledgeObjectType.coerce(data.get("type")),
        title=data.get("title", ""),
        summary=data.get("summary", ""),
        metadata=dict(data.get("metadata") or {}),
        relations=[
            RelationEdge(
                from_id=e.get("from", data["id"]),
                kind=e["kind"],
                to_id=e["to"],
                attributes=dict(e.get("attributes") or {}),
            )
            for e in (data.get("relations") or [])
        ],
        content=KnowledgeContent(
            body=content.get("body"),
            format=content.get("format", "markdown"),
            raw=content.get("raw"),
            sections=list(content.get("sections") or []),
        ),
        version=data.get("version", "source"),
        status=ObjectStatus.coerce(data.get("status")),
        source=KnowledgeSource(
            source_document=source.get("document"),
            source_id=source.get("section_id"),
            source_hash=source.get("hash"),
            knowledge_version=source.get("knowledge_version"),
            format=source.get("format", "markdown"),
        ),
    )


def dumps(obj: KnowledgeObject, *, indent: int | None = 2) -> str:
    return json.dumps(to_ir(obj), indent=indent, ensure_ascii=False)


def loads(text: str) -> KnowledgeObject:
    return from_ir(json.loads(text))


def dump_collection(objects: list[KnowledgeObject]) -> dict[str, Any]:
    """Serialize a set of objects into a single IR collection document."""
    return {
        "ir_version": IR_VERSION,
        "objects": [to_ir(o) for o in objects],
    }


def load_collection(data: dict[str, Any]) -> list[KnowledgeObject]:
    return [from_ir(o) for o in (data.get("objects") or [])]


# ── validation ───────────────────────────────────────────────────────────────


def validate_ir(data: dict[str, Any]) -> list[str]:
    """Return a list of contract violations ([] == valid).

    Enforces required keys, basic types, and **storage neutrality** — the IR must
    never carry a backend-specific key (vectors, tables, arrow, ...).
    """
    problems: list[str] = []

    missing = REQUIRED_KEYS - set(data.keys())
    if missing:
        problems.append(f"missing required keys: {sorted(missing)}")

    if "type" in data and KnowledgeObjectType.coerce(data["type"]).value != data["type"]:
        # coerce falls back to Unknown; flag an unrecognized type explicitly
        if data["type"] != KnowledgeObjectType.UNKNOWN.value:
            problems.append(f"unrecognized type: {data['type']!r}")

    if not isinstance(data.get("metadata", {}), dict):
        problems.append("metadata must be an object")
    if not isinstance(data.get("relations", []), list):
        problems.append("relations must be an array")
    if not isinstance(data.get("content", {}), dict):
        problems.append("content must be an object")

    banned = _find_banned_keys(data)
    if banned:
        problems.append(f"storage-specific keys present (not storage-neutral): {sorted(banned)}")

    return problems


def is_storage_neutral(data: dict[str, Any]) -> bool:
    return not _find_banned_keys(data)


def _find_banned_keys(node: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        for key, value in node.items():
            if str(key).lower() in STORAGE_BANNED_KEYS:
                found.add(str(key))
            found |= _find_banned_keys(value)
    elif isinstance(node, list):
        for item in node:
            found |= _find_banned_keys(item)
    return found


def _plain(value: Any) -> Any:
    """Deep-copy plain JSON-able structures (defensive against shared refs)."""
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_plain(v) for v in value]
    return value
