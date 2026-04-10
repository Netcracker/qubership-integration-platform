import fs from "fs";
import path from "path";
import { marked } from "marked";

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

async function buildSearchIndex(dir, paths) {
  const documents = [];
  for (let i = 0; i < paths.length; i++) {
    const content = await fs.promises.readFile(paths[i], "utf-8");
    documents.push({
      id: i,
      title: convertDirNameToTocTitle(path.basename(path.dirname(paths[i]))),
      body: extractTextFromTokens(marked.lexer(content)),
    });
  }
  return { documents };
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
  const searchIndex = await buildSearchIndex(docDir, paths);

  await Promise.all([
    saveData(outDir, "paths.json", buildPaths(docDir, paths), "document paths"),
    saveData(outDir, "names.json", buildNames(docDir, paths), "document names"),
    saveData(outDir, "toc.json", buildTocTree(docDir, paths), "table of content tree"),
    saveData(outDir, "search-index.json", searchIndex, "search index"),
  ]);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
