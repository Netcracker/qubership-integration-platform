"""Provider Contract validation.

Two guarantees this module verifies:

1. **Storage neutrality** — a provider's Knowledge Objects serialize to valid,
   storage-neutral IR (no vectors/tables/arrow keys). See :func:`validate_ir`.
2. **Cross-provider identity** — every provider returns *identical* Runtime
   Knowledge Objects for the same reference, regardless of backend. The SDK must
   receive the same object whether it came from Markdown or LanceDB.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from .ir import to_ir, validate_ir
from .models import KnowledgeObject, KnowledgeReference
from .providers.base import KnowledgeProvider


@dataclass
class EquivalenceResult:
    equivalent: bool
    differences: list[str]

    def __bool__(self) -> bool:  # pragma: no cover - trivial
        return self.equivalent


def objects_equivalent(a: KnowledgeObject, b: KnowledgeObject) -> EquivalenceResult:
    """Two objects are equivalent iff their canonical IR is identical."""
    ir_a, ir_b = to_ir(a), to_ir(b)
    diffs = _diff(ir_a, ir_b, path="")
    return EquivalenceResult(equivalent=not diffs, differences=diffs)


def validate_object_contract(obj: KnowledgeObject) -> list[str]:
    """A single object must serialize to valid, storage-neutral IR."""
    return validate_ir(to_ir(obj))


def validate_provider_contract(
    provider: KnowledgeProvider,
    references: list[str],
    *,
    reference_provider: Optional[KnowledgeProvider] = None,
) -> list[str]:
    """Validate a provider against the Runtime contract.

    For each reference: the object serializes to valid, storage-neutral IR; and
    (when ``reference_provider`` is given) it is identical to that provider's
    object for the same reference.
    """
    problems: list[str] = []
    for raw in references:
        ref = KnowledgeReference.parse(raw)
        result = provider.get(ref, include_body=True)
        if not result.ok or result.value is None:
            problems.append(f"{provider.name}: could not resolve {raw}: "
                            f"{result.error if result.error else 'no value'}")
            continue

        ir_problems = validate_object_contract(result.value)
        problems += [f"{provider.name}:{raw}: {p}" for p in ir_problems]

        if reference_provider is not None:
            ref_result = reference_provider.get(ref, include_body=True)
            if not ref_result.ok or ref_result.value is None:
                problems.append(f"{reference_provider.name}: could not resolve {raw}")
                continue
            eq = objects_equivalent(result.value, ref_result.value)
            if not eq.equivalent:
                problems.append(
                    f"{provider.name} vs {reference_provider.name} differ for {raw}: "
                    f"{eq.differences}"
                )
    return problems


# ── diff helper ──────────────────────────────────────────────────────────────


def _diff(a: Any, b: Any, path: str) -> list[str]:
    diffs: list[str] = []
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if key not in a:
                diffs.append(f"{path}.{key}: missing in first")
            elif key not in b:
                diffs.append(f"{path}.{key}: missing in second")
            else:
                diffs += _diff(a[key], b[key], f"{path}.{key}")
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            diffs.append(f"{path}: length {len(a)} != {len(b)}")
        else:
            for i, (ia, ib) in enumerate(zip(a, b)):
                diffs += _diff(ia, ib, f"{path}[{i}]")
    elif a != b:
        diffs.append(f"{path}: {a!r} != {b!r}")
    return diffs
