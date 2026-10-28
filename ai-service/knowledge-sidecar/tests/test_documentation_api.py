import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from qip_knowledge_sidecar.app import create_app
from qip_knowledge_sidecar.documentation import build_index


@pytest.fixture
def docs(tmp_path: Path) -> Path:
    root = tmp_path / "documentation"
    root.mkdir()
    text = "# Setup\n\nThis café connector uses a distinctive purple-widget switch.\n\n| Name | Value |\n| --- | --- |\n| Host | localhost |\n"
    raw = text.encode("utf-8")
    section = {
        "id": "guides/setup.md#0", "path": "guides/setup.md", "title": "Setup",
        "headingPath": ["Setup"], "startByte": 0, "endByte": len(raw),
        "searchText": "Setup This café connector uses a distinctive purple-widget switch. Name Value Host localhost",
    }
    corpus = {
        "schemaVersion": 1, "checksum": "sha256:" + hashlib.sha256(b"guides/setup.md\0" + raw + b"\0").hexdigest(),
        "documents": [{"path": "guides/setup.md", "title": "Setup", "url": "/docs/setup",
                       "content": text, "sha256": hashlib.sha256(raw).hexdigest()}],
        "sections": [section], "elementPaths": {"connector": "guides/setup.md"},
    }
    (root / "documentation.json").write_text(json.dumps(corpus), encoding="utf-8")
    build_index(root)
    return root


def write_corpus(root: Path, documents: dict[str, str], element_paths: dict[str, str]) -> None:
    entries = []
    sections = []
    digest = hashlib.sha256()
    for path, text in sorted(documents.items()):
        raw = text.encode()
        entries.append({"path": path, "title": path, "url": "/" + path,
                        "content": text, "sha256": hashlib.sha256(raw).hexdigest()})
        sections.append({"id": f"{path}#0", "path": path, "title": path,
                         "headingPath": [path], "startByte": 0, "endByte": len(raw),
                         "searchText": text})
        digest.update(path.encode() + b"\0" + raw + b"\0")
    corpus = {"schemaVersion": 1, "checksum": "sha256:" + digest.hexdigest(),
              "documents": entries, "sections": sections, "elementPaths": element_paths}
    root.mkdir(parents=True, exist_ok=True)
    (root / "documentation.json").write_text(json.dumps(corpus), encoding="utf-8")


def table_corpus(root: Path) -> tuple[str, str]:
    rows = [f"| row-{index} | value-{index} |\n" for index in range(45)]
    text = "# Results\n\n| Name | Value |\n| --- | --- |\n" + "".join(rows)
    raw = text.encode()
    split = raw.index(b"| row-40")
    checksum = "sha256:" + hashlib.sha256(b"guide.md\0" + raw + b"\0").hexdigest()
    corpus = {"schemaVersion": 1, "checksum": checksum,
              "documents": [{"path": "guide.md", "title": "Results", "url": "/guide",
                             "content": text, "sha256": hashlib.sha256(raw).hexdigest()}],
              "sections": [{"id": "guide.md#0", "path": "guide.md", "title": "Results",
                            "headingPath": ["Results"], "startByte": 0, "endByte": split,
                            "searchText": text[:split]},
                           {"id": f"guide.md#{split}", "path": "guide.md", "title": "Results",
                            "headingPath": ["Results"], "startByte": split, "endByte": len(raw),
                            "searchText": text[split:]}],
              "elementPaths": {}}
    root.mkdir(parents=True, exist_ok=True)
    (root / "documentation.json").write_text(json.dumps(corpus), encoding="utf-8")
    return checksum, f"guide.md#{split}"


