from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
from typing import Any

import lancedb
from lancedb.index import FTS

from qip_knowledge_sidecar.runtime import RuntimeErrorResponse


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _useful_content(text: str) -> bool:
    return bool(re.sub(r"(?m)^\s{0,3}#{1,6}\s+.*$", "", text).strip())


def _search_terms(query: str) -> list[str]:
    return re.findall(r"[A-Za-z0-9_]+", query)[:64]


def _table_context(raw: bytes, offset: int, heading_path: list[str]) -> str | None:
    preceding = raw[:offset].decode("utf-8").splitlines()
    for index in range(len(preceding) - 1, 0, -1):
        if re.fullmatch(r"\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*", preceding[index]):
            return "\n".join(preceding[index - 1:index + 1])
        if not preceding[index].strip():
            break
    return " / ".join(heading_path) if heading_path else None


def _load_corpus(root: Path) -> dict[str, Any]:
    try:
        corpus_path = root / "documentation.json"
        if not corpus_path.is_file():
            raise RuntimeErrorResponse(
                code="DOCUMENTATION_UNAVAILABLE", message="Documentation corpus is unavailable",
                status=503, retryable=True,
            )
        corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
        if corpus["schemaVersion"] != 1 or not re.fullmatch(
            r"sha256:[0-9a-f]{64}", corpus["checksum"]
        ):
            raise ValueError("Unsupported schema or checksum")
        docs = {doc["path"]: doc for doc in corpus["documents"]}
        if len(docs) != len(corpus["documents"]):
            raise ValueError("Duplicate documentation paths")
        for doc in docs.values():
            content = doc["content"].encode("utf-8")
            if _sha256(content) != doc["sha256"]:
                raise ValueError(f"Content checksum mismatch for {doc['path']}")
        digest = hashlib.sha256()
        for path in sorted(docs):
            digest.update(path.encode("utf-8"))
            digest.update(b"\0")
            digest.update(docs[path]["content"].encode("utf-8"))
            digest.update(b"\0")
        if "sha256:" + digest.hexdigest() != corpus["checksum"]:
            raise ValueError("Corpus checksum mismatch")
        section_ids: set[str] = set()
        sections_by_path: dict[str, list[tuple[int, int]]] = {}
        for section in corpus["sections"]:
            start, end = section["startByte"], section["endByte"]
            path = section["path"]
            if section["id"] in section_ids or path not in docs:
                raise ValueError("Duplicate section id or unknown document path")
            section_ids.add(section["id"])
            raw = docs[path]["content"].encode("utf-8")
            if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end < start or end > len(raw):
                raise ValueError(f"Invalid section byte range for {section['id']}")
            if section["id"] != f"{path}#{start}":
                raise ValueError(f"Section id does not match its path and byte offset: {section['id']}")
            raw[start:end].decode("utf-8")
            sections_by_path.setdefault(path, []).append((start, end))
        for path, doc in docs.items():
            ranges = sorted(sections_by_path.get(path, []))
            if not ranges or ranges[0][0] != 0 or ranges[-1][1] != len(doc["content"].encode("utf-8")):
                raise ValueError(f"Sections do not cover document {path}")
            if any(left[1] != right[0] for left, right in zip(ranges, ranges[1:])):
                raise ValueError(f"Section ranges overlap or leave gaps in {path}")
        return corpus
    except RuntimeErrorResponse:
        raise
    except Exception as error:
        raise RuntimeErrorResponse(
            code="DOCUMENTATION_INTEGRITY_FAILURE",
            message=f"Documentation corpus is invalid: {error}",
            status=503,
            retryable=False,
        ) from error


def build_index(root: Path) -> None:
    corpus = _load_corpus(root)
    documents = {doc["path"]: doc for doc in corpus["documents"]}
    rows = []
    for section in corpus["sections"]:
        doc = documents[section["path"]]
        content = doc["content"].encode("utf-8")
        rows.append({
            "id": section["id"], "path": section["path"], "title": section["title"],
            "url": doc["url"], "headingPath": section["headingPath"],
            "startByte": section["startByte"], "endByte": section["endByte"],
            "searchText": " ".join([doc["title"], *section["headingPath"], section["searchText"]]),
            "text": content[section["startByte"]:section["endByte"]].decode("utf-8"),
            "corpusChecksum": corpus["checksum"],
        })
    db = lancedb.connect(str(root / "index"))
    if "sections" in db.list_tables().tables:
        db.drop_table("sections")
    table = db.create_table("sections", data=rows)
    table.create_index("searchText", config=FTS(with_position=True))


