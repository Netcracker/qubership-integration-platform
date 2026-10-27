/**
 * What the `vscode` guard lets through: the measured allowlist, and the errors a case declares with
 * `expectConsoleError`.
 */
import { test, expect } from "@playwright/test";
import { ALLOWED, guardFailures, type VsCodeProblem } from "../../support/vscode-guard.js";

const [nls] = ALLOWED;
const allowed: VsCodeProblem = { source: "console", message: nls.message, url: nls.url };

test.describe("vscode guard", () => {
  test("an allowed 404 passes only on its own URL", () => {
    expect(guardFailures([allowed], [])).toEqual([]);
    const elsewhere = { ...allowed, url: "http://localhost:3917/static/devextensions/package.json" };
    expect(guardFailures([elsewhere], [])).toEqual([`console.error at ${elsewhere.url}: ${nls.message}`]);
    // An uncaught exception with the same text is not the 404.
    expect(guardFailures([{ source: "pageerror", message: nls.message }], [])).toEqual([`uncaught ${nls.message}`]);
  });

  test("every allowlist entry names the run that showed it", () => {
    for (const each of ALLOWED) expect(each.shownBy, each.url).toMatch(/\d{4}-\d{2}-\d{2}/);
  });

  test("an expected error matches its exact message, every time", () => {
    const worker: VsCodeProblem = { source: "pageerror", message: "Event" };
    expect(guardFailures([worker, worker], ["Event"])).toEqual([]);
    expect(guardFailures([{ source: "console", message: "Event" }], ["Event"])).toEqual([]);
    // Neither a longer message nor a shorter one is the declared error.
    expect(guardFailures([{ source: "pageerror", message: "Event loop stalled" }], ["Event"])).toEqual([
      "uncaught Event loop stalled",
      "the expected error never arrived: Event",
    ]);
    expect(guardFailures([{ source: "console", message: "Even" }], ["Event"])).toEqual([
      "console.error: Even",
      "the expected error never arrived: Event",
    ]);
  });

  test("an expected error that never arrives fails the case", () => {
    expect(guardFailures([], ["Event"])).toEqual(["the expected error never arrived: Event"]);
  });

  test("an undeclared error fails beside a declared one", () => {
    const problems: VsCodeProblem[] = [
      { source: "pageerror", message: "Event" },
      { source: "console", message: "[Extension Host] Failed to fetch data for CIP Extension API", url: "http://localhost:3917/x.js" },
    ];
    expect(guardFailures(problems, ["Event"])).toEqual([
      "console.error at http://localhost:3917/x.js: [Extension Host] Failed to fetch data for CIP Extension API",
    ]);
  });
});
