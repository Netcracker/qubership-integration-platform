/**
 * The recorder of the webview-extension bridge: every request a webview sends to the extension,
 * paired by `requestId` with the answer the extension posts back.
 *
 * `acquireVsCodeApi()` returns a frozen object that posts through the parent frame over a
 * `MessagePort`, so an outgoing message is never a `message` event a page listener could see. The
 * init script therefore traps the global: `pre/index.html` assigns `acquireVsCodeApi` into the
 * content frame, the setter wraps the factory, and each record goes to Node through a binding as it
 * happens, so a webview closed mid-case loses nothing.
 */
import type { BrowserContext, TestInfo } from "@playwright/test";

/** The annotation a case carries once per message type it saw answered. */
export const VSCODE_MESSAGE_ANNOTATION = "vscode-message";

export const BINDING = "__e2eVsCodeMessage";

/**
 * Types the extension answers whatever its handler did, or not at all: it posts nothing for the
 * first two and an error-free answer for the third. A case counts one only through `sentMessage`,
 * after asserting the tab it opens.
 */
export const EFFECT_ONLY_TYPES: readonly string[] = ["openChainInNewTab", "navigateInNewTab", "navigateComparedDocumentInNewTab"];

/** One message the init script saw, in either direction. */
export type BridgeMessage =
  | { direction: "sent"; type: string; requestId: string }
  | { direction: "received"; type: string; requestId: string; error: boolean };

/** Pairs what the webviews sent with what the extension answered. */
export class MessageRecorder {
  /** The type of every request sent, by its `requestId`. */
  private readonly requests = new Map<string, string>();
  private readonly answeredTypes = new Set<string>();

  record(message: BridgeMessage): void {
    if (message.direction === "sent") {
      this.requests.set(message.requestId, message.type);
      return;
    }
    // An answer marks the type of its request: `comparedDocumentsResponse` counts for `comparedDocumentsRequest`.
    // An unknown `requestId` is a message the extension started, such as its own `navigate`.
    const type = this.requests.get(message.requestId);
    if (type === undefined || message.error) return;
    this.answeredTypes.add(type);
  }

  /** Whether any webview sent a request of `type`. */
  sent(type: string): boolean {
    return [...this.requests.values()].includes(type);
  }

  /** The types answered without an error, in the order of their first answer. */
  answered(): string[] {
    return [...this.answeredTypes];
  }

  /** The types a case counts by their answer: every answered type outside `EFFECT_ONLY_TYPES`. */
  covered(): string[] {
    return this.answered().filter((type) => !EFFECT_ONLY_TYPES.includes(type));
  }
}

/** Adds an annotation for `type` unless the case already carries one. */
export function annotateMessage(testInfo: Pick<TestInfo, "annotations">, type: string): void {
  const { annotations } = testInfo;
  if (annotations.some((each) => each.type === VSCODE_MESSAGE_ANNOTATION && each.description === type)) return;
  annotations.push({ type: VSCODE_MESSAGE_ANNOTATION, description: type });
}

/**
 * Runs in every frame before its scripts. Only `command: "cip"` is reported, which leaves out the
 * theme request and the media preview's own webview. The setter also adds the incoming listener,
 * because `document.open()` drops the listeners a frame had before it.
 */
export function trapAcquireVsCodeApi(binding: string): void {
  type Api = { postMessage(message: unknown): void; getState(): unknown; setState(state: unknown): unknown };
  type Message = { command?: string; data?: { type?: string; requestId?: string } };
  const report = (message: unknown) => (window as unknown as Record<string, (message: unknown) => void>)[binding](message);
  let wrapped: (() => Api) | undefined;
  Object.defineProperty(window, "acquireVsCodeApi", {
    configurable: true,
    get: () => wrapped,
    set(original: () => Api) {
      // Each call reaches the original, so a second acquire still throws.
      wrapped = () => {
        const api = original();
        return Object.freeze({
          postMessage(message: Message) {
            if (message?.command === "cip") report({ direction: "sent", type: message.data?.type, requestId: message.data?.requestId });
            return api.postMessage(message);
          },
          getState: () => api.getState(),
          setState: (state: unknown) => api.setState(state),
        });
      };
      window.addEventListener("message", (event: MessageEvent<{ type?: unknown; requestId?: unknown; error?: unknown }>) => {
        const { type, requestId, error } = event.data ?? {};
        if (typeof type === "string" && typeof requestId === "string") {
          report({ direction: "received", type, requestId, error: error !== undefined });
        }
      });
    },
  });
}

/** Installs the trap in every frame of `context` and feeds what it reports into a new recorder. */
export async function recordMessages(context: BrowserContext): Promise<MessageRecorder> {
  const recorder = new MessageRecorder();
  await context.exposeBinding(BINDING, (_source, message: BridgeMessage) => recorder.record(message));
  await context.addInitScript(trapAcquireVsCodeApi, BINDING);
  return recorder;
}