def test_documentation_works_without_private_knowledge_package(docs: Path, tmp_path: Path) -> None:
    with TestClient(create_app(tmp_path / "missing-private-package", docs)) as client:
        manifest = client.get("/v1/documentation/manifest")
        assert manifest.status_code == 200
        checksum = "sha256:" + hashlib.sha256(b"guides/setup.md\0" + json.loads((docs / "documentation.json").read_text())["documents"][0]["content"].encode() + b"\0").hexdigest()
        assert manifest.json() == {"schemaVersion": 1, "checksum": checksum,
                                   "documentCount": 1, "sectionCount": 1}
        assert client.get("/v1/health/ready").status_code == 503


def test_search_matches_section_body_and_returns_real_fts_result(docs: Path, tmp_path: Path) -> None:
    checksum = json.loads((docs / "documentation.json").read_text())["checksum"]
    with TestClient(create_app(tmp_path / "missing-private-package", docs)) as client:
        result = client.post("/v1/documentation/search", json={"expectedChecksum": checksum,
                                "query": "purple-widget", "elementTypes": ["connector"], "limit": 5})
    assert result.status_code == 200
    assert result.json()["results"][0]["id"] == "guides/setup.md#0"
    assert "purple-widget" in result.json()["results"][0]["snippet"]


def test_read_pages_preserve_utf8_bytes_and_reject_stale_or_cross_section_cursors(
    docs: Path, tmp_path: Path,
) -> None:
    checksum = json.loads((docs / "documentation.json").read_text())["checksum"]
    with TestClient(create_app(tmp_path / "missing-private-package", docs)) as client:
        first = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                   "id": "guides/setup.md#0", "maxBytes": 11}).json()
        chunks = [first["text"]]
        cursor = first["nextCursor"]
        while cursor:
            page = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                     "id": "guides/setup.md#0", "cursor": cursor, "maxBytes": 11}).json()
            chunks.append(page["text"])
            cursor = page["nextCursor"]
        assert "".join(chunks).encode("utf-8") == json.loads((docs / "documentation.json").read_text())["documents"][0]["content"].encode()
        assert page["complete"] is True
        assert client.post("/v1/documentation/read", json={"expectedChecksum": "sha256:stale", "id": "guides/setup.md#0"}).status_code == 409
        assert client.post("/v1/documentation/read", json={"expectedChecksum": checksum, "id": "missing"}).status_code == 404
        assert client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                "id": "guides/setup.md#0", "cursor": first["nextCursor"] + "x"}).status_code == 400


def test_documentation_unavailable_is_independent_of_private_readiness(tmp_path: Path) -> None:
    with TestClient(create_app(tmp_path / "missing-private-package", tmp_path / "missing-docs")) as client:
        response = client.get("/v1/documentation/manifest")
    assert response.status_code == 503
    assert response.json()["code"] == "DOCUMENTATION_UNAVAILABLE"


def test_tiny_page_limit_fails_when_next_utf8_character_cannot_fit(docs: Path, tmp_path: Path) -> None:
    checksum = json.loads((docs / "documentation.json").read_text())["checksum"]
    with TestClient(create_app(tmp_path / "missing-private-package", docs)) as client:
        cursor = None
        while True:
            response = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                     "id": "guides/setup.md#0", "cursor": cursor, "maxBytes": 1})
            if response.status_code == 422:
                break
            assert response.status_code == 200
            cursor = response.json()["nextCursor"]
    assert response.json()["code"] == "DOCUMENTATION_INVALID_REQUEST"


