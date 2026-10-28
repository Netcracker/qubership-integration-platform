/**
 * The message registry: one row per message type of the webview-extension bridge.
 *
 * A row is `covered` while a passing `vscode` case recorded the type answered without an error,
 * `not-covered` with a reason otherwise, and `unhandled` for a type the UI sends and the extension
 * has no handler for. `specs/harness/vscode-messages.spec.ts` reads the rows against the extension
 * and UI sources, and `reconcileMessages` reads them against the run, in both directions.
 *
 * The module loads under `node --experimental-strip-types` in `reconcile.mjs`, so its runtime
 * imports name `.ts` files that import nothing at runtime themselves.
 */
import { eachSpec, proves, runFilters, type JsonReport } from "./elements.ts";
import { VSCODE_MESSAGE_ANNOTATION } from "../support/vscode-messages.ts";

export type MessageStatus = "covered" | "not-covered" | "unhandled";

export interface MessageEntry {
  message: string;
  status: MessageStatus;
  /** Required unless the row is `covered`. */
  reason?: string;
}

/** The reason every handler with no sender in `ui/src` carries; the harness reads its prefix. */
export const NO_UI_SENDER = "no UI sender";

const R_SERVICE = "no case opens the service editor yet";
const R_CONTEXT_MCP = "no case opens the context service or MCP service editor yet";
const R_1214 = "#1214: the extension has no handler, so the UI receives an answer with no payload";

const covered = (message: string): MessageEntry => ({ message, status: "covered" });
const notCovered = (reason: string, ...messages: string[]): MessageEntry[] =>
  messages.map((message) => ({ message, status: "not-covered", reason }));

export const messageRegistry: MessageEntry[] = [
  // specs/vscode/smoke.spec.ts
  covered("startup"),
  covered("navigate"),
  covered("getChain"),
  covered("getLibrary"),

  // specs/vscode/chain-editor.spec.ts
  covered("getElements"),
  covered("getLibraryElementByType"),
  covered("createElement"),
  covered("createConnection"),
  covered("deleteElements"),
  covered("deleteConnections"),
  covered("groupElements"),
  covered("ungroupElements"),
  covered("cloneElements"),
  covered("transferElement"),
  {
    message: "getConnections",
    status: "not-covered",
    reason: "no screen calls api.getConnections: the chain editor reads the dependencies from getChain",
  },
  // `openChainInNewTab` and `navigateInNewTab` count only through `sentMessage`: the extension posts no answer.
  covered("findChainByElementId"),
  covered("getElementsByType"),
  covered("openChainInNewTab"),
  covered("navigateInNewTab"),

  // specs/vscode/chain-properties.spec.ts
  covered("updateChain"),
  covered("moveChain"),
  covered("getMaskedFields"),
  covered("createMaskedField"),
  covered("updateMaskedField"),
  covered("deleteMaskedFields"),

  // specs/vscode/element-forms.spec.ts
  covered("updateElement"),
  covered("getServices"),
  covered("getService"),
  covered("getEnvironments"),
  covered("getEnvironment"),
  covered("getApiSpecifications"),
  covered("getLatestApiSpecification"),
  covered("getOperations"),
  covered("getOperationInfo"),
  covered("getContextServices"),
  covered("getMcpServices"),

  // specs/vscode/chain-diff.spec.ts; `navigateComparedDocumentInNewTab` counts only through `sentMessage`.
  covered("comparedDocumentsRequest"),
  covered("navigateComparedDocumentInNewTab"),

  // specs/vscode/export-images.spec.ts
  covered("saveExportedImage"),
  covered("exportImagesProgress"),
  covered("exportImagesComplete"),
  covered("exportImagesItemFailed"),
  covered("exportImagesItemWarning"),

  ...notCovered(
    R_SERVICE,
    "updateService",
    "createService",
    "createEnvironment",
    "updateEnvironment",
    "deleteEnvironment",
    "getSpecificationModel",
    "updateApiSpecificationGroup",
    "updateSpecificationModel",
    "deprecateModel",
    "deleteSpecificationGroup",
    "deleteSpecificationModel",
    "importSpecificationGroup",
    "importSpecification",
    "getImportSpecificationResult",
    "getSpecApiFiles",
    "readSpecificationFileContent",
    "navigateToOperations",
    "navigateToSpecifications",
  ),
  ...notCovered(R_CONTEXT_MCP, "getContextService", "updateContextService", "getMcpService", "updateMcpService"),

  {
    message: "exportImagesDone",
    status: "not-covered",
    reason: `${NO_UI_SENDER}: the UI reports the end of an export with exportImagesComplete, which shares the handler`,
  },
  {
    message: "listChainExportTargets",
    status: "not-covered",
    reason: `${NO_UI_SENDER}: the extension calls it itself when an export starts (extension.ts)`,
  },

  ...["importSystems", "createContextService", "getSpecificationModelSource", "exportImagesStarted", "exportImagesItemDone"].map(
    (message): MessageEntry => ({ message, status: "unhandled", reason: R_1214 }),
  ),
];

