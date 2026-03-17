/**
 * JSDOM and test environment setup.
 *
 * 1. Prevents JSDOM "Not implemented: navigation" errors by blocking anchor default
 *    behavior. JSDOM does not implement full navigation; clicking <a href> throws.
 *
 * 2. Suppresses known deprecation warnings from third-party libraries (antd, rc-component)
 *    that we cannot fix: findDOMNode (rc-tooltip / rc-resize-observer via antd Table).
 *
 * 3. Suppresses "was not wrapped in act(...)" warnings from antd components (Tabs, Modal, etc.)
 *    that trigger internal state updates; fixing requires intrusive test changes with limited benefit.
 *
 * 4. Suppresses expected console.error from tests that intentionally trigger error paths
 *    (e.g. fetch rejection) to verify error handling — DocumentationPage, documentationService.
 *
 * 5. Polyfill for structuredClone (Node 17+ / modern browsers) when running in older jsdom.
 *
 * 6. Polyfill for HTMLFormElement.requestSubmit (not implemented in older jsdom).
 */
if (
  typeof (globalThis as { structuredClone?: unknown }).structuredClone ===
  "undefined"
) {
  (globalThis as { structuredClone: <T>(val: T) => T }).structuredClone = <T>(
    val: T,
  ): T => JSON.parse(JSON.stringify(val));
}

if (typeof globalThis.HTMLFormElement !== "undefined") {
  const proto = HTMLFormElement.prototype as HTMLFormElement & {
    requestSubmit?: (submitter?: HTMLElement) => void;
  };
  proto.requestSubmit = function (_submitter?: HTMLElement) {
    /* no-op: jsdom throws "Not implemented"; prevents errors when Save button triggers form submit */
  };
}

const originalConsoleError = console.error;
console.error = (...args: unknown[]) => {
  const fullMsg = args
    .map((a) =>
      typeof a === "string"
        ? a
        : a instanceof Error
          ? (a as Error).message
          : String(a),
    )
    .join(" ");
  if (
    fullMsg.includes("findDOMNode is deprecated") ||
    fullMsg.includes("was not wrapped in act") ||
    fullMsg.includes("Not implemented: navigation") ||
    fullMsg.includes("Failed to load documentation") ||
    fullMsg.includes("Failed to open element documentation") ||
    fullMsg.includes("Failed to open context documentation") ||
    fullMsg.includes("Unable to parse JSON") ||
    fullMsg.includes("Function components cannot be given refs") ||
    fullMsg.includes("Maximum update depth exceeded") ||
    fullMsg.includes(
      "Not implemented: HTMLFormElement.prototype.requestSubmit",
    ) ||
    fullMsg.includes("Failed to parse schema")
  ) {
    return;
  }
  originalConsoleError.apply(console, args);
};

if (
  typeof document !== "undefined" &&
  typeof document.addEventListener === "function"
) {
  document.addEventListener(
    "click",
    (e: MouseEvent) => {
      const target = e.target as Node | null;
      const anchor =
        target && "closest" in target
          ? (target as Element).closest("a[href]")
          : null;
      if (anchor) {
        e.preventDefault();
      }
    },
    true,
  );
}
