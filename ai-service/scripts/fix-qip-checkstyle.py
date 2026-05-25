#!/usr/bin/env python3
"""Fix QIP checkstyle import order and simple brace style in Java sources."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def is_standard_java(imp: str) -> bool:
    body = imp.removeprefix("import ").removesuffix(";").strip()
    if body.startswith("static "):
        body = body[7:].strip()
    # Checkstyle STANDARD_JAVA_PACKAGE is java/javax only; jakarta.* is third-party.
    return body.startswith(("java.", "javax."))


def is_static(imp: str) -> bool:
    return imp.strip().startswith("import static ")


def sort_imports(imports: list[str]) -> list[str]:
    third: list[str] = []
    std: list[str] = []
    static: list[str] = []
    for imp in imports:
        if is_static(imp):
            static.append(imp)
        elif is_standard_java(imp):
            std.append(imp)
        else:
            third.append(imp)
    def import_key(line: str) -> str:
        body = line.strip().removesuffix(";")
        if body.startswith("import static "):
            return "static:" + body[len("import static ") :]
        if body.startswith("import "):
            return body[len("import ") :]
        return body

    third.sort(key=import_key)
    std.sort(key=import_key)
    static.sort(key=import_key)
    blocks: list[str] = []
    if third:
        blocks.append("\n".join(third))
    if std:
        blocks.append("\n".join(std))
    if static:
        blocks.append("\n".join(static))
    return blocks


def fix_imports(content: str) -> str:
    lines = content.splitlines(keepends=True)
    pkg_end = 0
    for i, line in enumerate(lines):
        if line.startswith("package "):
            pkg_end = i + 1
            break
    imp_start = pkg_end
    while imp_start < len(lines) and lines[imp_start].strip() == "":
        imp_start += 1
    imp_end = imp_start
    imports: list[str] = []
    while imp_end < len(lines) and (
        lines[imp_end].startswith("import ") or lines[imp_end].strip() == ""
    ):
        if lines[imp_end].startswith("import "):
            imports.append(lines[imp_end].rstrip("\n"))
        imp_end += 1
    if not imports:
        return content
    rest = "".join(lines[imp_end:])
    header = "".join(lines[:imp_start])
    if header and not header.endswith("\n"):
        header += "\n"
    ordered = sort_imports(imports)
    new_import_block = "\n\n".join(ordered) + "\n\n"
    return header + new_import_block + rest.lstrip("\n")


def expand_one_line_methods(content: str) -> str:
    """Expand `type name() { stmt; }` onto multiple lines (Google-style braces)."""

    def repl_body(m: re.Match[str]) -> str:
        indent, decl, body = m.group(1), m.group(2).strip(), m.group(3).strip()
        if "{" in body or "}" in body or not body:
            return m.group(0)
        inner = indent + "    " + body
        return f"{indent}{decl} {{\n{inner}\n{indent}}}"

    def repl_empty(m: re.Match[str]) -> str:
        indent, decl = m.group(1), m.group(2).strip()
        return f"{indent}{decl} {{\n{indent}}}"

    content = re.compile(
        r"^(\s*)((?:public|private|protected)\s+[\w<>,\s\[\]?]+\s+\w+\s*\([^)]*\))\s*\{\s*([^;{}]+?)\s*\}\s*$",
        re.MULTILINE,
    ).sub(repl_body, content)
    return re.compile(
        r"^(\s*)((?:public|private|protected)\s+\w+\s*\([^)]*\))\s*\{\s*\}\s*$",
        re.MULTILINE,
    ).sub(repl_empty, content)


def underscore_test_method_to_camel(name: str) -> str:
    if "_" not in name:
        return name
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:] if p)


def rename_test_methods(content: str) -> str:
    """Rename test method identifiers that use underscore segments to camelCase."""

    def repl(m: re.Match[str]) -> str:
        old = m.group(1)
        new = underscore_test_method_to_camel(old)
        if new == old:
            return m.group(0)
        return m.group(0).replace(old, new, 1)

    # Package-private or public test methods with underscores in the name.
    content = re.sub(
        r"\bvoid\s+([a-z][a-zA-Z0-9]*(?:_[a-z][a-zA-Z0-9]*)+)\s*\(",
        lambda m: f"void {underscore_test_method_to_camel(m.group(1))}(",
        content,
    )
    return content


def process_file(path: Path, *, tests_only_rename: bool = False) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = original
    if "/src/test/" in str(path).replace("\\", "/"):
        updated = rename_test_methods(updated)
    if not tests_only_rename:
        updated = fix_imports(updated)
        updated = expand_one_line_methods(updated)
    elif "/src/test/" in str(path).replace("\\", "/"):
        updated = fix_imports(updated)
    if updated != original:
        path.write_text(updated, encoding="utf-8", newline="\n")
        return True
    return False


def process_file_imports_only(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = fix_imports(original)
    if updated != original:
        path.write_text(updated, encoding="utf-8", newline="\n")
        return True
    return False


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    dirs = [root / "src" / "main" / "java", root / "src" / "test" / "java"]
    imports_only = "--imports-only" in (argv or sys.argv[1:])
    tests_rename = "--tests-rename" in (argv or sys.argv[1:])
    changed = 0
    for base in dirs:
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.java")):
            if tests_rename:
                if "/src/test/" not in str(path).replace("\\", "/"):
                    continue
                if process_file(path, tests_only_rename=True):
                    changed += 1
            elif imports_only:
                if process_file_imports_only(path):
                    changed += 1
            elif process_file(path):
                changed += 1
    print(f"Updated {changed} files", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