/** Rows that repeat a type or lack a required reason. */
export function validateMessageRegistry(entries: readonly MessageEntry[]): string[] {
  const problems: string[] = [];
  const seen = new Set<string>();
  for (const entry of entries) {
    if (seen.has(entry.message)) problems.push(`${entry.message}: more than one row`);
    seen.add(entry.message);
    if (entry.status !== "covered" && !entry.reason) problems.push(`${entry.message}: a ${entry.status} row needs a reason`);
  }
  return problems;
}

// ---------------------------------------------------------------------------
// The sources
// ---------------------------------------------------------------------------

/**
 * The types the extension answers: the `case` labels of `getApiResponse` in `apiRouter.ts`, and the
 * `message.data.type` literals the diff panel's listener in `chainDiffEditor.ts` compares.
 */
export function handledTypes(apiRouter: string, chainDiffEditor: string): string[] {
  const start = apiRouter.indexOf("export async function getApiResponse(");
  // The function ends at the first line that closes a top-level block.
  const body = apiRouter.slice(start, apiRouter.indexOf("\n}", start));
  const cases = [...body.matchAll(/\bcase\s+"([^"]+)"\s*:/g)].map((match) => match[1]);
  const diff = [...chainDiffEditor.matchAll(/message\.data\.type\s*===\s*"([^"]+)"/g)].map((match) => match[1]);
  return [...cases, ...diff];
}

export interface SourceFile {
  path: string;
  text: string;
}

/**
 * The types the UI sends: the first argument of every `sendMessageToExtension(` call, read across
 * lines, and of every `reportExportImagesProgress(` call, which forwards its type.
 *
 * A first argument that is a string literal is the type. An identifier is resolved through
 * `constants`, the `export const NAME = "…"` declarations of `vscodeExtensionApi.ts`. The one
 * other identifier allowed is the wrapper's own `type` parameter; any other argument is returned
 * in `unresolved`, so a new indirection fails the harness instead of hiding a type.
 */
export function sentTypes(files: readonly SourceFile[], constants: string): { types: string[]; unresolved: string[] } {
  const named = new Map([...constants.matchAll(/export const (\w+) = "([^"]+)"/g)].map((match) => [match[1], match[2]]));
  const types = new Set<string>();
  const unresolved: string[] = [];
  for (const file of files) {
    const calls = file.text.matchAll(/(?<!function\s+)\b(sendMessageToExtension|reportExportImagesProgress)\(\s*([^,)]+?)\s*[,)]/g);
    for (const [, callee, argument] of calls) {
      const literal = /^"([^"]+)"$/.exec(argument);
      if (literal) types.add(literal[1]);
      else if (named.has(argument)) types.add(named.get(argument)!);
      else if (!(callee === "sendMessageToExtension" && argument === "type" && file.path.endsWith("vscodeExportSink.ts"))) {
        unresolved.push(`${file.path}: ${callee}(${argument}`);
      }
    }
  }
  return { types: [...types], unresolved };
}

