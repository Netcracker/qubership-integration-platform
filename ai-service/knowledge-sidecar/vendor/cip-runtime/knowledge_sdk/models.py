"""Canonical Runtime data model for the Knowledge SDK.

Phase 1.5 promotes ``KnowledgeObject`` to the **canonical Runtime object model**
(``id/type/title/summary/metadata/relations/content/version/status/source``).
The Runtime operates on Knowledge Objects, never on Markdown files.

Backward compatibility: read-only accessors (``name``, ``body``, ``provenance``,
``tags``, ``aliases``, ``attributes``) keep the Phase 1 surface working so the
SDK, providers, config, health, statistics, and versioning integrate
transparently and unmodified.

Nothing here names LanceDB, tables, vectors, or file layouts.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Generic, Optional, TypeVar

from .errors import KnowledgeError

T = TypeVar("T")


class KnowledgeObjectType(str, Enum):
    """Engineering families (OBJECT_MODEL_OVERVIEW.md §3.1 and §3.2).

    §3.1 defines the canonical families. §3.2 defines the specialized families,
    which are equally first-class — "These remain first-class object types" —
    but are usually reached *through* the canonical ones and their relations.

    Both groups are implemented. Omitting §3.2 left the entire CIP ``language/``
    domain with no type to be assigned, which accounted for 96% of all Unknown
    objects.
    """

    # ── §3.1 canonical families ──────────────────────────────────────────────
    PATTERN = "Pattern"
    STANDARD = "Standard"
    EXAMPLE = "Example"
    GENERATOR_HINT = "GeneratorHint"
    VALIDATOR_RULE = "ValidatorRule"
    ARCHITECTURE_DECISION = "ArchitectureDecision"
    SKILL = "Skill"
    CAPABILITY = "Capability"
    LIFECYCLE = "Lifecycle"
    INTEGRATION_COMPONENT = "IntegrationComponent"
    API_DEFINITION = "ApiDefinition"
    ENGINEERING_DECISION = "EngineeringDecision"

    # ── §3.2 specialized families ────────────────────────────────────────────
    RULE = "Rule"                             # machine-checkable form of Standard
    DECISION_NODE = "DecisionNode"            # a decision-tree branch
    GRAMMAR_CONSTRAINT = "GrammarConstraint"  # a ValidatorRule over structure
    LANGUAGE_ELEMENT = "LanguageElement"      # a CIP language element definition
    ANTI_PATTERN = "AntiPattern"              # inverse of Pattern
    NAMING_RULE = "NamingRule"                # a Standard specialization

    #: Terminal sentinel — never a valid package type. An object still carrying
    #: it fails the build (ERR-KC-001). It exists so inference can report "no
    #: evidence" instead of guessing.
    UNKNOWN = "Unknown"

    @classmethod
    def coerce(cls, value: Any) -> "KnowledgeObjectType":
        if isinstance(value, cls):
            return value
        if value is None:
            return cls.UNKNOWN
        for member in cls:
            if member.value.lower() == str(value).strip().lower():
                return member
        return cls.UNKNOWN


class ObjectStatus(str, Enum):
    ACTIVE = "active"
    DEPRECATED = "deprecated"
    SUPERSEDED = "superseded"
    RETIRED = "retired"
    DRAFT = "draft"

    @classmethod
    def coerce(cls, value: Any) -> "ObjectStatus":
        if isinstance(value, cls):
            return value
        if value is None:
            return cls.ACTIVE
        for member in cls:
            if member.value.lower() == str(value).strip().lower():
                return member
        return cls.ACTIVE


class RetrievalMode(str, Enum):
    EXACT = "exact"
    FILTER = "filter"
    RELATION = "relation"
    VECTOR = "vector"


class HealthState(str, Enum):
    OK = "OK"
    DEGRADED = "DEGRADED"
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class KnowledgeReference:
    """A ``document :: id`` reference or a bare Knowledge ID (KID)."""

    id: str
    document: Optional[str] = None

    @classmethod
    def parse(cls, raw: str) -> "KnowledgeReference":
        text = raw.strip()
        for sep in ("::", "#", "§"):
            if sep in text:
                doc, _, ident = text.partition(sep)
                return cls(id=ident.strip(), document=doc.strip() or None)
        return cls(id=text)

    def __str__(self) -> str:
        return f"{self.document} :: {self.id}" if self.document else self.id


@dataclass
class KnowledgeSource:
    """Canonical ``source`` — the pointer back to the Markdown Source of Truth."""

    source_document: Optional[str] = None
    source_id: Optional[str] = None
    source_hash: Optional[str] = None
    knowledge_version: Optional[str] = None
    format: str = "markdown"


# backward-compatible alias for the Phase 1 name
Provenance = KnowledgeSource


@dataclass
class KnowledgeContent:
    """Canonical ``content`` — structured, storage-neutral body.

    ``raw`` preserves the exact source fragment so the mapping is **lossless**
    (round-trips through the IR without dropping engineering knowledge).
    """

    body: Optional[str] = None
    format: str = "markdown"
    raw: Optional[str] = None
    sections: list[dict] = field(default_factory=list)


@dataclass
class RelationEdge:
    """A storage-independent typed edge (RELATION_MODEL.md §2)."""

    from_id: str
    kind: str
    to_id: str
    attributes: dict[str, Any] = field(default_factory=dict)


@dataclass
class KnowledgeObject:
    """The canonical Runtime unit. Common structure for every object family."""

    id: str
    type: KnowledgeObjectType
    title: str
    summary: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)
    relations: list[RelationEdge] = field(default_factory=list)
    content: KnowledgeContent = field(default_factory=KnowledgeContent)
    version: str = "source"
    status: ObjectStatus = ObjectStatus.ACTIVE
    source: KnowledgeSource = field(default_factory=KnowledgeSource)

    # ── backward-compatible / convenience accessors (read-only) ───────────

    @property
    def name(self) -> str:
        return self.title

    @property
    def body(self) -> Optional[str]:
        return self.content.body if self.content else None

    @property
    def provenance(self) -> KnowledgeSource:
        return self.source

    @property
    def tags(self) -> list[str]:
        return list(self.metadata.get("tags", []))

    @property
    def aliases(self) -> list[str]:
        return list(self.metadata.get("aliases", []))

    @property
    def attributes(self) -> dict[str, Any]:
        return dict(self.metadata.get("attributes", {}))


@dataclass(frozen=True)
class RetrievalInfo:
    """Attached to every result set for observability (never affects behavior)."""

    provider: str
    retrieval_mode: RetrievalMode = RetrievalMode.EXACT
    score: Optional[float] = None
    latency_ms: float = 0.0
    token_estimate: int = 0
    degraded: bool = False


@dataclass
class Result(Generic[T]):
    """Uniform return type. Errors are returned, not thrown, for expected misses."""

    value: Optional[T] = None
    retrieval: Optional[RetrievalInfo] = None
    error: Optional[KnowledgeError] = None

    @property
    def ok(self) -> bool:
        return self.error is None

    @classmethod
    def success(cls, value: T, retrieval: RetrievalInfo) -> "Result[T]":
        return cls(value=value, retrieval=retrieval)

    @classmethod
    def failure(cls, error: KnowledgeError, retrieval: Optional[RetrievalInfo] = None) -> "Result[T]":
        return cls(error=error, retrieval=retrieval)


# ── Query types ─────────────────────────────────────────────────────────────


@dataclass
class KnowledgeQuery:
    """Deterministic, metadata-first query (HYBRID_RETRIEVAL.md stages 1-5)."""

    types: Optional[list[KnowledgeObjectType]] = None
    tags: Optional[list[str]] = None
    ids: Optional[list[str]] = None
    exclude_types: Optional[list[KnowledgeObjectType]] = None
    text: Optional[str] = None
    limit: int = 25
    include_body: bool = False


@dataclass
class SearchQuery:
    """Similarity retrieval; ``k`` capped by CONTEXT_SELECTION_RULES Rule 3."""

    seed_text: str
    filter: KnowledgeQuery = field(default_factory=KnowledgeQuery)
    k: int = 2


# ── Provider/runtime descriptors ────────────────────────────────────────────


@dataclass(frozen=True)
class HealthCheck:
    name: str
    passed: bool
    detail: str = ""


@dataclass
class HealthStatus:
    state: HealthState
    provider: str
    checks: list[HealthCheck] = field(default_factory=list)
    reason: str = ""

    @property
    def ok(self) -> bool:
        return self.state == HealthState.OK


@dataclass(frozen=True)
class EmbeddingModel:
    id: Optional[str] = None
    version: Optional[str] = None
    dim: Optional[int] = None
    normalize: Optional[bool] = None


@dataclass
class RuntimeIdentity:
    provider: str
    product: Optional[str] = None
    knowledge_version: Optional[str] = None
    compiler_version: Optional[str] = None
    runtime_sdk_version: Optional[str] = None
    schema_version: Optional[str] = None
    relation_schema_version: Optional[str] = None
    embedding_model: Optional[EmbeddingModel] = None
    build_timestamp: Optional[str] = None
    manifest_schema: Optional[str] = None


@dataclass
class ProviderStatistics:
    provider: str
    collections: dict[str, int] = field(default_factory=dict)
    total_objects: int = 0
    relations_count: int = 0
    db_size_bytes: int = 0
    source: str = "live"        # "live" (counted) | "manifest" (metadata-only)
    extras: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Capabilities:
    exact: bool = True
    filter: bool = True
    relation: bool = False
    vector: bool = False
