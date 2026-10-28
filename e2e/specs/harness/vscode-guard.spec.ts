/**
 * What the `vscode` guard lets through: the measured allowlist, and the errors a case declares with
 * `expectConsoleError`. Also which hosts the page reaches rather than an empty answer.
 */
import { test, expect } from "@playwright/test";
import { ALLOWED, guardFailures, isLocal, withAnyStack, type VsCodeProblem } from "../../support/vscode-guard.js";

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

  test("a pattern with any stack needs the message and a stack", () => {
    const message = "[Extension Host] Chain file x.yaml can't be parsed (CIP) Error: not valid";
    const thrown: VsCodeProblem = { source: "console", message: `${message}\n\tat parse (extension.js:4085:19)\n\tat async open (extension.js:326:32)` };
    expect(guardFailures([thrown, thrown], [withAnyStack(message)])).toEqual([]);
    // The message alone, another message with a stack, and a longer first line are not the declared error.
    const pattern = withAnyStack(message);
    expect(guardFailures([{ source: "console", message }], [pattern])).toEqual([`console.error: ${message}`, `the expected error never arrived: ${pattern}`]);
    const other = `[Extension Host] Chain file y.yaml can't be parsed (CIP) Error: not valid\n\tat parse (extension.js:1:1)`;
    expect(guardFailures([{ source: "console", message: other }], [pattern])).toEqual([`console.error: ${other}`, `the expected error never arrived: ${pattern}`]);
    const longer = `${message} again\n\tat parse (extension.js:1:1)`;
    expect(guardFailures([{ source: "console", message: longer }], [pattern])).toEqual([`console.error: ${longer}`, `the expected error never arrived: ${pattern}`]);
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

  test("only localhost and its subdomains are local", () => {
    expect(isLocal(new URL("http://localhost:3917/static/mount/"))).toBe(true);
    expect(isLocal(new URL("http://v--05k9getbcvv9njg3fp3fdo23nf18c4fhjtoa0e2i0tvkuur2fn8g.localhost:3917/index.html"))).toBe(true);
    expect(isLocal(new URL("https://main.vscode-cdn.net/extensions/chat.json"))).toBe(false);
    expect(isLocal(new URL("https://default.exp-tas.com/vscode/ab"))).toBe(false);
    // A host that only ends in the same letters is elsewhere.
    expect(isLocal(new URL("https://notlocalhost/"))).toBe(false);
    expect(isLocal(new URL("https://localhost.example.com/"))).toBe(false);
  });
});
