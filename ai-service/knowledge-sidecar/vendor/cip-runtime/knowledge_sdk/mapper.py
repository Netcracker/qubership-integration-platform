"""Knowledge Object Mapper — deterministic Markdown -> Knowledge Objects.

The single place that understands Markdown. It converts a Markdown document into
canonical :class:`KnowledgeObject` instances, **preserving all engineering
knowledge** (front matter, headings, anchors, ordinals, relation markers, and the
exact raw fragment) so the mapping is lossless. It is completely independent of
LanceDB and of any storage backend.

Determinism: identical Markdown -> identical objects (same ids, same order, same
content hash).
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any, Optional

from . import _yaml
from .models import (
    KnowledgeContent,
    KnowledgeObject,
    KnowledgeObjectType,
    KnowledgeSource,
    ObjectStatus,
    RelationEdge,
)

_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
_ANCHOR = re.compile(r"\{#([A-Za-z0-9_\-:.]+)\}")
_ID_MARKER = re.compile(r"<!--\s*id:\s*([A-Za-z0-9_\-:.]+)\s*-->")
_TYPE_MARKER = re.compile(r"<!--\s*type:\s*([A-Za-z0-9_\-]+)\s*-->")
_STATUS_MARKER = re.compile(r"<!--\s*status:\s*([A-Za-z0-9_\-]+)\s*-->")
_REL_MARKER = re.compile(r"<!--\s*rel:\s*([A-Za-z0-9_]+)\s*->\s*([A-Za-z0-9_\-:.]+)\s*-->")

# directory-name -> default object type (a light heuristic; front matter wins).
# Kept in step with knowledge_build/normalization/types.py::_DIR_TYPE — a
# disagreement between the two would make an object's type depend on which of the
# mapper or the inference stage saw it first.
_DIR_TYPE = {
    "corporate": KnowledgeObjectType.STANDARD,
    "patterns": KnowledgeObjectType.PATTERN,
    "services": KnowledgeObjectType.INTEGRATION_COMPONENT,
    "chains": KnowledgeObjectType.EXAMPLE,
    "examples": KnowledgeObjectType.EXAMPLE,
    # §3.2 specialized families
    "language": KnowledgeObjectType.LANGUAGE_ELEMENT,
    "elements": KnowledgeObjectType.LANGUAGE_ELEMENT,
    "grammar": KnowledgeObjectType.GRAMMAR_CONSTRAINT,
}

SOURCE_VERSION = "source"


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-")


def content_hash(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


class MarkdownKnowledgeMapper:
    """Maps Markdown text to canonical Knowledge Objects."""

    def map_file(self, path: str | Path, root: Optional[str | Path] = None) -> list[KnowledgeObject]:
        p = Path(path)
        text = p.read_text(encoding="utf-8", errors="replace")
        if root is not None:
            try:
                rel = p.relative_to(root).as_posix()  # OS-independent canonical path
            except ValueError:
                rel = p.name
        else:
            rel = p.name
        dir_parts = p.parts
        return self.map_document(text, source_document=rel, path_parts=dir_parts)

    def map_document(
        self,
        text: str,
        source_document: str,
        path_parts: tuple[str, ...] = (),
    ) -> list[KnowledgeObject]:
        front, body_text = self._split_front_matter(text)

        file_type = KnowledgeObjectType.coerce(front.get("type")) if front.get("type") else None
        if file_type is None:
            for part in path_parts:
                if part in _DIR_TYPE:
                    file_type = _DIR_TYPE[part]
                    break
        file_type = file_type or KnowledgeObjectType.UNKNOWN
        file_tags = [str(t) for t in (front.get("tags") or [])]
        file_status = ObjectStatus.coerce(front.get("status"))
        file_version = str(front.get("version")) if front.get("version") is not None else SOURCE_VERSION

        objects: list[KnowledgeObject] = []
        for ordinal, (level, title, raw_body) in enumerate(self._split_sections(body_text), start=1):
            anchor = _ANCHOR.search(title)
            clean_title = _ANCHOR.sub("", title).strip()
            explicit_id = None
            m_id = _ID_MARKER.search(raw_body)
            if anchor:
                explicit_id = anchor.group(1)
            elif m_id:
                explicit_id = m_id.group(1)

            primary_id = explicit_id or slug(clean_title) or f"section-{ordinal}"

            aliases: list[str] = []
            slugged = slug(clean_title)
            if explicit_id and slugged and slugged != primary_id:
                aliases.append(slugged)
            aliases.append(f"section-{ordinal}")

            sec_type = file_type
            m_type = _TYPE_MARKER.search(raw_body)
            if m_type:
                sec_type = KnowledgeObjectType.coerce(m_type.group(1))

            sec_status = file_status
            m_status = _STATUS_MARKER.search(raw_body)
            if m_status:
                sec_status = ObjectStatus.coerce(m_status.group(1))

            relations = [
                RelationEdge(from_id=primary_id, kind=k, to_id=t)
                for (k, t) in _REL_MARKER.findall(raw_body)
            ]

            summary = self._first_paragraph(raw_body) or clean_title
            normalized_body = raw_body.strip()

            metadata: dict[str, Any] = {
                "tags": list(file_tags),
                "aliases": aliases,
                "ordinal": ordinal,
                "heading_level": level,
                "anchor": explicit_id,
                "source_format": "markdown",
            }
            if front:
                # preserve any extra front-matter keys (no information lost)
                extra = {k: v for k, v in front.items() if k not in ("type", "tags", "status", "version")}
                if extra:
                    metadata["front_matter"] = extra

            objects.append(KnowledgeObject(
                id=primary_id,
                type=sec_type,
                title=clean_title or primary_id,
                summary=summary,
                metadata=metadata,
                relations=relations,
                content=KnowledgeContent(
                    body=normalized_body,
                    format="markdown",
                    raw=raw_body,
                    sections=[],
                ),
                version=file_version,
                status=sec_status,
                source=KnowledgeSource(
                    source_document=source_document,
                    source_id=primary_id,
                    source_hash=content_hash(raw_body),
                    knowledge_version=SOURCE_VERSION,
                    format="markdown",
                ),
            ))
        return objects

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _split_front_matter(text: str) -> tuple[dict, str]:
        if text.startswith("---"):
            end = text.find("\n---", 3)
            if end != -1:
                block = text[3:end].strip("\n")
                rest = text[end + 4:]
                data = _yaml.loads(block)
                if isinstance(data, dict):
                    return data, rest.lstrip("\n")
        return {}, text

    @staticmethod
    def _split_sections(text: str) -> list[tuple[int, str, str]]:
        """Return (heading_level, title, raw_body) per section, in document order."""
        lines = text.splitlines()
        sections: list[tuple[int, str, list[str]]] = []
        preamble: list[str] = []
        current: Optional[tuple[int, str, list[str]]] = None
        for line in lines:
            m = _HEADING.match(line)
            if m:
                if current:
                    sections.append(current)
                current = (len(m.group(1)), m.group(2), [])
            elif current:
                current[2].append(line)
            else:
                preamble.append(line)
        if current:
            sections.append(current)
        result = [(lvl, title, "\n".join(body)) for lvl, title, body in sections]
        if not result and preamble:
            result = [(0, "", "\n".join(preamble))]
        return result

    @staticmethod
    def _first_paragraph(body: str) -> str:
        for block in re.split(r"\n\s*\n", body.strip()):
            stripped = block.strip()
            if stripped and not stripped.startswith(("<!--", "```", "#")):
                return " ".join(stripped.splitlines()).strip()
        return ""
