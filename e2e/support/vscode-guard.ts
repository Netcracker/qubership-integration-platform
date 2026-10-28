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
export const withAnyStack = (message: string) => new RegExp(`^${message.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\n\\tat `);

/**
 * What fails the case: every problem neither allowed nor expected, and every expected message that
 * never arrived. An expected message matches exactly, or its pattern matches, and absorbs each
 * repeat of itself.
 */
export function guardFailures(problems: readonly VsCodeProblem[], expected: readonly ExpectedError[]): string[] {
  const unexpected = problems
    .filter((each) => !allowed(each) && !expected.some((message) => matches(message, each.message)))
    .map((each) => (each.source === "pageerror" ? `uncaught ${each.message}` : `console.error${each.url ? ` at ${each.url}` : ""}: ${each.message}`));
  const missing = expected
    .filter((message) => !problems.some((each) => matches(message, each.message)))
    .map((message) => `the expected error never arrived: ${message}`);
  return [...unexpected, ...missing];
}

/** The experiment service the workbench of the pinned build asks for its assignments. */
export const EXPERIMENT_SERVICE = "https://default.exp-tas.com/**";

interface VsCodeFixtures {
  vscode: VsCodePage;
  /** Declares an error the case meets, by its exact message or a pattern, so that the guard lets it through. */
  expectConsoleError: (message: ExpectedError) => void;
  messages: MessageRecorder;
  /** Waits until a webview sent a request of `type`, and counts the type for the case. */
  sentMessage: (type: string) => Promise<void>;
  expectedErrors: ExpectedError[];
  vscodeGuard: void;
  experimentService: void;
}

export const test = base.extend<VsCodeFixtures>({
  // The workbench asks the experiment service on the internet for its assignments some time after
  // the load. Once its preflight answer carried no CORS header, and the page logged two
  // console.error lines (2026-10-28). An empty answer keeps every case off the network.
  experimentService: [
    async ({ context }, use) => {
      await context.route(EXPERIMENT_SERVICE, (route) =>
        route.fulfill({ json: { Configs: [], AssignmentContext: "" }, headers: { "access-control-allow-origin": "*" } }),
      );
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
  sentMessage: async ({ messages }, use, testInfo) => {
    await use(async (type) => {
      await expect.poll(() => messages.sent(type), { message: `a webview sends ${type}` }).toBe(true);
      annotateMessage(testInfo, type);
    });
  },
  vscodeGuard: [
    async ({ page, expectedErrors }, use, testInfo) => {
      const problems: VsCodeProblem[] = [];
      page.on("pageerror", (error) => problems.push({ source: "pageerror", message: error.message }));
      page.on("console", (message) => {
        if (message.type() === "error") problems.push({ source: "console", message: message.text(), url: message.location().url || undefined });
      });
      await use();
      const failures = guardFailures(problems, expectedErrors);
      if (failures.length === 0) return;
      // A `test.fail()` case pins one defect, and a problem the guard caught is not that defect.
      testInfo.expectedStatus = "passed";
      throw new Error(`the page reported ${failures.length} problem(s):\n  ${failures.join("\n  ")}`);
    },
    { auto: true },
  ],
});

export { expect };
