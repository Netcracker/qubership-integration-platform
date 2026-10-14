/**
 * The `test` every `ui` spec imports: the suite's fixtures plus a guard on the page.
 *
 * The guard fails the scenario on an uncaught exception, on a `console.error`, and on a 4xx or 5xx
 * answer to a request the page itself sent under `/api/`. None of the three is visible to the API
 * layer, and a screen that throws can still render every control a scenario looks for. No noise is
 * allowed through: every route in `routes-smoke.spec.ts` loaded against the bundle without any.
 */
import type { Page } from "@playwright/test";
import { test as base } from "./fixtures.js";

/** One thing the guard caught, with the route the page was on when it happened. */
interface PageProblem {
  route: string;
  message: string;
}

function routeOf(page: Page): string {
  const url = new URL(page.url());
  return `${url.pathname}${url.search}`;
}

/** Arms the guard on `page` and returns what it collects; the list only ever grows. */
function watchPage(page: Page): PageProblem[] {
  const problems: PageProblem[] = [];
  const record = (message: string) => problems.push({ route: routeOf(page), message });
  page.on("pageerror", (error) => record(`uncaught ${error.name}: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") record(`console.error: ${message.text()}`);
  });
  page.on("response", (response) => {
    if (response.status() < 400) return;
    const url = new URL(response.url());
    if (!url.pathname.startsWith("/api/")) return;
    record(`${response.request().method()} ${url.pathname} answered ${response.status()}`);
  });
  return problems;
}

function describeProblems(problems: readonly PageProblem[]): string {
  const [first] = problems;
  return (
    `the page reported ${problems.length} problem(s); the first, on ${first.route}: ${first.message}` +
    problems
      .slice(1)
      .map((each) => `\n  on ${each.route}: ${each.message}`)
      .join("")
  );
}

export const test = base.extend<{ pageGuard: void }>({
  pageGuard: [
    async ({ page }, use, testInfo) => {
      const problems = watchPage(page);
      await use();
      if (problems.length === 0) return;
      // A `test.fail()` case pins one defect, and a problem the guard caught is not that defect, so
      // the case fails for real rather than as expected.
      testInfo.expectedStatus = "passed";
      throw new Error(describeProblems(problems));
    },
    { auto: true },
  ],
});

export { expect } from "@playwright/test";
