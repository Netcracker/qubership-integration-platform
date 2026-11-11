"""The Knowledge Provider Interface.

The SDK depends ONLY on this abstract interface (PROVIDER_INTERFACE.md). LanceDB,
Markdown, and any future backend (SQLite, PostgreSQL, Neo4j, Enterprise KG)
implement it. No storage concept crosses this boundary upward.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from ..config import RuntimeConfig
from ..models import (
    Capabilities,
    HealthStatus,
    KnowledgeObject,
    KnowledgeQuery,
    KnowledgeReference,
    ProviderStatistics,
    RelationEdge,
    Result,
    RuntimeIdentity,
    SearchQuery,
)


class KnowledgeProvider(ABC):
    """Read-only provider contract. There is no write path by design."""

    #: stable provider name used in config, factory registration, and traces
    name: str = "abstract"

    def __init__(self, config: RuntimeConfig):
        self.config = config
        self._initialized = False

    # ── lifecycle ─────────────────────────────────────────────────────────

    @abstractmethod
    def initialize(self) -> HealthStatus:
        """Open the store read-only and validate identity. Idempotent.

        Returns the post-initialization :class:`HealthStatus`. A provider that
        fails validation returns an ``UNAVAILABLE``/``DEGRADED`` status rather
        than raising, so the SDK can fall back.
        """

    def close(self) -> None:
        self._initialized = False

    @property
    def initialized(self) -> bool:
        return self._initialized

    # ── introspection ─────────────────────────────────────────────────────

    @abstractmethod
    def capabilities(self) -> Capabilities: ...

    @abstractmethod
    def health(self) -> HealthStatus: ...

    @abstractmethod
    def version(self) -> RuntimeIdentity: ...

    @abstractmethod
    def statistics(self) -> ProviderStatistics: ...

    # ── retrieval (read-only) ─────────────────────────────────────────────

    @abstractmethod
    def get(self, ref: KnowledgeReference, include_body: bool = False) -> Result[KnowledgeObject]: ...

    @abstractmethod
    def search(self, query: SearchQuery | KnowledgeQuery) -> Result[list[KnowledgeObject]]: ...

    @abstractmethod
    def related(self, object_id: str, kinds: list[str] | None = None) -> Result[list[RelationEdge]]: ...

    # ── convenience ───────────────────────────────────────────────────────

    def __enter__(self) -> "KnowledgeProvider":
        self.initialize()
        return self

    def __exit__(self, *exc) -> None:
        self.close()