def test_duplicate_headings_keep_distinct_read_ids(tmp_path: Path) -> None:
    root = tmp_path / "documentation"
    root.mkdir()
    text = "# Same\n\nfirst-unique-term\n\n# Same\n\nsecond-unique-term\n"
    raw = text.encode()
    corpus = {"schemaVersion": 1,
              "checksum": "sha256:" + hashlib.sha256(b"guide.md\0" + raw + b"\0").hexdigest(),
              "documents": [{"path": "guide.md", "title": "Same", "url": "/guide", "content": text,
                             "sha256": hashlib.sha256(raw).hexdigest()}],
              "sections": [{"id": f"guide.md#{start}", "path": "guide.md", "title": "Same",
                            "headingPath": ["Same"], "startByte": start, "endByte": end,
                            "searchText": text.encode()[start:end].decode()}
                           for start, end in [(0, raw.find(b"# Same", 1)), (raw.find(b"# Same", 1), len(raw))]],
              "elementPaths": {}}
    (root / "documentation.json").write_text(json.dumps(corpus), encoding="utf-8")
    build_index(root)
    with TestClient(create_app(tmp_path / "missing-private-package", root)) as client:
        checksum = client.get("/v1/documentation/manifest").json()["checksum"]
        results = client.post("/v1/documentation/search", json={"expectedChecksum": checksum,
                                   "query": "second-unique-term", "limit": 5}).json()["results"]
        read = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                 "id": "guide.md#" + str(raw.find(b"# Same", 1))}).json()
        first_page = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                 "id": "guide.md#0", "maxBytes": 8}).json()
        cross_section = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                    "id": read["id"], "cursor": first_page["nextCursor"]})
    assert results[0]["id"] == read["id"]
    assert "second-unique-term" in read["text"]
    assert cross_section.status_code == 400
    assert cross_section.json()["code"] == "DOCUMENTATION_INVALID_REQUEST"


def test_element_search_prefilters_before_ranking_and_falls_back_to_the_mapped_page(
    tmp_path: Path,
) -> None:
    root = tmp_path / "documentation"
    content = {f"guides/unrelated-{index}.md": "network " * 100 for index in range(25)}
    content["guides/target.md"] = "network target-operation details"
    write_corpus(root, content, {"target": "guides/target.md"})
    build_index(root)
    with TestClient(create_app(tmp_path / "missing-private-package", root)) as client:
        checksum = client.get("/v1/documentation/manifest").json()["checksum"]
        matching = client.post("/v1/documentation/search", json={"expectedChecksum": checksum,
                                     "query": "network", "elementTypes": ["target"], "limit": 1}).json()
        fallback = client.post("/v1/documentation/search", json={"expectedChecksum": checksum,
                                     "query": "no-such-token", "elementTypes": ["target"], "limit": 1}).json()
    assert matching["results"][0]["path"] == "guides/target.md"
    assert fallback["results"][0]["path"] == "guides/target.md"


def test_open_rejects_same_row_count_index_from_an_older_corpus(docs: Path, tmp_path: Path) -> None:
    manifest_path = docs / "documentation.json"
    corpus = json.loads(manifest_path.read_text(encoding="utf-8"))
    corpus["documents"][0]["content"] = corpus["documents"][0]["content"].replace(
        "purple-widget", "silver-widget"
    )
    raw = corpus["documents"][0]["content"].encode()
    corpus["documents"][0]["sha256"] = hashlib.sha256(raw).hexdigest()
    corpus["checksum"] = "sha256:" + hashlib.sha256(b"guides/setup.md\0" + raw + b"\0").hexdigest()
    corpus["sections"][0]["searchText"] = corpus["documents"][0]["content"]
    manifest_path.write_text(json.dumps(corpus), encoding="utf-8")
    with TestClient(create_app(tmp_path / "missing-private-package", docs)) as client:
        response = client.get("/v1/documentation/manifest")
    assert response.status_code == 503
    assert response.json()["code"] == "DOCUMENTATION_UNAVAILABLE"


def test_table_continuation_read_has_header_context_and_preserves_source_bytes(
    tmp_path: Path,
) -> None:
    root = tmp_path / "documentation"
    checksum, section_id = table_corpus(root)
    build_index(root)
    with TestClient(create_app(tmp_path / "missing-private-package", root)) as client:
        page = client.post("/v1/documentation/read", json={"expectedChecksum": checksum,
                                "id": section_id, "maxBytes": 64}).json()
    assert page["context"] == "| Name | Value |\n| --- | --- |"
    assert page["text"].startswith("| row-40 | value-40 |\n")
