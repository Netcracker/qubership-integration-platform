/**
 * The webview-extension bridge without a browser: the trap the init script installs, run in a VM
 * context whose `window` stands in for a webview's content frame; the pairing of what it reports;
 * the message registry read against the extension and UI sources; and its reconciliation against a
 * hand-built report.
 */
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { reconcileArgs, type JsonReport } from "../../registry/elements.js";
import {
  NO_UI_SENDER,
  handledTypes,
  messageRegistry,
  reconcileMessages,
  sentTypes,
  sourceDrift,
  validateMessageRegistry,
  type MessageEntry,
  type SourceFile,
} from "../../registry/vscode-messages.js";
import {
  BINDING,
  EFFECT_ONLY_TYPES,
  MessageRecorder,
  VSCODE_MESSAGE_ANNOTATION,
  annotateMessage,
  trapAcquireVsCodeApi,
  type BridgeMessage,
} from "../../support/vscode-messages.js";

/** A content frame with the trap installed from its source, as `addInitScript` serializes it. */
function frameWithTrap() {
  const reported: BridgeMessage[] = [];
  const posted: unknown[] = [];
  const window = Object.assign(new EventTarget(), { [BINDING]: (message: BridgeMessage) => reported.push(message) });
  vm.runInNewContext(`(${trapAcquireVsCodeApi.toString()})(${JSON.stringify(BINDING)})`, { window });
  const frame = window as EventTarget & { acquireVsCodeApi?: () => { postMessage(message: unknown): void } };
  // What `pre/index.html` does: assign a factory that may be called once.
  let acquired = false;
  frame.acquireVsCodeApi = () => {
    if (acquired) throw new Error("An instance of the VS Code API has already been acquired");
    acquired = true;
    return { postMessage: (message: unknown) => posted.push(message), getState: () => undefined, setState: () => undefined };
  };
  const receive = (data: unknown) => frame.dispatchEvent(new MessageEvent("message", { data }));
  return { frame, reported, posted, receive };
}

