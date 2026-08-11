"""Runtime Knowledge SDK — Phase 1 foundation.

A storage-abstraction layer for engineering knowledge. The Runtime defaults to
the Markdown provider (the current Source of Truth); LanceDB is optional. Nothing
here changes existing Runtime behavior — this package is additive and transparent.

Public API::

    from knowledge_sdk import KnowledgeSDK, RuntimeConfig
    sdk = KnowledgeSDK.from_config({"provider": "markdown", "knowledge_root": "knowledge"})
    sdk.get("CORPORATE_CIP_STANDARDS.md :: authentication")
    sdk.health(); sdk.version(); sdk.statistics()
"""

from __future__ import annotations

from .config import DEFAULT_PROVIDER, RuntimeConfig
from .contract import (
    EquivalenceResult,
    objects_equivalent,
    validate_object_contract,
    validate_provider_contract,
)
from .errors import ErrorCode, KnowledgeError, KnowledgeSDKError
from .factory import available_providers, create_provider, register_provider
from .ir import (
    IR_VERSION,
    dump_collection,
    dumps,
    from_ir,
    load_collection,
    loads,
    to_ir,
    validate_ir,
)
from .mapper import MarkdownKnowledgeMapper
from .models import (
    Capabilities,
    EmbeddingModel,
    HealthCheck,
    HealthState,
    HealthStatus,
    KnowledgeContent,
    KnowledgeObject,
    KnowledgeObjectType,
    KnowledgeQuery,
    KnowledgeReference,
    KnowledgeSource,
    ObjectStatus,
    Provenance,
    ProviderStatistics,
    RelationEdge,
    Result,
    RetrievalInfo,
    RetrievalMode,
    RuntimeIdentity,
    SearchQuery,
)
from .providers.base import KnowledgeProvider
from .providers.lancedb_provider import LanceDbProvider
from .providers.markdown_provider import MarkdownProvider
from .sdk import KnowledgeSDK

__version__ = "1.0.0"

__all__ = [
    "__version__",
    "KnowledgeSDK",
    "RuntimeConfig",
    "DEFAULT_PROVIDER",
    "KnowledgeProvider",
    "MarkdownProvider",
    "LanceDbProvider",
    "create_provider",
    "register_provider",
    "available_providers",
    # canonical object model
    "KnowledgeObject",
    "KnowledgeObjectType",
    "KnowledgeContent",
    "KnowledgeSource",
    "ObjectStatus",
    "KnowledgeReference",
    "KnowledgeQuery",
    "SearchQuery",
    "RelationEdge",
    "Provenance",
    # mapper + IR
    "MarkdownKnowledgeMapper",
    "IR_VERSION",
    "to_ir",
    "from_ir",
    "dumps",
    "loads",
    "dump_collection",
    "load_collection",
    "validate_ir",
    # contract
    "objects_equivalent",
    "validate_object_contract",
    "validate_provider_contract",
    "EquivalenceResult",
    # results & runtime descriptors
    "Result",
    "RetrievalInfo",
    "RetrievalMode",
    "HealthStatus",
    "HealthState",
    "HealthCheck",
    "RuntimeIdentity",
    "ProviderStatistics",
    "Capabilities",
    "EmbeddingModel",
    "ErrorCode",
    "KnowledgeError",
    "KnowledgeSDKError",
]
