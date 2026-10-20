"""Markdown Provider — the default provider, returning canonical Knowledge Objects.

Phase 1.5: the provider no longer exposes Markdown internally. It delegates all
parsing to the :class:`MarkdownKnowledgeMapper` and returns canonical
:class:`KnowledgeObject` instances. Runtime behavior is unchanged; it is the
permanent fallback and the proof of the storage abstraction.

No embeddings, no vector search, no external dependency. ``search`` is a
deterministic metadata + text filter (HYBRID_RETRIEVAL.md stages 1-5 minus the
vector stage).
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable, Optional

from ..config import RuntimeConfig
from ..errors import ErrorCode, KnowledgeError
from ..mapper import MarkdownKnowledgeMapper
from ..models import (
    Capabilities,
    HealthCheck,
    HealthState,
    HealthStatus,
    KnowledgeContent,
    KnowledgeObject,
    KnowledgeObjectType,
    KnowledgeQuery,
    KnowledgeReference,
    ProviderStatistics,
    RelationEdge,
    Result,
    RetrievalInfo,
    RetrievalMode,
    RuntimeIdentity,
    SearchQuery,
)
from .base import KnowledgeProvider


class MarkdownProvider(KnowledgeProvider):
    name = "markdown"

    def __init__(self, config: RuntimeConfig):
        super().__init__(config)
        root = config.knowledge_root
        self.root: Optional[Path] = Path(root) if root else None
        self._mapper = MarkdownKnowledgeMapper()
        self._objects: list[KnowledgeObject] = []
        self._by_id: dict[str, KnowledgeObject] = {}

    # ── lifecycle ─────────────────────────────────────────────────────────

    def initialize(self) -> HealthStatus:
        self._objects = []
        self._by_id = {}
        if self.root and self.root.is_dir():
            for md in sorted(self.root.rglob("*.md")):
                try:
                    self._objects.extend(self._mapper.map_file(md, root=self.root))
                except OSError:
                    continue
            for obj in self._objects:
                self._by_id.setdefault(obj.id.lower(), obj)
                for alias in obj.aliases:
                    self._by_id.setdefault(alias.lower(), obj)
        self._initialized = True
        return self.health()

    # ── introspection ─────────────────────────────────────────────────────

    def capabilities(self) -> Capabilities:
        return Capabilities(exact=True, filter=True, relation=True, vector=False)

    def health(self) -> HealthStatus:
        checks: list[HealthCheck] = []
        if self.root is None:
            checks.append(HealthCheck("knowledge_root_configured", False, "no knowledge_root set"))
            return HealthStatus(HealthState.UNAVAILABLE, self.name, checks,
                                "knowledge_root not configured")
        exists = self.root.is_dir()
        checks.append(HealthCheck("knowledge_root_exists", exists, str(self.root)))
        if not exists:
            return HealthStatus(HealthState.UNAVAILABLE, self.name, checks,
                                f"knowledge_root not found: {self.root}")
        has_docs = self._initialized and len(self._objects) > 0
        checks.append(HealthCheck("knowledge_objects_loaded", has_docs,
                                  f"{len(self._objects)} objects"))
        state = HealthState.OK if has_docs else HealthState.DEGRADED
        reason = "" if has_docs else "no markdown objects discovered under knowledge_root"
        return HealthStatus(state, self.name, checks, reason)

    def version(self) -> RuntimeIdentity:
        return RuntimeIdentity(
            provider=self.name,
            product=self.config.product,
            knowledge_version="source",
            runtime_sdk_version=self.config.sdk_version,
            schema_version="source",
        )

    def statistics(self) -> ProviderStatistics:
        collections: dict[str, int] = {}
        relations = 0
        for obj in self._objects:
            collections[obj.type.value] = collections.get(obj.type.value, 0) + 1
            relations += len(obj.relations)
        return ProviderStatistics(
            provider=self.name,
            collections=collections,
            total_objects=len(self._objects),
            relations_count=relations,
            source="live",
        )

    # ── retrieval ──────────────────────────────────────────────────────────

    def get(self, ref: KnowledgeReference, include_body: bool = False) -> Result[KnowledgeObject]:
        candidates: Iterable[KnowledgeObject]
        if ref.document:
            wanted = ref.document.lower()
            candidates = [
                o for o in self._objects
                if o.source.source_document
                and (o.source.source_document.lower() == wanted
                     or o.source.source_document.lower().endswith("/" + wanted)
                     or Path(o.source.source_document).name.lower() == Path(wanted).name.lower())
            ]
        else:
            candidates = self._objects

        target = ref.id.strip().lower()
        for obj in candidates:
            if obj.id.lower() == target or target in (a.lower() for a in obj.aliases):
                return Result.success(
                    self._project(obj, include_body),
                    RetrievalInfo(self.name, RetrievalMode.EXACT),
                )
        return Result.failure(
            KnowledgeError(ErrorCode.NOT_FOUND, "reference not found", str(ref)),
            RetrievalInfo(self.name, RetrievalMode.EXACT),
        )

    def search(self, query: SearchQuery | KnowledgeQuery) -> Result[list[KnowledgeObject]]:
        if isinstance(query, SearchQuery):
            flt = query.filter
            text = query.seed_text
            limit = query.k
        else:
            flt = query
            text = query.text or ""
            limit = query.limit

        results = []
        want_types = {t.value for t in (flt.types or [])}
        drop_types = {t.value for t in (flt.exclude_types or [])}
        want_tags = {t.lower() for t in (flt.tags or [])}
        needle = text.strip().lower()

        for obj in self._objects:
            if want_types and obj.type.value not in want_types:
                continue
            if drop_types and obj.type.value in drop_types:
                continue
            if flt.ids and obj.id not in flt.ids:
                continue
            if want_tags and not want_tags.issubset({t.lower() for t in obj.tags}):
                continue
            if needle:
                hay = f"{obj.id} {obj.title} {obj.summary} {obj.content.body or ''}".lower()
                if needle not in hay:
                    continue
            results.append(self._project(obj, flt.include_body))

        results.sort(key=lambda o: o.id)
        results = results[: max(0, limit)]
        return Result.success(
            results,
            RetrievalInfo(self.name, RetrievalMode.FILTER, degraded=False),
        )

    def related(self, object_id: str, kinds: list[str] | None = None) -> Result[list[RelationEdge]]:
        obj = self._by_id.get(object_id.strip().lower())
        if obj is None:
            return Result.failure(
                KnowledgeError(ErrorCode.NOT_FOUND, "object not found", object_id),
                RetrievalInfo(self.name, RetrievalMode.RELATION),
            )
        edges = obj.relations
        if kinds:
            wanted = {k.lower() for k in kinds}
            edges = [e for e in edges if e.kind.lower() in wanted]
        return Result.success(
            list(edges),
            RetrievalInfo(self.name, RetrievalMode.RELATION, degraded=len(edges) == 0),
        )

    # ── projection ────────────────────────────────────────────────────────

    @staticmethod
    def _project(obj: KnowledgeObject, include_body: bool) -> KnowledgeObject:
        """Return the object, or a body-suppressed copy for the token-lean default."""
        if include_body:
            return obj
        slim_content = KnowledgeContent(
            body=None, format=obj.content.format, raw=None, sections=[],
        )
        return KnowledgeObject(
            id=obj.id, type=obj.type, title=obj.title, summary=obj.summary,
            metadata=dict(obj.metadata), relations=list(obj.relations),
            content=slim_content, version=obj.version, status=obj.status,
            source=obj.source,
        )
