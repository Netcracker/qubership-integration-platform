import fs from "fs";
import pth from "path";
import { marked } from "marked";
import elasticlunr from "elasticlunr";

class TocTreeNode {
  constructor(title, documentId) {
    this.title = title;
    this.documentId = documentId;
    this.children = [];
  }

  getChild = function (path) {
    if (!path || path.length === 0) {
      return this;
    }

    let child = this.children.find((child) => child.title === path[0]);
    if (!child) {
      child = new TocTreeNode(path[0]);
      this.children.push(child);
    }

    return child.getChild(path.slice(1));
  };
}

// Extract text content from markdown tokens
function extractTextFromTokens(tokens) {
  const textParts = [];

  for (const token of tokens) {
    if (token.type === 'text' || token.type === 'codespan') {
      textParts.push(token.text || token.raw);
    } else if (token.type === 'paragraph' || token.type === 'heading') {
      if (token.tokens) {
        textParts.push(extractTextFromTokens(token.tokens));
      } else if (token.text) {
        textParts.push(token.text);
      }
    } else if (token.type === 'list') {
      if (token.items) {
        for (const item of token.items) {
          if (item.tokens) {
            textParts.push(extractTextFromTokens(item.tokens));
          }
        }
      }
    } else if (token.type === 'table') {
      if (token.header) {
        for (const cell of token.header) {
          if (cell.tokens) {
            textParts.push(extractTextFromTokens(cell.tokens));
          } else if (cell.text) {
            textParts.push(cell.text);
          }
        }
      }
      if (token.rows) {
        for (const row of token.rows) {
          for (const cell of row) {
            if (cell.tokens) {
              textParts.push(extractTextFromTokens(cell.tokens));
            } else if (cell.text) {
              textParts.push(cell.text);
            }
          }
        }
      }
    } else if (token.type === 'code') {
      textParts.push(token.text);
    } else if (token.type === 'blockquote' && token.tokens) {
      textParts.push(extractTextFromTokens(token.tokens));
    } else if (token.type === 'strong' || token.type === 'em' || token.type === 'del') {
      if (token.tokens) {
        textParts.push(extractTextFromTokens(token.tokens));
      } else if (token.text) {
        textParts.push(token.text);
      }
    } else if (token.type === 'link' && token.tokens) {
      textParts.push(extractTextFromTokens(token.tokens));
    } else if (token.tokens) {
      textParts.push(extractTextFromTokens(token.tokens));
    }
  }

  return textParts.join('\n\n');
}

async function* walk(dir) {
  for await (const d of await fs.promises.opendir(dir)) {
    const entry = pth.join(dir, d.name);
    if (d.isDirectory()) yield* await walk(entry);
    else if (d.isFile()) yield entry;
  }
}

async function getSortedMarkdownDocuments(dir) {
  const paths = [];
  for await (const path of walk(dir)) {
    if (path.endsWith(".md")) {
      paths.push(path);
    }
  }
  paths.sort((p0, p1) => {
    const d0 = pth.dirname(p0);
    const d1 = pth.dirname(p1);
    const n0 = pth.basename(p0);
    const n1 = pth.basename(p1);
    return d0 < d1 ? -1 : d0 > d1 ? 1 : n0 < n1 ? -1 : n0 > n1 ? 1 : 0;
  });
  return paths;
}

async function buildTocTree(dir, paths) {
  const tree = new TocTreeNode("");
  paths.forEach((path, index) => {
    const relativePath = pth.relative(dir, path);
    const tocPath = extractTocPath(relativePath);
    tree.getChild(tocPath).documentId = index;
  });
  return tree;
}

function extractTocPath(p) {
  return pth
    .dirname(p)
    .split(pth.sep)
    .map((s) => convertDirNameToTocTitle(s));
}

function convertDirNameToTocTitle(s) {
  const tokens = s.split("__");
  return tokens.length > 1 ? tokens[1].replaceAll("_", " ") : s;
}

async function buildPaths(dir, pathes) {
  return pathes.map((path) => pth.relative(dir, path).replaceAll("\\", "/"));
}

async function buildNames(dir, pathes) {
  return pathes.map((path) => extractTocPath(pth.relative(dir, path)));
}

async function saveJson(obj, fileName) {
  const objData = JSON.stringify(obj);
  await fs.promises.writeFile(fileName, objData);
}

async function saveData(outDir, fileName, data, name) {
  const path = pth.resolve(outDir, fileName);
  console.log("Writing", name, "to", path, "...");
  return saveJson(data, path);
}

async function buildSearchIndex(dir, paths) {
  let index = elasticlunr(function () {
    this.addField("title");
    this.addField("body");
    this.setRef("id");
    this.saveDocument(true);
  });
  for (let i = 0; i < paths.length; ++i) {
    const path = paths[i];
    const relativePath = pth.relative(dir, path);
    const body = await fs.promises.readFile(path);
    let doc = {
      id: i,
      title: getDocumentTitle(relativePath),
      body: extractDocumentContent(body.toString()),
    };
    index.addDoc(doc);
  }
  return index;
}

function extractDocumentContent(markdownText) {
  const tokens = marked.lexer(markdownText);
  return extractTextFromTokens(tokens);
}

function getDocumentTitle(path) {
  return convertDirNameToTocTitle(pth.basename(pth.dirname(path)));
}

function parseCommandLine() {
  const args = process.argv.slice(2);
  if (args.length < 2) {
    console.error("Usage: build-doc-index <doc_dir> <out_dir>");
    process.exit(1);
  }
  return {
    _: args,
  };
}

function main() {
  const args = parseCommandLine();
  const docDir = args._[0];
  const outDir = args._[1];
  console.log("Documentation root directory:", docDir);
  console.log("Output directory:", outDir);

  getSortedMarkdownDocuments(docDir)
    .then((paths) =>
      Promise.all([
        buildPaths(docDir, paths).then((paths) =>
          saveData(outDir, "paths.json", paths, "document paths"),
        ),
        buildNames(docDir, paths).then((names) =>
          saveData(outDir, "names.json", names, "document names"),
        ),
        buildTocTree(docDir, paths).then((tree) =>
          saveData(outDir, "toc.json", tree, "table of content tree"),
        ),
        buildSearchIndex(docDir, paths).then((index) =>
          saveData(outDir, "search-index.json", index, "search index"),
        ),
      ]),
    )
    .catch((err) => console.log(err));
}

main();