test.describe("vscode messages", () => {
  test.describe("trap", () => {
    test("reports a cip request and delegates it", () => {
      const { frame, reported, posted } = frameWithTrap();
      const api = frame.acquireVsCodeApi!();
      const message = { command: "cip", data: { type: "getChain", requestId: "r1", payload: "c1" } };
      api.postMessage(message);
      expect(reported).toEqual([{ direction: "sent", type: "getChain", requestId: "r1" }]);
      expect(posted).toEqual([message]);
      expect(Object.isFrozen(api)).toBe(true);
    });

    test("leaves out a message that is not a cip request", () => {
      const { frame, reported, posted } = frameWithTrap();
      frame.acquireVsCodeApi!().postMessage({ command: "requestTheme" });
      expect(reported).toEqual([]);
      expect(posted).toEqual([{ command: "requestTheme" }]);
    });

    test("a second acquire still throws", () => {
      const { frame } = frameWithTrap();
      frame.acquireVsCodeApi!();
      expect(() => frame.acquireVsCodeApi!()).toThrow(/already been acquired/);
    });

    test("reports an answer, and skips a message with no request id", () => {
      const { reported, receive } = frameWithTrap();
      receive({ requestId: "r1", type: "getChain", payload: {} });
      receive({ requestId: "r2", type: "getLibrary", error: { message: "boom", name: "Error" } });
      receive({ requestId: "r3", type: "getSpecificationModelSource", payload: undefined });
      receive({ type: "theme-update", payload: {} });
      expect(reported).toEqual([
        { direction: "received", type: "getChain", requestId: "r1", error: false, payload: true },
        { direction: "received", type: "getLibrary", requestId: "r2", error: true, payload: false },
        { direction: "received", type: "getSpecificationModelSource", requestId: "r3", error: false, payload: false },
      ]);
    });
  });

  test.describe("pairing", () => {
    const sent = (type: string, requestId: string): BridgeMessage => ({ direction: "sent", type, requestId });
    const answer = (type: string, requestId: string, error = false, payload = true): BridgeMessage => ({ direction: "received", type, requestId, error, payload });

    test("an answer without an error marks its request's type", () => {
      const recorder = new MessageRecorder();
      recorder.record(sent("getChain", "r1"));
      recorder.record(sent("getLibrary", "r2"));
      expect(recorder.answered()).toEqual([]);
      recorder.record(answer("getChain", "r1"));
      recorder.record(answer("getLibrary", "r2", true));
      expect(recorder.answered()).toEqual(["getChain"]);
      expect(recorder.sent("getLibrary")).toBe(true);
      expect(recorder.sent("getElements")).toBe(false);
    });

    test("an answer with no payload is told apart", () => {
      const recorder = new MessageRecorder();
      recorder.record(sent("getSpecificationModelSource", "r1"));
      recorder.record(sent("getChain", "r2"));
      recorder.record(sent("getLibrary", "r3"));
      recorder.record(answer("getSpecificationModelSource", "r1", false, false));
      recorder.record(answer("getChain", "r2"));
      recorder.record(answer("getLibrary", "r3", true, false));
      expect(recorder.answeredWithoutPayload("getSpecificationModelSource")).toBe(true);
      expect(recorder.answeredWithoutPayload("getChain")).toBe(false);
      // An error is an answer of its own kind.
      expect(recorder.answeredWithoutPayload("getLibrary")).toBe(false);
    });

    test("a request stays unanswered until any answer to it arrives", () => {
      const recorder = new MessageRecorder();
      recorder.record(sent("getService", "r1"));
      recorder.record(sent("getService", "r2"));
      recorder.record(sent("getLibrary", "r3"));
      expect(recorder.unanswered()).toEqual(["getService", "getService", "getLibrary"]);
      recorder.record(answer("getService", "r2"));
      recorder.record(answer("getLibrary", "r3", true));
      expect(recorder.unanswered()).toEqual(["getService"]);
    });

    test("comparedDocumentsResponse answers comparedDocumentsRequest", () => {
      const recorder = new MessageRecorder();
      recorder.record(sent("comparedDocumentsRequest", "r1"));
      recorder.record(answer("comparedDocumentsResponse", "r1"));
      expect(recorder.answered()).toEqual(["comparedDocumentsRequest"]);
    });

    test("an answer to no request is ignored", () => {
      // The extension's own `navigate` carries a request id no webview sent.
      const recorder = new MessageRecorder();
      recorder.record(answer("navigate", "from-the-extension"));
      expect(recorder.answered()).toEqual([]);
    });
  });

  test.describe("annotations", () => {
    test("an effect-only type counts only through sentMessage", () => {
      const recorder = new MessageRecorder();
      for (const [index, type] of ["getChain", ...EFFECT_ONLY_TYPES].entries()) {
        recorder.record({ direction: "sent", type, requestId: `r${index}` });
        recorder.record({ direction: "received", type, requestId: `r${index}`, error: false, payload: true });
      }
      expect(recorder.covered()).toEqual(["getChain"]);
    });

    test("one annotation per type", () => {
      const testInfo = { annotations: [] as { type: string; description?: string }[] };
      annotateMessage(testInfo, "getChain");
      annotateMessage(testInfo, "getChain");
      annotateMessage(testInfo, "getLibrary");
      expect(testInfo.annotations).toEqual([
        { type: VSCODE_MESSAGE_ANNOTATION, description: "getChain" },
        { type: VSCODE_MESSAGE_ANNOTATION, description: "getLibrary" },
      ]);
    });
  });

  test.describe("registry", () => {
    const read = (relative: string) => fs.readFileSync(path.join(repoRoot(), relative), "utf-8");
    const apiRouter = read("vscode-extension/src/web/response/apiRouter.ts");
    const chainDiffEditor = read("vscode-extension/src/web/chainDiffEditor.ts");
    const constants = read("ui/src/api/rest/vscodeExtensionApi.ts");
    const uiFiles: SourceFile[] = (fs.readdirSync(path.join(repoRoot(), "ui/src"), { recursive: true, encoding: "utf-8" }) as string[])
      .filter((file) => /\.tsx?$/.test(file))
      .map((file) => ({ path: `ui/src/${file}`, text: read(`ui/src/${file}`) }));

    test("the rows are unique and every uncovered row has a reason", () => {
      expect(validateMessageRegistry(messageRegistry)).toEqual([]);
      expect(validateMessageRegistry([{ message: "a", status: "not-covered" }, { message: "a", status: "covered" }])).toEqual([
        "a: a not-covered row needs a reason",
        "a: more than one row",
      ]);
    });

    test("the extension parser reads two handlers", () => {
      const handled = handledTypes(apiRouter, chainDiffEditor);
      expect(handled).toHaveLength(67);
      expect(handled).toEqual(expect.arrayContaining(["startup", "listChainExportTargets", "comparedDocumentsRequest", "navigateComparedDocumentInNewTab"]));
      // The second `switch` in apiRouter.ts switches on a file type, after getApiResponse ends.
      const router = `export async function getApiResponse(m) {\n  switch (m.type) {\n    case "getChain":\n      return 1;\n  }\n}\nfunction other(t) {\n  switch (t) {\n    case "boolean":\n  }\n}\n`;
      expect(handledTypes(router, `if (message.data.type === "comparedDocumentsRequest") {}`)).toEqual(["getChain", "comparedDocumentsRequest"]);
    });

    test("the UI parser reads calls, constants, and the wrapper", () => {
      const files: SourceFile[] = [
        { path: "a.ts", text: `api.sendMessageToExtension(\n  "getContextService",\n  id,\n);\napi.sendMessageToExtension(STARTUP_EVENT);\nvoid api.sendMessageToExtension("getLibrary")` },
        {
          path: "ui/src/batch/export/vscodeExportSink.ts",
          text: `export async function reportExportImagesProgress(\n  type: string,\n) {\n  await api.sendMessageToExtension(type, payload);\n}`,
        },
        { path: "b.tsx", text: `void reportExportImagesProgress("exportImagesStarted", {});\napi.sendMessageToExtension(someType);` },
      ];
      expect(sentTypes(files, `export const STARTUP_EVENT = "startup";`)).toEqual({
        types: ["getContextService", "startup", "getLibrary", "exportImagesStarted"],
        unresolved: ["b.tsx: sendMessageToExtension(someType"],
      });
    });

    test("the rows match what the extension and the UI hold", () => {
      const { types, unresolved } = sentTypes(uiFiles, constants);
      expect(unresolved).toEqual([]);
      expect(types).toEqual(expect.arrayContaining(["startup", "navigate", "comparedDocumentsRequest", "exportImagesItemWarning", "getSpecificationModelSource"]));
      expect(sourceDrift(messageRegistry, handledTypes(apiRouter, chainDiffEditor), types)).toEqual([]);
      expect(messageRegistry.filter((entry) => entry.status === "unhandled").map((entry) => entry.message).sort()).toEqual(
        ["createContextService", "exportImagesItemDone", "exportImagesStarted", "getSpecificationModelSource", "importSystems"],
      );
    });

    test("drift fails in either direction", () => {
      const rows: MessageEntry[] = [
        { message: "getChain", status: "covered" },
        { message: "listTargets", status: "not-covered", reason: `${NO_UI_SENDER}: the extension calls it` },
        { message: "importSystems", status: "unhandled", reason: "#1214" },
      ];
      expect(sourceDrift(rows, ["getChain", "listTargets"], ["getChain", "importSystems"])).toEqual([]);
      // A handler with no row, and a sent type with neither a handler nor an unhandled row.
      expect(sourceDrift(rows, ["getChain", "listTargets", "getLibrary"], ["getChain", "importSystems", "getLibrary", "newType"])).toEqual([
        "getLibrary: the extension handles it, but it has no row",
        "newType: the UI sends it and the extension has no handler, so its row is unhandled",
      ]);
      // An unhandled row that gained a handler.
      expect(sourceDrift(rows, ["getChain", "listTargets", "importSystems"], ["getChain", "importSystems"])).toEqual([
        "importSystems: marked unhandled, but the extension handles it now",
      ]);
      // A handler with no UI sender and no reason saying so, and a row for a type nobody has.
      const unexplained: MessageEntry[] = [...rows.slice(0, 1), { message: "listTargets", status: "not-covered", reason: "later" }, ...rows.slice(2), { message: "gone", status: "covered" }];
      expect(sourceDrift(unexplained, ["getChain", "listTargets"], ["getChain", "importSystems"])).toEqual([
        `listTargets: nothing in ui/src sends it, so its row is not-covered with a reason that starts "${NO_UI_SENDER}"`,
        "gone: a row for a type neither the extension handles nor the UI sends",
      ]);
    });
  });

  test.describe("reconcile", () => {
    const rows: MessageEntry[] = [
      { message: "getChain", status: "covered" },
      { message: "getLibrary", status: "not-covered", reason: "later" },
      { message: "importSystems", status: "unhandled", reason: "#1214" },
    ];
    /** A run invoked with `args`, one test per entry of `tests`, each recording the given types. */
    const run = (args: string[], tests: { recorded: string[]; status?: string; expectedStatus?: string }[]): JsonReport => ({
      config: { argv: ["/usr/bin/node", "node_modules/.bin/playwright", "test", ...args] },
      suites: [
        {
          specs: tests.map((each, index) => ({
            title: `case ${index}`,
            file: "specs/vscode/smoke.spec.ts",
            tests: [
              {
                projectName: "vscode",
                status: each.status ?? "expected",
                expectedStatus: each.expectedStatus ?? "passed",
                annotations: each.recorded.map((type) => ({ type: VSCODE_MESSAGE_ANNOTATION, description: type })),
              },
            ],
          })),
        },
      ],
    });
    const vscodeRun = (tests: Parameters<typeof run>[1]) => run(["--project=vscode"], tests);

    test("the options are read apart from the report path", () => {
      expect(reconcileArgs([])).toEqual({});
      expect(reconcileArgs(["test-results/report.json"])).toEqual({ report: "test-results/report.json" });
      expect(reconcileArgs(["--only=vscode-messages", "r.json"])).toEqual({ onlyMessages: true, report: "r.json" });
      expect(reconcileArgs(["r.json", "--only=vscode-messages"])).toEqual({ onlyMessages: true, report: "r.json" });
      expect(reconcileArgs(["--only=elements"]).error).toMatch(/unknown option --only=elements/);
      expect(reconcileArgs(["--verbose"]).error).toMatch(/unknown option --verbose/);
    });

    test("a covered row a passing test recorded agrees", () => {
      expect(reconcileMessages(vscodeRun([{ recorded: ["getChain"] }]), rows, true)).toEqual([]);
      expect(reconcileMessages(run(["--project", "vscode"], [{ recorded: ["getChain"] }]), rows, true)).toEqual([]);
    });

    test("a whole run reconciles without the option", () => {
      expect(reconcileMessages(run([], [{ recorded: ["getChain"] }]), rows)).toEqual([]);
      expect(reconcileMessages(run([], []), rows)).toEqual(["getChain: marked covered, but no passing test recorded it"]);
    });

    test("--only=vscode-messages accepts exactly --project=vscode", () => {
      for (const args of [[], ["--project=ui"], ["--project=vscode", "--project=ui"], ["--project=vscode", "-g", "smoke"], ["specs/vscode/smoke.spec.ts"]]) {
        expect(reconcileMessages(run(args, [{ recorded: ["getChain"] }]), rows, true)).toEqual([
          expect.stringMatching(/^the run was narrowed by `.*`: --only=vscode-messages reads a run of --project=vscode$/),
        ]);
      }
      // A non-selecting option leaves the run at --project=vscode.
      expect(reconcileMessages(run(["--project=vscode", "--workers=1"], [{ recorded: ["getChain"] }]), rows, true)).toEqual([]);
      // The PR workflow's vscode job runs it so.
      expect(reconcileMessages(run(["--project=vscode", "--max-failures=1"], [{ recorded: ["getChain"] }]), rows, true)).toEqual([]);
    });

    test("a covered row no passing test recorded fails", () => {
      const failed = vscodeRun([{ recorded: ["getChain"], status: "unexpected" }]);
      const pinned = vscodeRun([{ recorded: ["getChain"], expectedStatus: "failed" }]);
      for (const report of [vscodeRun([]), failed, pinned]) {
        expect(reconcileMessages(report, rows, true)).toEqual(["getChain: marked covered, but no passing test recorded it"]);
      }
    });

    test("a not-covered row a passing test recorded fails", () => {
      expect(reconcileMessages(vscodeRun([{ recorded: ["getChain", "getLibrary"] }]), rows, true)).toEqual([
        "getLibrary: marked not-covered, but a passing test recorded it",
      ]);
    });

    test("a recorded type with no row fails", () => {
      expect(reconcileMessages(vscodeRun([{ recorded: ["getChain", "brandNew"] }]), rows, true)).toEqual([
        "brandNew was recorded, but it is not a message row",
      ]);
    });

    test("an answer recorded for an unhandled row is ignored", () => {
      expect(reconcileMessages(vscodeRun([{ recorded: ["getChain", "importSystems"] }]), rows, true)).toEqual([]);
    });
  });
});
