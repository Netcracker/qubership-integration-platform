"""Runtime diagnostics and manifest compatibility checks.

Implements the pre-load validation ordering from ``KNOWLEDGE_MANIFEST.md`` §3
(blocking vs. degrading) as a reusable function. Providers call
:func:`evaluate_runtime` to produce a :class:`HealthStatus`. This module is
provider-independent: it validates a manifest *dict*, not a store.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

from . import _semver, _yaml
from .config import RuntimeConfig
from .models import HealthCheck, HealthState, HealthStatus

# manifest_schema shapes this SDK understands
SUPPORTED_MANIFEST_SCHEMAS = {"1.0"}
# object/relation schema MAJOR this SDK understands
SUPPORTED_SCHEMA_MAJOR = {1}


def load_manifest(path: str | Path) -> Optional[dict[str, Any]]:
    p = Path(path)
    if not p.exists():
        return None
    data = _yaml.load_file(p)
    return data if isinstance(data, dict) else None


def _major(version: Optional[str]) -> Optional[int]:
    if not version:
        return None
    return _semver.parse(str(version))[0]


def evaluate_runtime(
    *,
    provider: str,
    manifest: Optional[dict[str, Any]],
    config: RuntimeConfig,
    db_present: bool,
    library_present: bool,
) -> HealthStatus:
    """Return a HealthStatus for a compiled-runtime provider.

    Precedence:
      * a **blocking** failure (missing/invalid/incompatible manifest, wrong
        product) -> ``UNAVAILABLE``;
      * manifest valid but the store/library/vector index is absent
        -> ``DEGRADED`` (metadata is still serviceable: version/statistics);
      * everything present and compatible -> ``OK``.
    """
    checks: list[HealthCheck] = []
    blocking_failed = False
    degraded = False

    # 1. manifest present & parseable
    ok = manifest is not None
    checks.append(HealthCheck("manifest_present", ok, "" if ok else "manifest missing or unparseable"))
    if not ok:
        return HealthStatus(HealthState.UNAVAILABLE, provider, checks,
                            "manifest missing or unparseable (ERR-KS-006)")

    assert manifest is not None

    # 2. manifest_schema understood
    m_schema = str(manifest.get("manifest_schema", "")).strip()
    ok = m_schema in SUPPORTED_MANIFEST_SCHEMAS
    checks.append(HealthCheck("manifest_schema_supported", ok, f"manifest_schema={m_schema or '?'}"))
    blocking_failed = blocking_failed or not ok

    # 3. runtime_sdk_version range satisfied
    requires_sdk = _compat(manifest, "requires_runtime_sdk") or manifest.get("runtime_sdk_version")
    ok = _semver.satisfies(config.sdk_version, requires_sdk or "")
    checks.append(HealthCheck("runtime_sdk_compatible", ok,
                              f"sdk={config.sdk_version} requires={requires_sdk or 'any'}"))
    blocking_failed = blocking_failed or not ok

    # 4. object schema_version MAJOR understood
    schema_major = _major(manifest.get("schema_version"))
    ok = schema_major is None or schema_major in SUPPORTED_SCHEMA_MAJOR
    checks.append(HealthCheck("object_schema_compatible", ok,
                              f"schema_version={manifest.get('schema_version')}"))
    blocking_failed = blocking_failed or not ok

    # 5. relation schema_version MAJOR compatible
    rel_major = _major(manifest.get("relation_schema_version"))
    ok = rel_major is None or rel_major in SUPPORTED_SCHEMA_MAJOR
    checks.append(HealthCheck("relation_schema_compatible", ok,
                              f"relation_schema_version={manifest.get('relation_schema_version')}"))
    degraded = degraded or not ok  # relation gap degrades related(), not fatal

    # 6. product matches active product context (only if a context is set)
    m_product = manifest.get("product")
    if config.product:
        ok = str(m_product).lower() == str(config.product).lower()
        checks.append(HealthCheck("product_matches", ok,
                                  f"manifest={m_product} active={config.product}"))
        blocking_failed = blocking_failed or not ok
    else:
        checks.append(HealthCheck("product_matches", True, f"no active product context (manifest={m_product})"))

    # 8. embedding model present when a vector store is implied (degrading)
    has_embed = bool(manifest.get("embedding_model"))
    checks.append(HealthCheck("embedding_model_present", has_embed,
                              "vector ranking available" if has_embed else "no embedding model (vector -> filter)"))
    degraded = degraded or not has_embed

    # provider availability (library) and database availability (store)
    checks.append(HealthCheck("provider_library_available", library_present,
                              "lancedb importable" if library_present else "lancedb not installed"))
    checks.append(HealthCheck("database_available", db_present,
                              "store present" if db_present else "compiled store not present"))
    degraded = degraded or not library_present or not db_present

    if blocking_failed:
        reason = "manifest incompatible with runtime (see failed checks)"
        return HealthStatus(HealthState.UNAVAILABLE, provider, checks, reason)
    if degraded:
        reason = "runtime metadata valid; retrieval degraded (store/library/vector unavailable)"
        return HealthStatus(HealthState.DEGRADED, provider, checks, reason)
    return HealthStatus(HealthState.OK, provider, checks, "")


def _compat(manifest: dict[str, Any], key: str) -> Optional[str]:
    compat = manifest.get("compatibility")
    if isinstance(compat, dict) and key in compat:
        return str(compat[key])
    return None


# ── generation gate ───────────────────────────────────────────────────────────

#: The only provider permitted to serve a generation run.
REQUIRED_PROVIDER = "lancedb"


def evaluate_readiness(sdk, probe_reference: str) -> HealthStatus:
    """Mandatory gate before generation: is LanceDB actually serving?

    This deliberately does **not** gate on ``provider.health() == OK``. A runtime
    with no native ``lancedb`` library and no vector index reports ``DEGRADED``
    while serving correct, deterministic results from the packaged JSONL rows —
    gating on OK would block a system that is working. ``DEGRADED`` is acceptable;
    what is not acceptable is Markdown answering, or nothing answering.

    Three checks, all blocking:

    * ``lancedb_active``   — the serving provider is LanceDB, not Markdown.
    * ``no_runtime_fallback`` — no fallback provider is wired.
    * ``retrieval_probe``  — a known reference actually resolves. Reading a
      manifest proves the manifest exists; only a probe proves retrieval works.

    Returns ``OK`` (ready to generate) or ``UNAVAILABLE`` (blocked). Never
    ``DEGRADED``: generation is permitted or it is not.
    """
    checks: list[HealthCheck] = []

    active = getattr(sdk, "provider_name", None)
    ok = active == REQUIRED_PROVIDER
    checks.append(HealthCheck(
        "lancedb_active", ok,
        f"serving provider={active!r} (required={REQUIRED_PROVIDER!r})"))
    if not ok:
        return HealthStatus(
            HealthState.UNAVAILABLE, str(active), checks,
            f"LanceDB is not the serving provider (got {active!r}). "
            "LanceDB is mandatory; run 'compiler sync-project'.")

    fb = getattr(sdk, "fallback_name", None)
    ok = fb is None
    checks.append(HealthCheck("no_runtime_fallback", ok, f"fallback={fb!r}"))
    if not ok:
        return HealthStatus(
            HealthState.UNAVAILABLE, str(active), checks,
            f"a runtime fallback provider is wired ({fb!r}); LanceDB must be the only source.")

    result = sdk.get(probe_reference)
    ok = bool(getattr(result, "ok", False))
    detail = f"probe={probe_reference!r}"
    if not ok and getattr(result, "error", None) is not None:
        detail += f" error={result.error.code.value}"
    checks.append(HealthCheck("retrieval_probe", ok, detail))
    if not ok:
        return HealthStatus(
            HealthState.UNAVAILABLE, str(active), checks,
            f"LanceDB is active but retrieval failed for {probe_reference!r}. "
            "The Knowledge Package is unusable; run 'compiler sync-project'.")

    return HealthStatus(HealthState.OK, str(active), checks, "")
