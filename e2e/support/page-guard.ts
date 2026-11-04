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
import { MICRO_CHECKPOINTS, withoutDeletedDomains, type PageProblem } from "./page-problems.js";

function routeOf(page: Page): string {
  const url = new URL(page.url());
  return `${url.pathname}${url.search}`;
}

/** How much of a 5xx answer's body a problem quotes. */
const BODY_CHARS = 300;

/**
 * Arms the guard on `page` and returns what it collects, and a promise that settles once every 5xx
 * body it reads has been added to its problem; the list only ever grows.
 */
function watchPage(page: Page): { problems: PageProblem[]; bodies: Promise<unknown>[] } {
  const problems: PageProblem[] = [];
  const bodies: Promise<unknown>[] = [];
  const record = (problem: Omit<PageProblem, "route">) => {
    const recorded = { route: routeOf(page), ...problem };
    problems.push(recorded);
    return recorded;
  };
  page.on("pageerror", (error) => record({ message: `uncaught ${error.name}: ${error.message}` }));
  page.on("console", (message) => {
    if (message.type() === "error") record({ message: `console.error: ${message.text()}`, url: message.location().url });
  });
  page.on("response", (response) => {
    if (response.status() < 400) return;
    const url = new URL(response.url());
    if (!url.pathname.startsWith("/api/")) return;
    const domain = response.status() >= 500 ? MICRO_CHECKPOINTS.exec(url.pathname)?.[1] : undefined;
    const recorded = record({ message: `${response.request().method()} ${url.pathname} answered ${response.status()}`, url: response.url(), domain });
    // The status alone names no cause, and the service log may have rotated by the time a reader looks.
    if (response.status() >= 500) {
      bodies.push(response.text().then((body) => (recorded.message += `: ${body.slice(0, BODY_CHARS)}`), () => undefined));
    }
  });
  return { problems, bodies };
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
    async ({ page, catalog }, use, testInfo) => {
      const caught = watchPage(page);
      await use();
      await Promise.all(caught.bodies);
      const problems = await withoutDeletedDomains(caught.problems, catalog);
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
