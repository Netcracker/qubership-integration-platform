const fs = require("fs");
const pth = require("path");
const marked = require("marked");
const elasticlunr = require("elasticlunr");

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

class IndexableContentExtractor {
  constructor() {
    this.sep = "\n\n";
  }

  code = function (code) {
    return code + this.sep;
  };
  blockquote = function (quote) {
    return quote + this.sep;
  };
  html = function (html) {
    return html + this.sep;
  };
  heading = function (text) {
    return text + this.sep;
  };
  hr = function () {
    return "";
  };
  list = function (body) {
    return body;
  };
  listitem = function (text) {
    return text + this.sep;
  };
  checkbox = function () {
    return "";
  };
  paragraph = function (text) {
    return text + this.sep;
  };
  table = function (header, body) {
    return header + this.sep + body + this.sep;
  };
  tablerow = function (content) {
    return content + this.sep;
  };
  tablecell = function (content) {
    return content + this.sep;
  };
  strong = function (text) {
    return text;
  };
  em = function (text) {
    return text;
  };
  codespan = function (text) {
    return text + this.sep;
  };
  br = function () {
    return " ";
  };
  del = function (text) {
    return text;
  };
  link = function (href, title, text) {
    return text;
  };
  image = function (href, title, text) {
    return text;
  };
  text = function (text) {
    return text;
  };
  space = function () {
    return " ";
  };
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
  let renderer = new IndexableContentExtractor();
  return marked.parse(markdownText, { renderer });
}

function getDocumentTitle(path) {
  return convertDirNameToTocTitle(pth.basename(pth.dirname(path)));
}

function parseCommandLine() {
  return require("yargs")
    .scriptName("build-doc-index")
    .usage("$0 <doc_dir> <out_dir>")
    .positional("doc_dir", {
      description: "Documentation root directory",
      normalize: true,
      type: "string",
    })
    .positional("out_dir", {
      description: "Output directory",
      normalize: true,
      type: "string",
    })
    .help()
    .parse();
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
