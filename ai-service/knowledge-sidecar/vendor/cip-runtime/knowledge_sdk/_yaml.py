"""Dependency-free structured-document loading.

The SDK has **zero hard third-party dependencies**. YAML is loaded with PyYAML
when it is importable (it already ships with the repo tooling), and otherwise a
minimal built-in parser handles the flat/nested maps + lists used by our
config and manifest files. JSON is always supported via the stdlib.

This keeps unit tests runnable with no installation step (SUCCESS CRITERIA:
"tests must NOT require a populated LanceDB" — nor any external package).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

try:  # pragma: no cover - environment dependent
    import yaml as _pyyaml  # type: ignore
except Exception:  # pragma: no cover
    _pyyaml = None


def loads(text: str) -> Any:
    """Parse YAML/JSON text into Python data."""
    if _pyyaml is not None:
        return _pyyaml.safe_load(text)
    return _minimal_yaml(text)


def load_file(path: str | Path) -> Any:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if p.suffix.lower() == ".json":
        return json.loads(text)
    return loads(text)


# ── Minimal YAML fallback ───────────────────────────────────────────────────
# Supports: comments (# ...), 2-space-indented nested maps, "- " list items
# (scalars and inline "key: value" maps), and scalar coercion. Sufficient for
# the SDK's own config/manifest documents; not a general YAML implementation.


def _scalar(token: str) -> Any:
    t = token.strip()
    if t == "" or t == "~" or t.lower() == "null":
        return None
    if len(t) >= 2 and t[0] == t[-1] and t[0] in "\"'":
        return t[1:-1]
    if t.startswith("[") and t.endswith("]"):
        # inline flow list, e.g. [security, auth]
        inner = t[1:-1].strip()
        if not inner:
            return []
        return [_scalar(part) for part in inner.split(",")]
    low = t.lower()
    if low in ("true", "yes"):
        return True
    if low in ("false", "no"):
        return False
    try:
        return int(t)
    except ValueError:
        pass
    try:
        return float(t)
    except ValueError:
        pass
    return t


def _strip(line: str) -> str:
    # remove trailing comments not inside quotes
    in_q = None
    out = []
    for ch in line:
        if in_q:
            out.append(ch)
            if ch == in_q:
                in_q = None
        elif ch in "\"'":
            in_q = ch
            out.append(ch)
        elif ch == "#":
            break
        else:
            out.append(ch)
    return "".join(out).rstrip()


def _minimal_yaml(text: str) -> Any:
    raw_lines = text.splitlines()
    lines: list[tuple[int, str]] = []
    for ln in raw_lines:
        body = _strip(ln)
        if body.strip() == "":
            continue
        indent = len(body) - len(body.lstrip(" "))
        lines.append((indent, body.strip()))

    pos = 0

    def parse_block(min_indent: int) -> Any:
        nonlocal pos
        if pos >= len(lines):
            return None
        indent, content = lines[pos]
        if content.startswith("- "):
            return parse_list(indent)
        return parse_map(indent)

    def parse_map(indent: int) -> dict:
        nonlocal pos
        result: dict[str, Any] = {}
        while pos < len(lines):
            cur_indent, content = lines[pos]
            if cur_indent < indent or content.startswith("- "):
                break
            if cur_indent > indent:
                break
            key, sep, val = content.partition(":")
            key = key.strip()
            val = val.strip()
            pos += 1
            if val == "":
                # nested block or empty
                if pos < len(lines) and lines[pos][0] > indent:
                    result[key] = parse_block(lines[pos][0])
                else:
                    result[key] = None
            else:
                result[key] = _scalar(val)
        return result

    def parse_list(indent: int) -> list:
        nonlocal pos
        items: list[Any] = []
        while pos < len(lines):
            cur_indent, content = lines[pos]
            if cur_indent != indent or not content.startswith("- "):
                break
            item = content[2:].strip()
            pos += 1
            if ":" in item and not (item[0] in "\"'"):
                # inline map entry; may have following deeper lines
                key, _, val = item.partition(":")
                entry: dict[str, Any] = {key.strip(): _scalar(val.strip()) if val.strip() else None}
                while pos < len(lines) and lines[pos][0] > indent:
                    sub = parse_map(lines[pos][0])
                    if isinstance(sub, dict):
                        entry.update(sub)
                items.append(entry)
            else:
                items.append(_scalar(item))
        return items

    return parse_block(lines[0][0]) if lines else None