class DocumentationStore:
    def __init__(self, root: Path):
        self.root = root
        self.corpus = _load_corpus(root)
        self.checksum = self.corpus["checksum"]
        self.documents = {doc["path"]: doc for doc in self.corpus["documents"]}
        self.sections = {section["id"]: section for section in self.corpus["sections"]}
        self.element_paths = self.corpus.get("elementPaths", {})
        try:
            self.table = lancedb.connect(str(root / "index")).open_table("sections")
            if self.table.count_rows() != len(self.sections):
                raise ValueError("FTS table row count does not match manifest")
            indexed_checksums = {row["corpusChecksum"] for row in self.table.to_arrow().select(["corpusChecksum"]).to_pylist()}
            if indexed_checksums != {self.checksum}:
                raise ValueError("FTS index checksum does not match documentation corpus")
        except Exception as error:
            raise RuntimeErrorResponse(
                code="DOCUMENTATION_UNAVAILABLE", message=f"Documentation index is unavailable: {error}",
                status=503, retryable=True,
            ) from error

    def manifest(self) -> dict[str, Any]:
        return {"schemaVersion": 1, "checksum": self.checksum,
                "documentCount": len(self.documents), "sectionCount": len(self.sections)}

    def _pin(self, checksum: str) -> None:
        if checksum != self.checksum:
            raise RuntimeErrorResponse(code="DOCUMENTATION_VERSION_MISMATCH",
                                       message="expectedChecksum does not match the active documentation corpus",
                                       status=409, retryable=False)

    def search(self, checksum: str, query: str, element_types: list[str], limit: int) -> dict[str, Any]:
        self._pin(checksum)
        try:
            terms = _search_terms(query)
            if not terms:
                return {"checksum": self.checksum, "results": []}
            safe_query = " ".join(terms)
            wanted_paths = {self.element_paths[key] for key in element_types if key in self.element_paths}
            search = self.table.search(safe_query, query_type="fts")
            if wanted_paths:
                path_filter = ", ".join("'" + path.replace("'", "''") + "'" for path in sorted(wanted_paths))
                search = search.where(f"path IN ({path_filter})")
            rows = search.limit(max(limit * 4, 20)).to_list()
            if wanted_paths and not rows:
                rows = [
                    {"id": section_id, "_score": 0.0}
                    for section_id, section in self.sections.items()
                    if section["path"] in wanted_paths
                    and _useful_content(self.documents[section["path"]]["content"].encode("utf-8")[section["startByte"]:section["endByte"]].decode("utf-8"))
                ]
            rows.sort(key=lambda row: (-float(row.get("_score", 0)), row["id"]))
            result = []
            for row in rows[:limit]:
                section = self.sections[row["id"]]
                doc = self.documents[section["path"]]
                text = doc["content"].encode("utf-8")[section["startByte"]:section["endByte"]].decode("utf-8")
                if not _useful_content(text):
                    continue
                at = next((text.casefold().find(term) for term in terms if text.casefold().find(term) >= 0), 0)
                left = max(0, at - 180)
                right = min(len(text), at + 300)
                snippet = text[left:right]
                result.append({"id": row["id"], "path": section["path"], "title": section["title"],
                               "headingPath": section["headingPath"], "url": doc["url"], "snippet": snippet,
                               "startByte": section["startByte"], "endByte": section["endByte"]})
            return {"checksum": self.checksum, "results": result}
        except RuntimeErrorResponse:
            raise
        except Exception as error:
            raise RuntimeErrorResponse(code="DOCUMENTATION_UNAVAILABLE", message=f"Documentation search failed: {error}",
                                       status=503, retryable=True) from error

    @staticmethod
    def _cursor(checksum: str, section_id: str, offset: int) -> str:
        body = json.dumps([checksum, section_id, offset], separators=(",", ":")).encode()
        sig = hashlib.sha256(checksum.encode() + body).digest()[:12]
        return base64.urlsafe_b64encode(body + sig).decode().rstrip("=")

    def read(self, checksum: str, section_id: str, cursor: str | None, max_bytes: int) -> dict[str, Any]:
        self._pin(checksum)
        section = self.sections.get(section_id)
        if section is None:
            raise RuntimeErrorResponse(code="DOCUMENTATION_NOT_FOUND", message="Documentation section was not found",
                                       status=404, retryable=False)
        doc = self.documents[section["path"]]
        raw = doc["content"].encode("utf-8")
        start, end = section["startByte"], section["endByte"]
        offset = start
        if cursor:
            try:
                token = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4))
                body, signature = token[:-12], token[-12:]
                values = json.loads(body)
                if signature != hashlib.sha256(checksum.encode() + body).digest()[:12] or values[:2] != [checksum, section_id]:
                    raise ValueError("Invalid cursor signature or scope")
                offset = values[2]
                if not isinstance(offset, int) or offset <= start or offset >= end:
                    raise ValueError("Cursor offset is outside the section")
                raw[:offset].decode("utf-8")
            except Exception as error:
                raise RuntimeErrorResponse(code="DOCUMENTATION_INVALID_REQUEST", message=f"Invalid documentation cursor: {error}",
                                           status=400, retryable=False) from error
        stop = min(offset + max_bytes, end)
        newline = raw.rfind(b"\n", offset, stop)
        if newline >= offset:
            stop = newline + 1
        while stop > offset:
            try:
                text = raw[offset:stop].decode("utf-8")
                break
            except UnicodeDecodeError:
                stop -= 1
        if stop == offset:
            raise RuntimeErrorResponse(code="DOCUMENTATION_INVALID_REQUEST", message="maxBytes is too small for the next UTF-8 character",
                                       status=422, retryable=False)
        context = _table_context(raw, offset, section["headingPath"])
        next_cursor = self._cursor(checksum, section_id, stop) if stop < end else None
        return {"checksum": checksum, "id": section_id, "path": section["path"], "title": doc["title"],
                "url": doc["url"], "headingPath": section["headingPath"], "startByte": offset,
                "endByte": stop, "text": text, "context": context, "complete": next_cursor is None,
                "nextCursor": next_cursor}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["build"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    build_index(args.path)


if __name__ == "__main__":
    main()
