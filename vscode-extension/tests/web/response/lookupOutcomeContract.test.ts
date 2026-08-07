// The guard against the next site.
//
// A lookup by id has three outcomes, and the one that keeps getting lost is the third: a file the
// scan could not read. Six review rounds in a row each fixed one layer and left the layer above it
// collapsing the outcome back into a miss, so this walks the sources instead of trusting review.
//
// Two shapes lose it, and each has a rule here:
//
//   1. a lookup call caught by a `catch` that can answer — the shape that continues to a name of
//      lower precedence and serves a stale sibling. Multi-candidate lookups run through
//      `resolveFirstCandidate`, whose `onUnreadable` handler is not optional.
//   2. a loop that parses a file and swallows the failure — the same collapse spelled out by hand
//      over a folder listing, which is what `getApiSpecifications`, `getSpecificationModel`,
//      `getOperations` and `getOperationInfo` did until they were routed through
//      `resolveScannedEntities`. Extracting the `catch` into a helper does not hide it: a function
//      that swallows a parse failure counts as a parse wherever it is called.
//
// Both rules resolve a call to the declaration it actually reaches, through the TypeScript type
// checker rather than through the name at the call site. An alias, a renamed import, a destructured
// binding, a computed property and a call through a variable all resolve to the same declaration,
// so none of them hides a lookup. What the checker cannot see it says so about: a call whose callee
// is `any` falls back to the name it is spelled with, and `<file>#<function>` reporting is the only
// place spelling is trusted.
//
// A `catch` that always rethrows is not a swallow — it answers with nothing, so the outcome
// survives it. That is why the reporting handlers around a save or an import need no entry below.
// `.catch(...)` and `.then(onFulfilled, onRejected)` count as `catch`; a `finally` does not, because
// it answers with nothing either.
//
// What this cannot see: a call on a value typed `any`, where there is no signature to resolve and
// the name at the call site is all there is — `api[whicheverMethod](id)` on an `any` slips through.
// The last case below is the canary for that: it fails if the share of calls the checker cannot
// resolve grows.
//
// The allowlists are the decision this test exists to force: each entry states why catching there
// does not collapse the outcome, and a new site is a line someone has to write and defend.

import * as fs from "fs";
import * as path from "path";
import * as ts from "typescript";

/** Resolving *which file* an entity id owns, and reading a file a listing handed back by name. */
const LOOKUPS = new Set([
  "findFile",
  "findFiles",
  "findFileById",
  "findFileByNavigationPath",
  "findFileWithExtension",
  "findServiceFileById",
  "findServiceFiles",
  "findGroupFileById",
  "findModelFileById",
  "getMainService",
  "readListedServiceFile",
  // The two accessors every environment and import path resolves a service through.
  "getSystemById",
  "getRawServiceById",
  // The two candidate runners the contract is stated in.
  "resolveFirstCandidate",
  "resolveScannedEntities",
  // The folder scans that resolve a group or an API across its two names.
  "resolveGroupFiles",
  "resolveApiFiles",
]);

/** Reading one file's content. Caught in a loop, this is the hand-rolled candidate scan. */
const PARSERS = new Set(["parseFile", "parseContentFromFile"]);

/** `<file>#<enclosing function>` → why catching a lookup there does not collapse the outcome. */
const ALLOWED_LOOKUP_CATCH: Record<string, string> = {
  "response/serviceApiRead.ts#resolveServiceFileUri":
    "Falls back to the held uri only for a miss; rethrows UnreadableServiceFileError, whose sibling is that very uri.",
  "response/serviceApiRead.ts#readServiceIdentity":
    "Takes the id from the file name, and resolves no file — the caller's lookup still decides.",
  "api-services/ApiGroupService.ts#getApiGroupById":
    "Answers null for a miss and rethrows the refusal, so no read or write is redirected.",
  "api-services/ApiGroupService.ts#regenerateGroupApisSafely":
    "Rebuilds a derived list; it writes nothing on a failure and the next write heals it.",
  "api-services/SystemService.ts#getSystemById":
    "Rethrows the refusal; only a plain miss answers null.",
  "api-services/SystemService.ts#getRawServiceById":
    "Rethrows the refusal; only a plain miss answers null.",
  "api-services/EnvironmentService.ts#getEnvironmentsForSystem":
    "Rethrows the refusal; only a plain miss answers an empty list.",
  "api-services/SpecificationImportService.ts#runImport":
    "Reports the import as failed and writes nothing further; it resolves no second file.",
  "api-services/SpecificationImportService.ts#createEnvironmentForSpecificationGroup":
    "An environment is best-effort next to the imported specification; the failure is reported, no file is chosen.",
  "chainDiffEditor.ts#registerChainDiffMessageHandlers":
    "Shows the navigation failure to the user; the editor stays where it is.",
  "extension.ts#activate":
    "A command handler reporting to the user, with no second candidate to fall to.",
  "extension.ts#enrichWebview":
    "Sends the failure back to the webview as the response to that request.",
};

