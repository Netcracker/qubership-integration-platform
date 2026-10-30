/**
 * The `test` every `vscode` spec imports: VS Code for the web in the case's own browser context, a
 * guard on the page, and the recorder of the webview-extension bridge.
 *
 * The guard fails the case on an uncaught exception and on a `console.error` in any frame: the
 * webviews, the workbench, and the extension host, whose errors the workbench prints in the top
 * frame as `[Extension Host] …` and `Activating extension … failed: …`. The source is not told
 * apart: an error raised by an inline or evaluated script carries no location URL. Built on
 * `@playwright/test` rather than `support/fixtures.ts`, whose automatic fixtures need the stack.
 */
import { test as base, expect } from "@playwright/test";
import { escapeRegExp } from "../fixtures/templating.js";
import { VsCodePage } from "../pages/VsCodePage.js";
import { VSCODE_PORT } from "./vscode-server.js";
import { annotateMessage, recordMessages, type MessageRecorder } from "./vscode-messages.js";

/** One error the page reported. */
export interface VsCodeProblem {
  source: "console" | "pageerror";
  message: string;
  url?: string;
}

/** An error every page load reports, with the run that showed it. */
export interface AllowedProblem {
  message: string;
  url: string;
  shownBy: string;
}

export const ALLOWED: readonly AllowedProblem[] = [
  {
    // The workbench asks for the localized manifest of the development extension, which has none.
    message: "Failed to load resource: the server responded with a status of 404 (Not Found)",
    url: `http://localhost:${VSCODE_PORT}/static/devextensions/package.nls.json`,
    shownBy: "every workbench load probed on 2026-10-28 against VS Code c718b461",
  },
];

const allowed = (problem: VsCodeProblem) =>
  ALLOWED.some((each) => problem.source === "console" && each.message === problem.message && each.url === problem.url);

/** An error a case expects: its exact message, or a pattern for a message that carries a build's line numbers. */
export type ExpectedError = string | RegExp;

const matches = (expected: ExpectedError, message: string) => (typeof expected === "string" ? expected === message : expected.test(message));

/**
 * The pattern of `message` followed by its stack trace, whose line numbers in `extension.js` change
 * with every build of the extension.
 */
export const withAnyStack = (message: string) => new RegExp(`^${escapeRegExp(message)}\\n\\tat `);

/**
 * The two lines the workbench prints for one error the extension host logs: `message`, the text the
 * extension passes, followed by `error`, the error it passes with it.
 */
export const extensionHostErrors = (message: string, error: string) => [
  `%c  ERR color: #f33 [Extension Host] ${message} ${error}`,
  `%c[Extension Host] %c${message} color: blue color:  ${error}`,
];

/**
 * What fails the case: every problem neither allowed nor expected, and every expected message that
 * never arrived. An expected message matches exactly, or its pattern matches, and absorbs each
 * repeat of itself. A message in `permitted` passes the same way, and its absence fails nothing.
 */
export function guardFailures(
  problems: readonly VsCodeProblem[],
  expected: readonly ExpectedError[],
  permitted: readonly ExpectedError[] = [],
): string[] {
  const unexpected = problems
    .filter((each) => !allowed(each) && ![...expected, ...permitted].some((message) => matches(message, each.message)))
    .map((each) => (each.source === "pageerror" ? `uncaught ${each.message}` : `console.error${each.url ? ` at ${each.url}` : ""}: ${each.message}`));
  const missing = expected
    .filter((message) => !problems.some((each) => matches(message, each.message)))
    .map((message) => `the expected error never arrived: ${message}`);
  return [...unexpected, ...missing];
}

/** Whether `url` is on this machine: the workbench on `localhost` and each webview on a `*.localhost` origin of its own. */
export const isLocal = (url: URL) => url.hostname === "localhost" || url.hostname.endsWith(".localhost");

interface VsCodeFixtures {
  vscode: VsCodePage;
  /** Declares an error the case meets, by its exact message or a pattern, so that the guard lets it through. */
  expectConsoleError: (message: ExpectedError) => void;
  /** Lets an error through that the case meets only sometimes, so its absence fails nothing. */
  allowConsoleError: (message: ExpectedError) => void;
  messages: MessageRecorder;
  /** Waits until a webview sent a request of `type`, and counts the type for the case. */
  sentMessage: (type: string) => Promise<void>;
  expectedErrors: ExpectedError[];
  allowedErrors: ExpectedError[];
  vscodeGuard: void;
  offsiteAnswers: void;
}

export const test = base.extend<VsCodeFixtures>({
  // The workbench asks services on the internet for its experiment assignments and for the chat and
  // Marketplace manifests. A request that fails logs a console.error (on 2026-10-28
  // main.vscode-cdn.net closed the connection), so every request to another host gets an empty
  // answer, which the workbench reports with a warning at most.
  offsiteAnswers: [
    async ({ context }, use) => {
      await context.route((url) => !isLocal(url), (route) => route.fulfill({ body: "", headers: { "access-control-allow-origin": "*" } }));
      await use();
    },
    { auto: true },
  ],
  // The trap reaches every document loaded after it, and a case loads the workbench in its body.
  messages: [
    async ({ context }, use, testInfo) => {
      const recorder = await recordMessages(context);
      await use(recorder);
      for (const type of recorder.covered()) annotateMessage(testInfo, type);
    },
    { auto: true },
  ],
  vscode: async ({ page }, use) => {
    await use(new VsCodePage(page));
  },
  expectedErrors: async ({}, use) => {
    await use([]);
  },
  expectConsoleError: async ({ expectedErrors }, use) => {
    await use((message) => expectedErrors.push(message));
  },
  allowedErrors: async ({}, use) => {
    await use([]);
  },
  allowConsoleError: async ({ allowedErrors }, use) => {
    await use((message) => allowedErrors.push(message));
  },
  sentMessage: async ({ messages }, use, testInfo) => {
    await use(async (type) => {
      await expect.poll(() => messages.sent(type), { message: `a webview sends ${type}` }).toBe(true);
      annotateMessage(testInfo, type);
    });
  },
  vscodeGuard: [
    async ({ page, expectedErrors, allowedErrors }, use, testInfo) => {
      const problems: VsCodeProblem[] = [];
      page.on("pageerror", (error) => problems.push({ source: "pageerror", message: error.message }));
      page.on("console", (message) => {
        if (message.type() === "error") problems.push({ source: "console", message: message.text(), url: message.location().url || undefined });
      });
      await use();
      const failures = guardFailures(problems, expectedErrors, allowedErrors);
      if (failures.length === 0) return;
      // A `test.fail()` case pins one defect, and a problem the guard caught is not that defect.
      testInfo.expectedStatus = "passed";
      throw new Error(`the page reported ${failures.length} problem(s):\n  ${failures.join("\n  ")}`);
    },
    { auto: true },
  ],
});

export { expect };
