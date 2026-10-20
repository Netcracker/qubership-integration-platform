"""Error model for the Runtime Knowledge SDK.

The SDK never leaks storage-specific exceptions. Every recoverable condition is
returned as a ``Result`` carrying a :class:`KnowledgeError`; only genuinely
unexpected states raise :class:`KnowledgeSDKError`.

Error codes follow the ``ERR-KS-*`` family reserved in the architecture
(``RUNTIME_API.md`` §8, ``KNOWLEDGE_MANIFEST.md`` §3). Phase 1 registers the
subset the foundation can actually reach.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class ErrorCode(str, Enum):
    """Stable ``ERR-KS-*`` codes. Values are the wire form used in logs/traces."""

    NOT_FOUND = "ERR-KS-001"            # reference not found in any provider
    SCHEMA_UNSUPPORTED = "ERR-KS-002"   # manifest object/relation schema unsupported
    PROVIDER_UNAVAILABLE = "ERR-KS-003"  # provider unreadable / not ready
    VECTOR_UNAVAILABLE = "ERR-KS-004"   # vector search requested, no vector capability
    VERSION_MISMATCH = "ERR-KS-005"     # DB/manifest version vs SDK/package-lock mismatch
    MANIFEST_INVALID = "ERR-KS-006"     # manifest missing/unparseable/unknown shape
    PRODUCT_MISMATCH = "ERR-KS-007"     # manifest product != active product context
    INTEGRITY = "ERR-KS-008"            # integrity/hash check failed
    CONFIG_INVALID = "ERR-KS-009"       # runtime configuration invalid
    UNKNOWN_PROVIDER = "ERR-KS-010"     # configured provider name not registered


@dataclass(frozen=True)
class KnowledgeError:
    """A recoverable error returned inside a :class:`~knowledge_sdk.models.Result`."""

    code: ErrorCode
    message: str
    detail: str | None = None

    def __str__(self) -> str:  # pragma: no cover - trivial
        base = f"[{self.code.value}] {self.message}"
        return f"{base} ({self.detail})" if self.detail else base


class KnowledgeSDKError(Exception):
    """Raised only for unexpected, non-recoverable conditions."""

    def __init__(self, code: ErrorCode, message: str, detail: str | None = None):
        self.error = KnowledgeError(code, message, detail)
        super().__init__(str(self.error))