/** The rows against the sources. Empty means they agree. */
export function sourceDrift(entries: readonly MessageEntry[], handled: readonly string[], sent: readonly string[]): string[] {
  const problems: string[] = [];
  const rows = new Map(entries.map((entry) => [entry.message, entry]));
  const handledSet = new Set(handled);
  const sentSet = new Set(sent);
  for (const type of handledSet) {
    const row = rows.get(type);
    if (row === undefined) problems.push(`${type}: the extension handles it, but it has no row`);
    else if (row.status === "unhandled") problems.push(`${type}: marked unhandled, but the extension handles it now`);
    else if (!sentSet.has(type) && (row.status !== "not-covered" || !row.reason?.startsWith(NO_UI_SENDER))) {
      problems.push(`${type}: nothing in ui/src sends it, so its row is not-covered with a reason that starts "${NO_UI_SENDER}"`);
    }
  }
  for (const type of sentSet) {
    if (!handledSet.has(type) && rows.get(type)?.status !== "unhandled") {
      problems.push(`${type}: the UI sends it and the extension has no handler, so its row is unhandled`);
    }
  }
  for (const entry of entries) {
    if (!handledSet.has(entry.message) && !sentSet.has(entry.message)) {
      problems.push(`${entry.message}: a row for a type neither the extension handles nor the UI sends`);
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------
// The run
// ---------------------------------------------------------------------------

/** The project a `--only=vscode-messages` reconcile accepts the run narrowed to. */
const VSCODE_PROJECT_FILTERS = [["--project=vscode"], ["--project", "vscode"]];

/** Every type a passing test recorded, from its `vscode-message` annotations. */
export function recordedMessages(report: JsonReport): Set<string> {
  const recorded = new Set<string>();
  for (const spec of eachSpec(report)) {
    for (const each of spec.tests ?? []) {
      if (!proves(each)) continue;
      for (const annotation of each.annotations ?? []) {
        if (annotation.type === VSCODE_MESSAGE_ANNOTATION && annotation.description) recorded.add(annotation.description);
      }
    }
  }
  return recorded;
}

/**
 * The message registry against the run. Empty means they agree.
 *
 * A whole run is read unless `only` is set, in which case the run has to be narrowed to exactly
 * `--project=vscode`. A `covered` row no passing test recorded fails, and so does a `not-covered`
 * row one did, or a recorded type with no row. A type recorded for an `unhandled` row is ignored:
 * the extension answers it with no payload and no error, which proves nothing.
 */
export function reconcileMessages(report: JsonReport, entries: readonly MessageEntry[] = messageRegistry, { only = false } = {}): string[] {
  const filters = runFilters(report);
  if (only && !VSCODE_PROJECT_FILTERS.some((allowed) => allowed.join(" ") === filters.join(" "))) {
    return [`the run was narrowed by \`${filters.join(" ") || "nothing"}\`: --only=vscode-messages reads a run of --project=vscode`];
  }
  if (!only && filters.length > 0) {
    return [`the run was narrowed by \`${filters.join(" ")}\`: reconciliation reads a whole run or none`];
  }

  const problems: string[] = [];
  const recorded = recordedMessages(report);
  const rows = new Map(entries.map((entry) => [entry.message, entry]));
  for (const type of recorded) {
    const row = rows.get(type);
    if (row === undefined) problems.push(`${type} was recorded, but it is not a message row`);
    else if (row.status === "not-covered") problems.push(`${type}: marked not-covered, but a passing test recorded it`);
  }
  for (const entry of entries) {
    if (entry.status === "covered" && !recorded.has(entry.message)) {
      problems.push(`${entry.message}: marked covered, but no passing test recorded it`);
    }
  }
  return problems;
}

/** The registry's counts, for the reconcile line. */
export function summarizeMessages(entries: readonly MessageEntry[]): { covered: number; handled: number; unhandled: number } {
  const unhandled = entries.filter((entry) => entry.status === "unhandled").length;
  return { covered: entries.filter((entry) => entry.status === "covered").length, handled: entries.length - unhandled, unhandled };
}