/** `<file>#<enclosing function>` → why a loop that swallows a parse there is not that collapse. */
const ALLOWED_PARSE_LOOP: Record<string, string> = {
  "response/file/fileApiImpl.ts#collectFiles":
    "The layer the outcome is created in: it records the file it could not read and the lookup above reports it.",
  "response/chainApiRead.ts#findChain":
    "Chains are stored under one name; the scan matches by id and no other name can stand in.",
  "response/chainApiRead.ts#getElementsByType":
    "Same: one name per chain, and the scan collects elements rather than resolving a file.",
  "qipExplorer.ts#getChains":
    "The tree walks the workspace itself, and a chain has one name.",
  "qipExplorer.ts#getChainChildren": "Same walk, one name per chain.",
  "qipExplorer.ts#findChainFilesRecursively": "Same walk, one name per chain.",
  "qipExplorer.ts#findServiceFilesRecursively":
    "Records the file it could not read; `dropUnreadableSiblings` then keeps its sibling off the tree.",
  "qipExplorer.ts#getServices":
    "Drives that walk, and drops every service whose file may be a sibling of one it could not read.",
  "extension.ts#collectServiceOwnedFiles":
    "A delete collecting what the service owns: a file it cannot read cannot be attributed, so it is left in place rather than deleted.",
  "extension.ts#collectServiceFileSiblings":
    "Same delete: an unreadable neighbour is not claimed as this service's sibling.",
  "extension.ts#deleteServiceWithRelatedFiles":
    "Deletes what that collection returned; a file it could not read was never claimed, so nothing is deleted in its place.",
  "response/serviceApiModify.ts#getSpecificationFilesByGroup":
    "Collects every file of the group for a delete — both names of one API — rather than resolving which one answers.",
  "response/serviceApiModify.ts#deleteSpecificationGroup":
    "Same delete: it re-reads the files it is about to remove, and skipping one removes nothing else in its place.",
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

const files = sourceFiles(SRC_ROOT);
const program = ts.createProgram(files, {
  target: ts.ScriptTarget.ES2020,
  module: ts.ModuleKind.Node16,
  moduleResolution: ts.ModuleResolutionKind.Node16,
  skipLibCheck: true,
  noEmit: true,
});
const checker = program.getTypeChecker();

function declarationName(declaration: ts.Node): string | undefined {
  const named = declaration as { name?: ts.Node };
  if (named.name && ts.isIdentifier(named.name)) {
    return named.name.text;
  }
  const parent = declaration.parent;
  if (
    parent &&
    (ts.isVariableDeclaration(parent) || ts.isPropertyDeclaration(parent)) &&
    ts.isIdentifier(parent.name)
  ) {
    return parent.name.text;
  }
  return undefined;
}

/** `<file>#<name>` for a declaration, or nothing for one that has no name to report. */
function keyOf(declaration: ts.Node): string | undefined {
  const name = declarationName(declaration);
  if (!name) {
    return undefined;
  }
  const fileName = declaration.getSourceFile().fileName;
  return fileName.startsWith(SRC_ROOT)
    ? `${path.relative(SRC_ROOT, fileName)}#${name}`
    : `${path.basename(fileName)}#${name}`;
}

/**
 * The declaration a call actually reaches. The resolved signature is what defeats every spelling:
 * an alias, a renamed import, a destructured binding and a computed property all resolve here.
 */
function resolveCallee(node: ts.CallExpression): ts.Node | undefined {
  const signature = checker.getResolvedSignature(node);
  if (signature?.declaration) {
    return signature.declaration;
  }
  let symbol = checker.getSymbolAtLocation(node.expression);
  if (symbol && symbol.flags & ts.SymbolFlags.Alias) {
    symbol = checker.getAliasedSymbol(symbol);
  }
  return symbol?.declarations?.[0];
}

/** The last resort for a callee the checker types as `any`: the name it is spelled with. */
function spelledName(node: ts.CallExpression): string {
  const callee = node.expression;
  if (ts.isPropertyAccessExpression(callee)) {
    return callee.name.text;
  }
  if (ts.isIdentifier(callee)) {
    return callee.text;
  }
  if (
    ts.isElementAccessExpression(callee) &&
    callee.argumentExpression &&
    ts.isStringLiteralLike(callee.argumentExpression)
  ) {
    return callee.argumentExpression.text;
  }
  return "";
}

type Call = { key?: string; name: string; node: ts.CallExpression };

function describeCall(node: ts.CallExpression): Call {
  const declaration = resolveCallee(node);
  return {
    key: declaration ? keyOf(declaration) : undefined,
    name: declaration
      ? (declarationName(declaration) ?? spelledName(node))
      : spelledName(node),
    node,
  };
}

/** A catch that always rethrows answers with nothing, so no outcome is collapsed there. */
function alwaysRethrows(handler: ts.CatchClause | ts.Expression): boolean {
  const body = ts.isCatchClause(handler)
    ? handler.block
    : (ts.isArrowFunction(handler) || ts.isFunctionExpression(handler)) &&
        ts.isBlock(handler.body)
      ? handler.body
      : undefined;
  const last = body?.statements[body.statements.length - 1];
  return !!last && ts.isThrowStatement(last);
}

/** The `try` or `.catch(` that can answer for this call, if any. */
function swallowedBy(node: ts.Node): ts.Node | undefined {
  for (let current = node.parent; current; current = current.parent) {
    if (
      ts.isTryStatement(current) &&
      current.tryBlock.getStart() <= node.getStart() &&
      node.getEnd() <= current.tryBlock.getEnd() &&
      current.catchClause &&
      !alwaysRethrows(current.catchClause)
    ) {
      return current;
    }
    if (
      ts.isCallExpression(current) &&
      ts.isPropertyAccessExpression(current.expression) &&
      current.expression.name.text === "catch" &&
      (!current.arguments[0] || !alwaysRethrows(current.arguments[0]))
    ) {
      return current;
    }
    // `.then(onFulfilled, onRejected)` is a `catch` spelled as an argument.
    if (
      ts.isCallExpression(current) &&
      ts.isPropertyAccessExpression(current.expression) &&
      current.expression.name.text === "then" &&
      current.arguments.length > 1 &&
      !alwaysRethrows(current.arguments[1])
    ) {
      return current;
    }
  }
  return undefined;
}

/** The nearest enclosing function, named through the variable or property it is bound to. */
function enclosingFunctionKey(node: ts.Node): string {
  for (let current = node.parent; current; current = current.parent) {
    if (
      ts.isFunctionDeclaration(current) ||
      ts.isMethodDeclaration(current) ||
      ts.isGetAccessorDeclaration(current) ||
      ts.isConstructorDeclaration(current) ||
      ts.isArrowFunction(current) ||
      ts.isFunctionExpression(current)
    ) {
      const key = keyOf(current);
      if (key) {
        return key;
      }
    }
  }
  return `${path.relative(SRC_ROOT, node.getSourceFile().fileName)}#<top level>`;
}

const callsByFunction = new Map<string, Call[]>();
const allCalls: { call: Call; enclosing: string; line: number }[] = [];

for (const fileName of files) {
  const source = program.getSourceFile(fileName)!;
  const visit = (node: ts.Node): void => {
    if (ts.isCallExpression(node)) {
      const call = describeCall(node);
      const enclosing = enclosingFunctionKey(node);
      callsByFunction.set(enclosing, [
        ...(callsByFunction.get(enclosing) ?? []),
        call,
      ]);
      allCalls.push({
        call,
        enclosing,
        line: source.getLineAndCharacterOfPosition(node.getStart()).line + 1,
      });
    }
    ts.forEachChild(node, visit);
  };
  ts.forEachChild(source, visit);
}

/**
 * Grows a seed set of functions over the call graph: a function that calls a member of the set
 * without swallowing it belongs to the set too. This is what makes a wrapper no hiding place —
 * the outcome a wrapper passes on is the wrapper's own.
 */
function closeOver(seed: (call: Call) => boolean): Set<string> {
  const members = new Set<string>();
  for (const [key, calls] of callsByFunction) {
    if (calls.some((call) => seed(call) && !swallowedBy(call.node))) {
      members.add(key);
    }
  }
  for (let changed = true; changed; ) {
    changed = false;
    for (const [key, calls] of callsByFunction) {
      if (members.has(key)) {
        continue;
      }
      if (
        calls.some(
          (call) =>
            call.key && members.has(call.key) && !swallowedBy(call.node),
        )
      ) {
        members.add(key);
        changed = true;
      }
    }
  }
  return members;
}

/** Every function that resolves which file an id owns, wrappers included. */
const lookupFunctions = closeOver((call) => LOOKUPS.has(call.name));

/** Every function that reads a file and swallows the failure, wrappers included. */
const parseSwallowers = (() => {
  const members = new Set<string>();
  for (const [key, calls] of callsByFunction) {
    if (
      calls.some((call) => PARSERS.has(call.name) && swallowedBy(call.node))
    ) {
      members.add(key);
    }
  }
  for (let changed = true; changed; ) {
    changed = false;
    for (const [key, calls] of callsByFunction) {
      if (members.has(key)) {
        continue;
      }
      if (calls.some((call) => call.key && members.has(call.key))) {
        members.add(key);
        changed = true;
      }
    }
  }
  return members;
})();

const LOOP_METHODS = new Set([
  "map",
  "forEach",
  "filter",
  "flatMap",
  "some",
  "every",
  "find",
  "reduce",
]);

function isLoop(node: ts.Node): boolean {
  return (
    ts.isForStatement(node) ||
    ts.isForOfStatement(node) ||
    ts.isForInStatement(node) ||
    ts.isWhileStatement(node) ||
    ts.isDoStatement(node) ||
    (ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(node.expression) &&
      LOOP_METHODS.has(node.expression.name.text))
  );
}

type Site = { key: string; line: number };

/** Rule 1: a lookup whose failure a `catch` can answer for. */
function swallowingLookupSites(): Site[] {
  return allCalls
    .filter(
      ({ call }) =>
        (LOOKUPS.has(call.name) ||
          (call.key && lookupFunctions.has(call.key))) &&
        swallowedBy(call.node),
    )
    .map(({ enclosing, line }) => ({ key: enclosing, line }));
}

/** Rule 2: a loop that reads candidate files and swallows what it cannot read. */
function swallowingLoopSites(): Site[] {
  const sites: Site[] = [];
  for (const fileName of files) {
    const source = program.getSourceFile(fileName)!;
    const visit = (node: ts.Node): void => {
      if (isLoop(node)) {
        let swallows = false;
        const scan = (child: ts.Node): void => {
          if (swallows || !ts.isCallExpression(child)) {
            ts.forEachChild(child, scan);
            return;
          }
          const call = describeCall(child);
          swallows =
            (PARSERS.has(call.name) && !!swallowedBy(child)) ||
            (!!call.key && parseSwallowers.has(call.key));
          ts.forEachChild(child, scan);
        };
        ts.forEachChild(node, scan);
        if (swallows) {
          sites.push({
            key: enclosingFunctionKey(node),
            line:
              source.getLineAndCharacterOfPosition(node.getStart()).line + 1,
          });
        }
      }
      ts.forEachChild(node, visit);
    };
    ts.forEachChild(source, visit);
  }
  return sites;
}

function unlisted(sites: Site[], allowed: Record<string, string>): string[] {
  return sites
    .filter((site) => !(site.key in allowed))
    .map((site) => `${site.key} (line ${site.line})`);
}

function stale(sites: Site[], allowed: Record<string, string>): string[] {
  const keys = new Set(sites.map((site) => site.key));
  return Object.keys(allowed).filter((key) => !keys.has(key));
}

describe("no lookup swallows the unreadable outcome", () => {
  const lookupSites = swallowingLookupSites();
  const loopSites = swallowingLoopSites();

  it("catches a lookup only where the allowlist says why", () => {
    expect(unlisted(lookupSites, ALLOWED_LOOKUP_CATCH)).toEqual([]);
  });

  it("skips a file it cannot read, inside a candidate loop, only where the allowlist says why", () => {
    expect(unlisted(loopSites, ALLOWED_PARSE_LOOP)).toEqual([]);
  });

  it("keeps both allowlists free of entries no site needs", () => {
    expect(stale(lookupSites, ALLOWED_LOOKUP_CATCH)).toEqual([]);
    expect(stale(loopSites, ALLOWED_PARSE_LOOP)).toEqual([]);
  });

  // The checker is what makes a spelling irrelevant, and it only works while the sources type. A
  // callee it cannot resolve falls back to its spelling, which an alias would slip past.
  it("resolves nearly every call to a declaration rather than to a spelling", () => {
    const unresolved = allCalls.filter(({ call }) => !call.key).length;

    expect(unresolved / allCalls.length).toBeLessThan(0.1);
  });
});
