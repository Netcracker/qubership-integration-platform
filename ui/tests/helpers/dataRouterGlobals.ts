/**
 * jsdom ships no fetch API, and a react-router data router builds a `Request`
 * for every navigation. Routes without loaders never read it back, so a holder
 * for the url, the method and the abort signal is enough to let
 * `createMemoryRouter` navigate under jsdom.
 */
export function installDataRouterGlobals(): void {
  if (typeof globalThis.Request !== "undefined") {
    return;
  }

  class TestRequest {
    readonly url: string;
    readonly method: string;
    readonly signal: AbortSignal | undefined;
    readonly headers = new Map<string, string>();

    constructor(input: string | { url: string }, init: RequestInit = {}) {
      this.url = typeof input === "string" ? input : input.url;
      this.method = init.method ?? "GET";
      this.signal = init.signal ?? undefined;
    }
  }

  globalThis.Request = TestRequest as unknown as typeof Request;
}
