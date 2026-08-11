"""Tiny semantic-version helpers for manifest compatibility checks.

Supports the range forms the Knowledge Manifest uses
(``KNOWLEDGE_MANIFEST.md`` §4): exact (``1.4.0``), caret-free comparator ranges
(``>=0.2.0 <0.3``), and ``x`` wildcards (``1.0.x``). Not a full semver library.
"""

from __future__ import annotations

import re

_NUM = re.compile(r"\d+")


def parse(version: str) -> tuple[int, int, int]:
    parts = [p for p in re.split(r"[.\-+]", version.strip()) if p != ""]
    nums: list[int] = []
    for p in parts[:3]:
        m = _NUM.match(p)
        nums.append(int(m.group()) if m else 0)
    while len(nums) < 3:
        nums.append(0)
    return nums[0], nums[1], nums[2]


def _cmp(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    return (a > b) - (a < b)


def satisfies(version: str, spec: str) -> bool:
    """True if ``version`` satisfies ``spec``.

    ``spec`` may be:
      - empty/None-ish -> always True
      - "x.y.z"        -> exact match on provided components
      - "1.0.x"        -> wildcard on the patch (or minor) component
      - ">=a <b" etc.  -> space-separated comparators (>=, >, <=, <, =)
    """
    if not spec or not str(spec).strip():
        return True
    spec = str(spec).strip()
    v = parse(version)

    if any(op in spec for op in (">", "<", "=")):
        for comparator in spec.split():
            if not _satisfies_comparator(v, comparator):
                return False
        return True

    if "x" in spec.lower() or "*" in spec:
        want = spec.lower().replace("*", "x").split(".")
        have = list(parse(version))
        for i, token in enumerate(want[:3]):
            if token in ("x", ""):
                continue
            if int(_NUM.match(token).group()) != have[i]:  # type: ignore[union-attr]
                return False
        return True

    return v == parse(spec)


def _satisfies_comparator(v: tuple[int, int, int], comparator: str) -> bool:
    m = re.match(r"(>=|<=|>|<|=)?\s*(.+)", comparator.strip())
    if not m:
        return True
    op, ver = m.group(1) or "=", m.group(2)
    c = _cmp(v, parse(ver))
    return {
        ">=": c >= 0,
        "<=": c <= 0,
        ">": c > 0,
        "<": c < 0,
        "=": c == 0,
    }[op]
