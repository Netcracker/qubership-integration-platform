"""Runtime configuration for the Knowledge SDK.

The Runtime **defaults to LanceDB**, which is mandatory: retrieval is served from
the compiled Knowledge Package and there is no runtime fallback to Markdown. Config
can come from a dict, a YAML/JSON file, or environment variables — in that order of
explicitness. It only selects which provider the SDK constructs.

Markdown remains the compile-time Source of Truth (``knowledge_build`` reads it to
produce the package) and the reference provider for parity tests. It is no longer a
runtime safety net: a Runtime that cannot serve from the package fails closed rather
than silently answering from Markdown.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from . import _yaml
from .errors import ErrorCode, KnowledgeSDKError

DEFAULT_PROVIDER = "lancedb"
#: Compile-time Source of Truth and parity reference. Never a runtime fallback.
SOURCE_OF_TRUTH_PROVIDER = "markdown"
ENV_PREFIX = "CIP_KNOWLEDGE_"


@dataclass
class RuntimeConfig:
    """Resolved runtime configuration.

    Attributes
    ----------
    provider:
        Active provider name (``lancedb`` by default). Resolved by the factory.
    knowledge_root:
        Root of the Markdown Knowledge Base — the compile-time Source of Truth and
        the parity reference. Not consulted by the runtime retrieval path.
    runtime_knowledge_dir:
        Directory holding the compiled runtime + manifest (LanceDB companion).
    product:
        Active product context (CIP | OM | ...), used for manifest validation.
    sdk_version:
        The running SDK version (compatibility checks read this).
    fallback:
        Deprecated and ignored by the runtime. Retained only so existing config
        files parse. LanceDB is mandatory; there is no runtime fallback.
    options:
        Free-form provider-specific options (never interpreted by the SDK core).
    """

    provider: str = DEFAULT_PROVIDER
    knowledge_root: Optional[str] = None
    runtime_knowledge_dir: Optional[str] = None
    product: Optional[str] = None
    sdk_version: str = "1.0.0"
    fallback: Optional[str] = None
    options: dict[str, Any] = field(default_factory=dict)

    # ── constructors ────────────────────────────────────────────────────────

    @classmethod
    def default(cls) -> "RuntimeConfig":
        return cls()

    @classmethod
    def from_dict(cls, data: dict[str, Any] | None) -> "RuntimeConfig":
        data = dict(data or {})
        provider = str(data.get("provider", DEFAULT_PROVIDER) or DEFAULT_PROVIDER).strip().lower()
        known = {
            "provider",
            "knowledge_root",
            "runtime_knowledge_dir",
            "product",
            "sdk_version",
            "fallback",
            "options",
        }
        options = dict(data.get("options") or {})
        # any unknown top-level keys are preserved as provider options
        for key, value in data.items():
            if key not in known:
                options[key] = value
        cfg = cls(
            provider=provider,
            knowledge_root=data.get("knowledge_root"),
            runtime_knowledge_dir=data.get("runtime_knowledge_dir"),
            product=data.get("product"),
            sdk_version=str(data.get("sdk_version", "1.0.0")),
            # Parsed for backward compatibility with existing config files, then
            # ignored: LanceDB is mandatory and the runtime has no fallback.
            fallback=data.get("fallback"),
            options=options,
        )
        cfg.validate()
        return cfg

    @classmethod
    def from_file(cls, path: str | Path) -> "RuntimeConfig":
        p = Path(path)
        if not p.exists():
            raise KnowledgeSDKError(
                ErrorCode.CONFIG_INVALID, "config file not found", str(p)
            )
        data = _yaml.load_file(p)
        if data is not None and not isinstance(data, dict):
            raise KnowledgeSDKError(
                ErrorCode.CONFIG_INVALID, "config root must be a mapping", str(p)
            )
        return cls.from_dict(data or {})

    @classmethod
    def from_env(cls, environ: Optional[dict[str, str]] = None) -> "RuntimeConfig":
        env = environ if environ is not None else os.environ
        data: dict[str, Any] = {}
        for field_name in ("provider", "knowledge_root", "runtime_knowledge_dir", "product", "sdk_version", "fallback"):
            key = ENV_PREFIX + field_name.upper()
            if key in env and env[key] != "":
                data[field_name] = env[key]
        return cls.from_dict(data)

    @classmethod
    def resolve(
        cls,
        config: "RuntimeConfig | dict[str, Any] | str | Path | None" = None,
        environ: Optional[dict[str, str]] = None,
    ) -> "RuntimeConfig":
        """Resolve a config from the most convenient available form."""
        if isinstance(config, RuntimeConfig):
            return config
        if isinstance(config, (str, Path)):
            return cls.from_file(config)
        if isinstance(config, dict):
            return cls.from_dict(config)
        # nothing passed: environment, else default (markdown)
        env_cfg = cls.from_env(environ)
        return env_cfg

    # ── validation ────────────────────────────────────────────────────────

    def validate(self) -> None:
        if not self.provider or not isinstance(self.provider, str):
            raise KnowledgeSDKError(
                ErrorCode.CONFIG_INVALID, "provider must be a non-empty string"
            )
