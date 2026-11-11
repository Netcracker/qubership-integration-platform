"""Provider Factory.

Runtime configuration selects the provider by name. Future providers register
themselves here and require **no SDK changes** — the SDK depends on the
``KnowledgeProvider`` interface, never on a concrete class.
"""

from __future__ import annotations

from typing import Callable

from .config import RuntimeConfig
from .errors import ErrorCode, KnowledgeSDKError
from .providers.base import KnowledgeProvider
from .providers.lancedb_provider import LanceDbProvider
from .providers.markdown_provider import MarkdownProvider

ProviderBuilder = Callable[[RuntimeConfig], KnowledgeProvider]

# name -> builder. Extend by calling register_provider(); no core edits needed.
_REGISTRY: dict[str, ProviderBuilder] = {
    MarkdownProvider.name: MarkdownProvider,
    LanceDbProvider.name: LanceDbProvider,
}


def register_provider(name: str, builder: ProviderBuilder) -> None:
    """Register a provider builder under ``name`` (idempotent overwrite)."""
    _REGISTRY[name.strip().lower()] = builder


def available_providers() -> list[str]:
    return sorted(_REGISTRY)


def create_provider(config: RuntimeConfig) -> KnowledgeProvider:
    """Instantiate the provider named by ``config.provider`` (not yet initialized)."""
    name = config.provider.strip().lower()
    builder = _REGISTRY.get(name)
    if builder is None:
        raise KnowledgeSDKError(
            ErrorCode.UNKNOWN_PROVIDER,
            f"unknown provider '{config.provider}'",
            f"available: {', '.join(available_providers())}",
        )
    return builder(config)
