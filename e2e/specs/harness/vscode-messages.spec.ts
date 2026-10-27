/**
 * The recorder of the webview-extension bridge, without a browser: the trap the init script installs,
 * run in a VM context whose `window` stands in for a webview's content frame, and the pairing of
 * what it reports.
 */
import vm from "node:vm";
import { test, expect } from "@playwright/test";
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
      receive({ type: "theme-update", payload: {} });
      expect(reported).toEqual([
        { direction: "received", type: "getChain", requestId: "r1", error: false },
        { direction: "received", type: "getLibrary", requestId: "r2", error: true },
      ]);
    });
  });

  test.describe("pairing", () => {
    const sent = (type: string, requestId: string): BridgeMessage => ({ direction: "sent", type, requestId });
    const answer = (type: string, requestId: string, error = false): BridgeMessage => ({ direction: "received", type, requestId, error });

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
        recorder.record({ direction: "received", type, requestId: `r${index}`, error: false });
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
});
