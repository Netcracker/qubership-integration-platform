"""LanceDB Provider — serves the compiled Runtime Knowledge Package.

Loads and serves knowledge directly from a compiled Knowledge Package produced by
the build pipeline — no compiler, no embeddings, no rebuild at runtime. It reads:

  * the manifest (``manifest.yaml`` — or the legacy ``runtime-knowledge-manifest.yaml``),
  * the objects, from the packaged store ``lancedb/knowledge_objects.jsonl`` (rows
    carry the full canonical IR), or the top-level ``objects.jsonl`` (IR per line),
  * an optional opt-in IR stub (``options.ir_stub_dir``) for tests.

Retrieval (``get``/``search``/``related``) is served from the loaded objects. If
no store/stub is available the provider is inert (``ERR-KS-003``) and the SDK
falls back to Markdown. Metadata (``health``/``version``/``statistics``) comes
from the manifest. The native ``lancedb`` library is not required — the packaged
store's JSONL rows are the deterministic runtime database.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from typing import Any, Optional

from .. import ir
from ..config import RuntimeConfig
from ..errors import ErrorCode, KnowledgeError
from ..health import evaluate_runtime, load_manifest
from ..models import (
    Capabilities,
    EmbeddingModel,
    HealthStatus,
    KnowledgeObject,
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

MANIFEST_FILENAMES = ("manifest.yaml", "runtime-knowledge-manifest.yaml")
STORE_DIRNAME = "lancedb"
STORE_ROWS = "knowledge_objects.jsonl"
OBJECTS_JSONL = "objects.jsonl"


def _lancedb_installed() -> bool:
    try:
        return importlib.util.find_spec("lancedb") is not None
    except (ImportError, ValueError):  # pragma: no cover
        return False


class LanceDbProvider(KnowledgeProvider):
    name = "lancedb"

    def __init__(self, config: RuntimeConfig):
        super().__init__(config)
        base = config.runtime_knowledge_dir or config.options.get("runtime_knowledge_dir")
        self.base_dir: Optional[Path] = Path(base) if base else None
        self.manifest: Optional[dict[str, Any]] = None
        self.library_present: bool = _lancedb_installed()
        self._objects: list[KnowledgeObject] = []
        self._index: dict[str, KnowledgeObject] = {}
        self._source: Optional[str] = None   # "store" | "objects.jsonl" | "ir-stub"

    # ── resolved paths ────────────────────────────────────────────────────

    @property
    def manifest_path(self) -> Optional[Path]:
        if not self.base_dir:
            return None
        for name in MANIFEST_FILENAMES:
            p = self.base_dir / name
            if p.exists():
                return p
        return self.base_dir / MANIFEST_FILENAMES[0]

    @property
    def store_path(self) -> Optional[Path]:
        override = self.config.options.get("store_dir")
        if override:
            return Path(override)
        return self.base_dir / STORE_DIRNAME if self.base_dir else None

    @property
    def ir_stub_dir(self) -> Optional[Path]:
        d = self.config.options.get("ir_stub_dir")
        return Path(d) if d else None

    def _db_present(self) -> bool:
        return bool(self._objects) or bool(self.store_path and self.store_path.exists())

    # ── lifecycle ─────────────────────────────────────────────────────────

    def initialize(self) -> HealthStatus:
        """Load manifest metadata and the packaged objects (no rebuild)."""
        mp = self.manifest_path
        self.manifest = load_manifest(mp) if (mp and mp.exists()) else None
        self._load_objects()
        self._initialized = True
        return self.health()

    def _load_objects(self) -> None:
        objs: list[KnowledgeObject] = []
        source: Optional[str] = None

        # 1. packaged store rows (lancedb/knowledge_objects.jsonl) — rows carry IR
        store = self.store_path
        rows = (store / STORE_ROWS) if store else None
        if rows and rows.exists():
            objs = self._read_rows(rows)
            source = "store"
        # 2. top-level objects.jsonl (one IR object per line)
        elif self.base_dir and (self.base_dir / OBJECTS_JSONL).exists():
            objs = self._read_objects_jsonl(self.base_dir / OBJECTS_JSONL)
            source = "objects.jsonl"
        # 3. opt-in IR stub (tests / pre-store)
        elif self.ir_stub_dir and self.ir_stub_dir.is_dir():
            objs = self._read_ir_stub(self.ir_stub_dir)
            source = "ir-stub"

        self._objects = sorted(objs, key=lambda o: o.id)
        self._index = {}
        for o in self._objects:
            self._index.setdefault(o.id.lower(), o)
            for alias in o.aliases:
                self._index.setdefault(alias.lower(), o)
        self._source = source if self._objects else None

    @staticmethod
    def _read_rows(path: Path) -> list[KnowledgeObject]:
        out: list[KnowledgeObject] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            data = row.get("ir")
            if isinstance(data, str):
                data = json.loads(data)
            if isinstance(data, dict):
                out.append(ir.from_ir(data))
        return out

    @staticmethod
    def _read_objects_jsonl(path: Path) -> list[KnowledgeObject]:
        out: list[KnowledgeObject] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                try:
                    out.append(ir.from_ir(json.loads(line)))
                except json.JSONDecodeError:
                    continue
        return out

    @staticmethod
    def _read_ir_stub(d: Path) -> list[KnowledgeObject]:
        out: list[KnowledgeObject] = []
        for jf in sorted(d.glob("*.json")):
            try:
                data = json.loads(jf.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            out.extend(ir.load_collection(data) if "objects" in data else [ir.from_ir(data)])
        return out

    # ── introspection ─────────────────────────────────────────────────────

    def capabilities(self) -> Capabilities:
        return Capabilities(
            exact=True, filter=True, relation=True,
            vector=bool(self.manifest and self.manifest.get("embedding_model")),
        )

    def health(self) -> HealthStatus:
        return evaluate_runtime(
            provider=self.name, manifest=self.manifest, config=self.config,
            db_present=self._db_present(), library_present=self.library_present,
        )

    def version(self) -> RuntimeIdentity:
        m = self.manifest or {}
        embed = m.get("embedding_model") or None
        embedding = None
        if isinstance(embed, dict):
            embedding = EmbeddingModel(
                id=embed.get("id"),
                version=str(embed.get("version")) if embed.get("version") is not None else None,
                dim=embed.get("dim"), normalize=embed.get("normalize"))
        return RuntimeIdentity(
            provider=self.name, product=m.get("product"),
            knowledge_version=m.get("knowledge_version"),
            compiler_version=m.get("compiler_version"),
            runtime_sdk_version=str(m.get("runtime_sdk_version")) if m.get("runtime_sdk_version") else self.config.sdk_version,
            schema_version=str(m.get("schema_version")) if m.get("schema_version") else None,
            relation_schema_version=str(m.get("relation_schema_version")) if m.get("relation_schema_version") else None,
            embedding_model=embedding, build_timestamp=m.get("build_timestamp"),
            manifest_schema=str(m.get("manifest_schema")) if m.get("manifest_schema") else None,
        )

    def statistics(self) -> ProviderStatistics:
        m = self.manifest or {}
        if self._objects:   # counts from the loaded database
            collections: dict[str, int] = {}
            for o in self._objects:
                collections[o.type.value] = collections.get(o.type.value, 0) + 1
            return ProviderStatistics(
                provider=self.name, collections=dict(sorted(collections.items())),
                total_objects=len(self._objects),
                relations_count=sum(len(o.relations) for o in self._objects),
                db_size_bytes=int(m.get("db_size_bytes") or 0),
                source=(self._source or "database"),
                extras={"db_present": True, "library_present": self.library_present,
                        "loaded_from": self._source},
            )
        # metadata-only (no objects loaded)
        collections = {str(k): int(v) for k, v in (m.get("collections") or {}).items()}
        return ProviderStatistics(
            provider=self.name, collections=collections,
            total_objects=int(m.get("total_objects") or sum(collections.values())),
            relations_count=int(m.get("relations_count") or 0),
            db_size_bytes=int(m.get("db_size_bytes") or 0), source="manifest",
            extras={"db_present": self._db_present(), "library_present": self.library_present,
                    "loaded_from": None},
        )

    # ── retrieval ─────────────────────────────────────────────────────────

    def get(self, ref: KnowledgeReference, include_body: bool = False) -> Result[KnowledgeObject]:
        if not self._objects:
            return self._not_ready(RetrievalMode.EXACT)
        obj = self._index.get(ref.id.strip().lower())
        if obj is None:
            return Result.failure(
                KnowledgeError(ErrorCode.NOT_FOUND, "reference not found", str(ref)),
                RetrievalInfo(self.name, RetrievalMode.EXACT))
        return Result.success(obj, RetrievalInfo(self.name, RetrievalMode.EXACT))

    def search(self, query: SearchQuery | KnowledgeQuery) -> Result[list[KnowledgeObject]]:
        if not self._objects:
            return self._not_ready(RetrievalMode.FILTER)
        flt = query.filter if isinstance(query, SearchQuery) else query
        text = (query.seed_text if isinstance(query, SearchQuery) else (query.text or "")).strip().lower()
        limit = query.k if isinstance(query, SearchQuery) else query.limit
        want_types = {t.value for t in (flt.types or [])}
        results = []
        for obj in self._objects:
            if want_types and obj.type.value not in want_types:
                continue
            if text and text not in f"{obj.id} {obj.title} {obj.summary}".lower():
                continue
            results.append(obj)
        return Result.success(results[: max(0, limit)],
                              RetrievalInfo(self.name, RetrievalMode.FILTER))

    def related(self, object_id: str, kinds: list[str] | None = None) -> Result[list[RelationEdge]]:
        if not self._objects:
            return self._not_ready(RetrievalMode.RELATION)
        obj = self._index.get(object_id.strip().lower())
        if obj is None:
            return Result.failure(
                KnowledgeError(ErrorCode.NOT_FOUND, "object not found", object_id),
                RetrievalInfo(self.name, RetrievalMode.RELATION))
        edges = obj.relations
        if kinds:
            wanted = {k.lower() for k in kinds}
            edges = [e for e in edges if e.kind.lower() in wanted]
        return Result.success(list(edges),
                              RetrievalInfo(self.name, RetrievalMode.RELATION, degraded=len(edges) == 0))

    def _not_ready(self, mode: RetrievalMode) -> Result[Any]:
        return Result.failure(
            KnowledgeError(ErrorCode.PROVIDER_UNAVAILABLE,
                           "LanceDB retrieval not available (no compiled store or IR stub)",
                           "SDK should fall back to markdown"),
            RetrievalInfo(self.name, mode, degraded=True))
