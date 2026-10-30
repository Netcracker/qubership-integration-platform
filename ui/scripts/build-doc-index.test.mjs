import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { buildDocumentationCorpus } from "./build-doc-index.mjs";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

test("builds stable byte-ranged sections and preserves Markdown content", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "cip-corpus-"));
  try {
    const source =
      "Preamble ☃\r\n\r\n# Guide\r\n\r\n```md\r\n# not a heading\r\n```\r\n\r\n## Table\r\n\r\n| Name | Value |\r\n| --- | --- |\r\n| alpha | one |\r\n| beta | two |\r\n";
    await fs.mkdir(path.join(root, "QIP_Elements_Library/1__sample"), {
      recursive: true,
    });
    await fs.writeFile(
      path.join(root, "QIP_Elements_Library/1__sample/readme.md"),
      source,
    );
    await fs.mkdir(path.join(root, "guides"), { recursive: true });
    await fs.writeFile(path.join(root, "guides/a b&c.md"), "Guide");
    const first = await buildDocumentationCorpus(root);
    const second = await buildDocumentationCorpus(root);
    assert.deepStrictEqual(Object.keys(first).sort(), [
      "checksum",
      "documents",
      "elementPaths",
      "schemaVersion",
      "sections",
    ]);
    assert.strictEqual(first.checksum, second.checksum);
    assert.match(first.checksum, /^sha256:[a-f0-9]{64}$/);
    assert.deepStrictEqual(Object.keys(first.documents[0]).sort(), [
      "content",
      "path",
      "sha256",
      "title",
      "url",
    ]);
    assert.deepStrictEqual(Object.keys(first.sections[0]).sort(), [
      "endByte",
      "headingPath",
      "id",
      "path",
      "searchText",
      "startByte",
      "title",
    ]);
    assert.strictEqual(first.documents[0].content, source);
    assert.strictEqual(
      first.elementPaths.sample,
      "QIP_Elements_Library/1__sample/readme.md",
    );
    assert.strictEqual(first.documents[1].url, "/doc/guides/a%20b%26c");
    const documentSections = first.sections.filter(
      (section) => section.path === first.documents[0].path,
    );
    assert.strictEqual(documentSections[0].startByte, 0);
    assert.strictEqual(
      documentSections.at(-1).endByte,
      Buffer.byteLength(source),
    );
    assert.deepStrictEqual(
      documentSections.slice(1).map((section) => section.startByte),
      documentSections.slice(0, -1).map((section) => section.endByte),
    );
    assert.deepStrictEqual(
      documentSections.map((section) => section.headingPath),
      [[], ["Guide"], ["Guide", "Table"]],
    );
    for (const section of documentSections) {
      const bytes = Buffer.from(source).subarray(
        section.startByte,
        section.endByte,
      );
      assert.notStrictEqual(bytes.length, 0);
      assert.deepStrictEqual(Buffer.from(bytes.toString("utf8")), bytes);
    }
    assert.match(
      documentSections.map((section) => section.searchText).join("\n"),
      /not a heading/,
    );
    assert.match(
      documentSections.map((section) => section.searchText).join("\n"),
      /alpha/,
    );
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("writes documentation.json without changing existing CLI indexes", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "cip-index-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const docs = path.join(root, "docs");
  const out = path.join(root, "out");
  await fs.mkdir(docs);
  await fs.writeFile(path.join(docs, "guide.md"), "# Guide\n\nText.");
  const script = fileURLToPath(
    new URL("./build-doc-index.mjs", import.meta.url),
  );
  const result = spawnSync(process.execPath, [script, docs, out], {
    encoding: "utf8",
  });
  assert.strictEqual(result.status, 0, result.stderr);
  const names = (await fs.readdir(out)).sort();
  assert.deepStrictEqual(names, [
    "documentation.json",
    "names.json",
    "paths.json",
    "search-index.json",
    "toc.json",
  ]);
});

test("splits large Markdown tables into searchable row groups with their headers", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "cip-large-table-"));
  try {
    const source = `# Operators\n\n| Name | Meaning |\n| --- | --- |\n${Array.from({ length: 81 }, (_, index) => `| op${index} | meaning${index} |`).join("\n")}\n`;
    await fs.writeFile(path.join(root, "operators.md"), source);
    const corpus = await buildDocumentationCorpus(root);
    assert.strictEqual(corpus.sections.length, 3);
    assert.ok(
      corpus.sections
        .slice(1)
        .every((section) => section.searchText.includes("Name\tMeaning")),
    );
    assert.strictEqual(corpus.sections[0].startByte, 0);
    assert.strictEqual(
      corpus.sections.at(-1).endByte,
      Buffer.byteLength(source),
    );
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});
