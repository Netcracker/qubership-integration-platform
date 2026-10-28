import fs from "fs";
import path from "path";
import { marked } from "marked";
import { createHash } from "node:crypto";
import { pathToFileURL } from "node:url";
import { buildElementTypeMapping } from "./element-documentation-mapping.mjs";

class TocTreeNode {
  constructor(title, documentId) {
    this.title = title;
    this.documentId = documentId;
    this.children = [];
  }

  getChild(tocPath) {
    if (!tocPath || tocPath.length === 0) {
      return this;
    }

    let child = this.children.find((c) => c.title === tocPath[0]);
    if (!child) {
      child = new TocTreeNode(tocPath[0]);
      this.children.push(child);
    }

    return child.getChild(tocPath.slice(1));
  }
}

function extractTextFromTokens(tokens) {
  const parts = [];

  for (const token of tokens) {
    if (token.tokens) {
      parts.push(extractTextFromTokens(token.tokens));
    } else if (
      token.type === "text" ||
      token.type === "codespan" ||
      token.type === "code"
    ) {
      parts.push(token.text || token.raw);
    } else if (token.text) {
      parts.push(token.text);
    }

    // Table and list have nested structures outside .tokens
    if (token.type === "list" && token.items) {
      for (const item of token.items) {
        if (item.tokens) parts.push(extractTextFromTokens(item.tokens));
      }
    }
    if (token.type === "table") {
      for (const cell of token.header || []) {
        if (cell.tokens) parts.push(extractTextFromTokens(cell.tokens));
        else if (cell.text) parts.push(cell.text);
      }
      for (const row of token.rows || []) {
        for (const cell of row) {
          if (cell.tokens) parts.push(extractTextFromTokens(cell.tokens));
          else if (cell.text) parts.push(cell.text);
        }
      }
    }
  }

  return parts.join("\n\n");
}

async function* walk(dir) {
  for await (const entry of await fs.promises.opendir(dir)) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(fullPath);
    else if (entry.isFile()) yield fullPath;
  }
}

async function getSortedMarkdownDocuments(dir) {
  const files = [];
  for await (const filePath of walk(dir)) {
    if (filePath.endsWith(".md")) {
      files.push(filePath);
    }
  }
  files.sort((a, b) => {
    const da = path.dirname(a);
    const db = path.dirname(b);
    if (da !== db) return da < db ? -1 : 1;
    return path.basename(a) < path.basename(b) ? -1 : 1;
  });
  return files;
}

function extractTocPath(relativePath) {
  return path
    .dirname(relativePath)
    .split(path.sep)
    .map(convertDirNameToTocTitle);
}

function convertDirNameToTocTitle(dirName) {
  const parts = dirName.split("__");
  return parts.length > 1 ? parts[1].replaceAll("_", " ") : dirName;
}

function buildTocTree(dir, paths) {
  const tree = new TocTreeNode("");
  paths.forEach((filePath, index) => {
    const tocPath = extractTocPath(path.relative(dir, filePath));
    tree.getChild(tocPath).documentId = index;
  });
  return tree;
}

function buildPaths(dir, paths) {
  return paths.map((filePath) =>
    path.relative(dir, filePath).replaceAll("\\", "/"),
  );
}

function buildNames(dir, paths) {
  return paths.map((filePath) => extractTocPath(path.relative(dir, filePath)));
}

async function buildSearchIndex(dir, paths, parsedDocuments) {
  const documents = [];
  for (let i = 0; i < paths.length; i++) {
    const content = await fs.promises.readFile(paths[i], "utf-8");
    const tokens = marked.lexer(content);
    parsedDocuments.set(paths[i], { content, tokens });
    documents.push({
      id: i,
      title: convertDirNameToTocTitle(path.basename(path.dirname(paths[i]))),
      body: extractTextFromTokens(tokens),
    });
  }
  return { documents };
}

function markdownUrl(relativePath) {
  return `/doc/${relativePath.replace(/\.md$/, "").split("/").map(encodeURIComponent).join("/")}`;
}

function normalizeMarkdown(content) {
  let normalized = "";
  const originalOffsets = [0];
  for (let index = 0; index < content.length; ) {
    if (content[index] === "\r") {
      index += content[index + 1] === "\n" ? 2 : 1;
      normalized += "\n";
    } else {
      normalized += content[index++];
    }
    originalOffsets.push(index);
  }
  return { normalized, originalOffsets };
}

