"""Deterministic block-style YAML emitter — the writer paired with ``_yaml``.

We emit YAML with our OWN dumper (not PyYAML) so output bytes are identical
regardless of whether PyYAML is installed — essential for reproducible builds.
Output stays within the subset the ``_yaml`` loader beside it (PyYAML or its
minimal fallback) can read: block maps, block lists, simple scalars. Keys keep
insertion order (no sorting) for stable, readable documents.

WHY THIS LIVES IN THE SDK
-------------------------
It was originally private to the Knowledge Compiler, because the manifest was the
only thing being written. It is not compiler-specific: it is a serializer with no
dependency on anything in ``knowledge_build``, and it is the exact inverse of
``knowledge_sdk._yaml``.

Runtime-side tooling (``runtime_import``) writes validation, health and
certification reports, so it needs a dumper too. Reaching into
``knowledge_build`` for one would drag the whole Compiler into every Runtime
installation — handing the Runtime the capability to compile knowledge, which the
frozen architecture forbids it. Removing the capability beats withholding the
permission, so the serializer moved down to the layer both sides already share.

``knowledge_build._yamlout`` re-exports this module; existing imports are
unaffected.
"""

from __future__ import annotations

from typing import Any

_PLAIN_SAFE = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_./:")


def dump(data: Any) -> str:
    lines: list[str] = []
    _emit(data, 0, lines)
    return "\n".join(lines) + "\n"


def _emit(node: Any, indent: int, lines: list[str]) -> None:
    pad = "  " * indent
    if isinstance(node, dict):
        if not node:
            lines.append(f"{pad}{{}}")
            return
        for key, value in node.items():
            if isinstance(value, dict) and value:
                lines.append(f"{pad}{key}:")
                _emit(value, indent + 1, lines)
            elif isinstance(value, list) and value:
                lines.append(f"{pad}{key}:")
                _emit(value, indent, lines)   # lists align under the key at same indent
            elif isinstance(value, dict):     # empty dict
                lines.append(f"{pad}{key}: {{}}")
            elif isinstance(value, list):     # empty list
                lines.append(f"{pad}{key}: []")
            else:
                lines.append(f"{pad}{key}: {_scalar(value)}")
    elif isinstance(node, list):
        for item in node:
            if isinstance(item, dict) and item:
                first = True
                for k, v in item.items():
                    prefix = f"{pad}- " if first else f"{pad}  "
                    first = False
                    if isinstance(v, (dict, list)) and v:
                        lines.append(f"{prefix}{k}:")
                        _emit(v, indent + 2, lines)
                    else:
                        lines.append(f"{prefix}{k}: {_scalar(v)}")
            else:
                lines.append(f"{pad}- {_scalar(item)}")
    else:
        lines.append(f"{pad}{_scalar(node)}")


def _scalar(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    text = str(value)
    if text == "":
        return '""'
    if all(ch in _PLAIN_SAFE for ch in text) and not text[0].isdigit() and text.lower() not in (
        "null", "true", "false", "yes", "no", "~"
    ):
        return text
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'
