// The guard against a sixth site.
//
// A lookup by id has three outcomes, and the one that keeps getting lost is the third: a file the
// scan could not read. Five review rounds in a row each fixed one layer and left the layer above it
// collapsing the outcome back into a miss, so this walks the sources instead of trusting review.
//
// The rule: a lookup call may not sit inside a `try`/`catch` or behind `.catch(` — that is the shape
// that continues to a name of lower precedence and answers from a stale sibling. Multi-candidate
// lookups run through `resolveFirstCandidate`, whose `onUnreadable` handler is not optional. The
// allowlist below is for the sites that catch on purpose; each one states why, and adding to it is
// the decision this test exists to force.

import * as fs from "fs";
import * as path from "path";
import * as ts from "typescript";

/** The lookups whose failure carries the unreadable outcome. */
const LOOKUPS = [
  "findFile",
  "findFiles",
  "findFileById",
  "findFileByNavigationPath",
  "findFileWithExtension",
  "findServiceFileById",
  "findServiceFiles",
  "findGroupFileById",
  "findModelFileById",
  // The read of a file a listing handed back by name, where the same collapse shows up as the
  // sibling being listed in its place.
  "getMainService",
  "readListedServiceFile",
  // The two accessors every environment and import path resolves a service through.
  "getSystemById",
  "getRawServiceById",
];

/** `<file>#<enclosing function>` → why catching there does not collapse the outcome. */
const ALLOWED: Record<string, string> = {
  "response/file/fileApiImpl.ts#findFileWithExtension":
    "Re-raises the convention file as unreadable when the scan reported a plain miss.",
  "response/file/serviceFileLookup.ts#readListedServiceFile":
    "Turns the parser's own failure into UnreadableServiceFileError naming the listed file.",
  "response/serviceApiRead.ts#resolveServiceFileUri":
    "Falls back to the held uri only for a miss; rethrows UnreadableServiceFileError, whose sibling is that very uri.",
  "response/serviceApiRead.ts#readServiceIdentity":
    "Takes the id from the file name, and resolves no file — the caller's lookup still decides.",
  "api-services/ApiGroupService.ts#getApiGroupById":
    "Answers null, never another group's file, so no read or write is redirected.",
  "api-services/SystemService.ts#getSystemById":
    "Rethrows the refusal; only a plain miss answers null.",
  "api-services/SystemService.ts#getRawServiceById":
    "Rethrows the refusal; only a plain miss answers null.",
  "api-services/SystemService.ts#saveSystem": "Logs and rethrows unchanged.",
  "api-services/EnvironmentService.ts#getEnvironmentsForSystem":
    "Rethrows the refusal; only a plain miss answers an empty list.",
  "api-services/EnvironmentService.ts#createEnvironment":
    "Rewraps every failure, the refusal's message included, and writes nothing on it.",
  "api-services/EnvironmentService.ts#updateEnvironment":
    "Rewraps every failure, the refusal's message included, and writes nothing on it.",
  "api-services/EnvironmentService.ts#deleteEnvironment":
    "Rewraps every failure, the refusal's message included, and writes nothing on it.",
};

const SRC_ROOT = path.resolve(__dirname, "../../../src/web");

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "test" ? [] : sourceFiles(full);
    }
    return entry.isFile() &&
      entry.name.endsWith(".ts") &&
      !entry.name.endsWith(".test.ts")
      ? [full]
      : [];
  });
}

function calleeName(node: ts.CallExpression): string {
  const callee = node.expression;
  if (ts.isPropertyAccessExpression(callee)) {
    return callee.name.text;
  }
  return ts.isIdentifier(callee) ? callee.text : "";
}

function containsLookupCall(node: ts.Node): boolean {
  let found = false;
  const visit = (child: ts.Node): void => {
    if (found) {
      return;
    }
    if (ts.isCallExpression(child) && LOOKUPS.includes(calleeName(child))) {
      found = true;
      return;
    }
    ts.forEachChild(child, visit);
  };
  ts.forEachChild(node, visit);
  return found;
}

/** The nearest named function, method or arrow-bound property around the node. */
function enclosingName(node: ts.Node): string {
  for (let current = node.parent; current; current = current.parent) {
    if (
      (ts.isFunctionDeclaration(current) ||
        ts.isMethodDeclaration(current) ||
        ts.isPropertyDeclaration(current) ||
        ts.isVariableDeclaration(current)) &&
      current.name &&
      ts.isIdentifier(current.name)
    ) {
      return current.name.text;
    }
  }
  return "<top level>";
}

type Site = { key: string; line: number };

function catchingSites(filePath: string): Site[] {
  const source = ts.createSourceFile(
    filePath,
    fs.readFileSync(filePath, "utf8"),
    ts.ScriptTarget.ES2020,
    true,
  );
  const relative = path.relative(SRC_ROOT, filePath);
  const sites: Site[] = [];

  const visit = (node: ts.Node): void => {
    const swallows =
      (ts.isTryStatement(node) && containsLookupCall(node.tryBlock)) ||
      (ts.isCallExpression(node) &&
        calleeName(node) === "catch" &&
        containsLookupCall(node.expression));
    if (swallows) {
      sites.push({
        key: `${relative}#${enclosingName(node)}`,
        line: source.getLineAndCharacterOfPosition(node.getStart()).line + 1,
      });
    }
    ts.forEachChild(node, visit);
  };
  ts.forEachChild(source, visit);
  return sites;
}

describe("no lookup swallows the unreadable outcome", () => {
  const sites = sourceFiles(SRC_ROOT).flatMap(catchingSites);

  it("catches a lookup only where the allowlist says why", () => {
    const unlisted = sites
      .filter((site) => !(site.key in ALLOWED))
      .map((site) => `${site.key} (line ${site.line})`);

    expect(unlisted).toEqual([]);
  });

  it("keeps the allowlist free of entries no site needs", () => {
    const keys = new Set(sites.map((site) => site.key));

    expect(Object.keys(ALLOWED).filter((key) => !keys.has(key))).toEqual([]);
  });
});