function createSections(relativePath, content, tokens) {
  const { normalized, originalOffsets } = normalizeMarkdown(content);
  const originalOffset = (offset) => originalOffsets[offset];
  const blocks = [];
  let cursor = 0;
  for (const token of tokens) {
    const normalizedStart = normalized.indexOf(token.raw, cursor);
    const normalizedEnd = normalizedStart + token.raw.length;
    const offset = originalOffset(normalizedStart);
    if (normalizedStart < 0 || offset === undefined) continue;
    blocks.push({
      token,
      start: offset,
      end: originalOffset(normalizedEnd),
      normalizedStart,
    });
    cursor = normalizedEnd;
  }
  const headings = [];
  let headingPath = [];
  for (const block of blocks) {
    if (block.token.type === "heading") {
      headingPath = headingPath.slice(0, block.token.depth - 1);
      headingPath[block.token.depth - 1] = block.token.text;
      headings.push({
        start: block.start,
        headingPath: headingPath.filter(Boolean),
      });
    }
  }
  const tableSplits = [];
  for (const block of blocks.filter(
    (item) => item.token.type === "table" && item.token.rows.length > 40,
  )) {
    const lines = block.token.raw.match(/[^\n]*\n|[^\n]+$/g) || [];
    let relativeOffset = 0;
    for (let row = 0; row < lines.length; row++) {
      if (row > 2 && (row - 2) % 40 === 0)
        tableSplits.push(
          originalOffset(block.normalizedStart + relativeOffset),
        );
      relativeOffset += lines[row].length;
    }
  }
  const starts = [
    0,
    ...headings.map((heading) => heading.start),
    ...tableSplits,
  ]
    .sort((a, b) => a - b)
    .filter((value, index, all) => index === 0 || value !== all[index - 1]);
  return starts.map((start, index) => {
    const end = index + 1 < starts.length ? starts[index + 1] : content.length;
    const firstHeading = [...headings]
      .reverse()
      .find((heading) => heading.start <= start);
    const bodyTokens = blocks
      .filter((block) => block.start >= start && block.start < end)
      .map((block) => block.token);
    const raw = content.slice(start, end);
    const continuationTable = blocks.find(
      (block) =>
        block.token.type === "table" &&
        block.start < start &&
        start < block.end,
    );
    const tableHeader = continuationTable
      ? (continuationTable.token.header || [])
          .map((cell) => cell.text)
          .join("\t")
      : "";
    return {
      id: `${relativePath}#${Buffer.byteLength(content.slice(0, start), "utf8")}`,
      path: relativePath,
      title:
        firstHeading?.headingPath.at(-1) ||
        path.basename(path.dirname(relativePath)),
      headingPath: firstHeading?.headingPath || [],
      startByte: Buffer.byteLength(content.slice(0, start), "utf8"),
      endByte: Buffer.byteLength(content.slice(0, end), "utf8"),
      searchText:
        `${tableHeader}\n\n${raw.trim()}\n\n${extractTextFromTokens(bodyTokens)}`.trim(),
    };
  });
}

export async function buildDocumentationCorpus(
  docDir,
  paths,
  parsedDocuments = new Map(),
) {
  paths ??= await getSortedMarkdownDocuments(docDir);
  paths = [...paths].sort((left, right) => {
    const a = path.relative(docDir, left).replaceAll("\\", "/");
    const b = path.relative(docDir, right).replaceAll("\\", "/");
    return a < b ? -1 : a > b ? 1 : 0;
  });
  const documents = [];
  const sections = [];
  const hash = createHash("sha256");
  for (const filePath of paths) {
    const relativePath = path.relative(docDir, filePath).replaceAll("\\", "/");
    let parsed = parsedDocuments.get(filePath);
    if (!parsed) {
      const content = await fs.promises.readFile(filePath, "utf8");
      parsed = { content, tokens: marked.lexer(content) };
    }
    const { content, tokens } = parsed;
    const title =
      tokens.find((token) => token.type === "heading")?.text ||
      path.basename(path.dirname(relativePath));
    documents.push({
      path: relativePath,
      title,
      url: markdownUrl(relativePath),
      content,
      sha256: createHash("sha256").update(content).digest("hex"),
    });
    sections.push(...createSections(relativePath, content, tokens));
    hash.update(relativePath).update("\0").update(content).update("\0");
  }
  const elementMapping = buildElementTypeMapping(
    documents.map((document) => document.path),
  );
  const routes = new Map(
    documents.map((document) => [
      `/doc/${document.path.replace(".md", "")}`,
      document.path,
    ]),
  );
  const elementPaths = Object.fromEntries(
    Object.entries(elementMapping).flatMap(([type, url]) =>
      routes.has(url) ? [[type, routes.get(url)]] : [],
    ),
  );
  return {
    schemaVersion: 1,
    checksum: `sha256:${hash.digest("hex")}`,
    documents,
    sections,
    elementPaths,
  };
}

async function saveData(outDir, fileName, data, label) {
  const filePath = path.resolve(outDir, fileName);
  console.log("Writing", label, "to", filePath, "...");
  await fs.promises.writeFile(filePath, JSON.stringify(data));
}

async function main() {
  const [docDir, outDir] = process.argv.slice(2);
  if (!docDir || !outDir) {
    console.error("Usage: build-doc-index <doc_dir> <out_dir>");
    process.exit(1);
  }

  console.log("Documentation root directory:", docDir);
  console.log("Output directory:", outDir);

  fs.mkdirSync(outDir, { recursive: true });
  const paths = await getSortedMarkdownDocuments(docDir);
  const parsedDocuments = new Map();
  const searchIndex = await buildSearchIndex(docDir, paths, parsedDocuments);
  const documentation = await buildDocumentationCorpus(
    docDir,
    paths,
    parsedDocuments,
  );

  await Promise.all([
    saveData(outDir, "paths.json", buildPaths(docDir, paths), "document paths"),
    saveData(outDir, "names.json", buildNames(docDir, paths), "document names"),
    saveData(
      outDir,
      "toc.json",
      buildTocTree(docDir, paths),
      "table of content tree",
    ),
    saveData(outDir, "search-index.json", searchIndex, "search index"),
    saveData(
      outDir,
      "documentation.json",
      documentation,
      "documentation corpus",
    ),
  ]);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
