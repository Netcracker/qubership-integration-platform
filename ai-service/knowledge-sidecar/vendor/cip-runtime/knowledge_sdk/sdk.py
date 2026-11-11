"""The Knowledge SDK facade.

A deliberately small, storage-agnostic API. The SDK talks only to a
``KnowledgeProvider`` (selected by the factory from config); providers talk to
storage. Nothing here names LanceDB or any storage concept.

Required operations (RUNTIME_API.md §4): ``get``, ``search``, ``related``,
``health``, ``statistics``, ``version``.

There is **no runtime fallback**. LanceDB is mandatory: if the active provider
cannot serve a call, the error is returned. Retrieval never silently re-resolves
against Markdown, because a silent fallback is indistinguishable — to the caller
and to the trace — from the compiled package having answered.

Markdown remains the compile-time Source of Truth and the parity reference for
``contract.validate_provider_contract``; it is not a runtime safety net.
"""

from __future__ import annotations

from typing import Optional

from . import factory
from .config import RuntimeConfig
from .errors import ErrorCode
from .models import (
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
from .providers.base import KnowledgeProvider

__all__ = ["KnowledgeSDK"]


class KnowledgeSDK:
    """Public entry point. Construct via :meth:`from_config` or :meth:`create`."""

    def __init__(self, provider: KnowledgeProvider):
        self._provider = provider
        self._initialized = False

    # ── construction ──────────────────────────────────────────────────────

    @classmethod
    def from_config(cls, config: "RuntimeConfig | dict | str | None" = None) -> "KnowledgeSDK":
        cfg = RuntimeConfig.resolve(config)
        provider = factory.create_provider(cfg)
        # cfg.fallback is parsed for backward compatibility and deliberately not
        # honored: there is no runtime fallback provider.
        sdk = cls(provider)
        sdk.initialize()
        return sdk

    @classmethod
    def create(cls, **kwargs) -> "KnowledgeSDK":
        return cls.from_config(kwargs)

    def initialize(self) -> HealthStatus:
        status = self._provider.initialize()
        self._initialized = True
        return status

    def close(self) -> None:
        self._provider.close()

    @property
    def provider_name(self) -> str:
        return self._provider.name

    @property
    def fallback_name(self) -> Optional[str]:
        """Always ``None``. Retained so callers that report it keep working."""
        return None

    # ── required API ──────────────────────────────────────────────────────

    def get(self, reference: "str | KnowledgeReference", include_body: bool = False) -> Result[KnowledgeObject]:
        ref = reference if isinstance(reference, KnowledgeReference) else KnowledgeReference.parse(reference)
        return self._provider.get(ref, include_body=include_body)

    def search(self, query: "SearchQuery | KnowledgeQuery") -> Result[list[KnowledgeObject]]:
        return self._provider.search(query)

    def related(self, object_id: str, kinds: list[str] | None = None) -> Result[list[RelationEdge]]:
        return self._provider.related(object_id, kinds)

    def health(self) -> HealthStatus:
        """Active provider's diagnostics (provider availability, DB, manifest, versions)."""
        return self._provider.health()

    def fallback_health(self) -> Optional[HealthStatus]:
        """Always ``None``. There is no fallback provider."""
        return None

    def statistics(self) -> ProviderStatistics:
        return self._provider.statistics()

    def version(self) -> RuntimeIdentity:
        return self._provider.version()
