/**
 * The earlier runs the workflow downloads before it writes the summary and the email.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { OUTCOMES_FILE, fixedSince, historyOf, readHistory, testKey } from "../../support/run-history.mjs";

test.describe("run history", { tag: ["@harness"] }, () => {
  test("lists the runs latest first and skips an unreadable one", () => {
    const dir = test.info().outputPath("history");
    const write = (run: string, content: string) => {
      fs.mkdirSync(path.join(dir, run), { recursive: true });
      fs.writeFileSync(path.join(dir, run, OUTCOMES_FILE), content);
    };
    write("10", JSON.stringify({ runId: "10", startedAt: "2026-10-14T02:00:00Z", failed: [], skipped: [] }));
    write("12", JSON.stringify({ runId: "12", startedAt: "2026-10-16T02:00:00Z", failed: [], skipped: [] }));
    write("11", '{"runId": "11", "fail');
    // A run whose artifact had expired leaves its directory empty.
    fs.mkdirSync(path.join(dir, "9"));

    expect(readHistory(dir).map((each) => each.runId)).toEqual(["12", "10"]);
    expect(readHistory(undefined)).toEqual([]);
    expect(readHistory(path.join(dir, "absent"))).toEqual([]);
  });

  // A run that skipped the test is passed over in every kind.
  test("classifies a failed test as recurring, intermittent, or new", () => {
    const key = "api › a.spec.ts › case";
    const run = (runId: string, outcome: "failed" | "passed" | "skipped") => ({
      runId,
      startedAt: "2026-10-16T02:00:00Z",
      failed: outcome === "failed" ? [key] : [],
      skipped: outcome === "skipped" ? [key] : [],
    });

    expect(historyOf(key, [run("3", "failed"), run("2", "skipped"), run("1", "failed"), run("0", "passed")])).toMatchObject({ kind: "recurring", runs: 2, since: { runId: "1" }, always: false });
    expect(historyOf(key, [run("1", "failed"), run("0", "failed")])).toMatchObject({ kind: "recurring", runs: 2, since: { runId: "0" }, always: true });
    expect(historyOf(key, [run("3", "passed"), run("2", "failed"), run("1", "skipped"), run("0", "failed")])).toMatchObject({ kind: "intermittent", previous: { runId: "3" }, failures: 2, runs: 3 });
    expect(historyOf(key, [run("1", "skipped"), run("0", "passed")])).toMatchObject({ kind: "new", previous: { runId: "0" } });
    expect(historyOf(key, [run("0", "skipped")])).toBeUndefined();
  });

  test("lists as fixed the tests the previous run failed", () => {
    const spec = (title: string, status: string) => ({ title, file: "a.spec.ts", line: 1, tests: [{ projectName: "api", status, results: [] }] });
    const report = {
      config: { rootDir: "/e2e/specs" },
      suites: [{ title: "a.spec.ts", specs: [spec("passed", "expected"), spec("flaky", "flaky"), spec("failed", "unexpected"), spec("skipped", "skipped")] }],
      errors: [],
      stats: { expected: 1, unexpected: 1, flaky: 1, skipped: 1 },
    };
    const previous = {
      runId: "1",
      startedAt: "2026-10-15T02:00:00Z",
      failed: ["passed", "flaky", "failed", "skipped", "gone"].map((title) => testKey("api", "a.spec.ts", title)),
      skipped: [],
    };

    expect(fixedSince(report, previous)).toEqual([testKey("api", "a.spec.ts", "passed"), testKey("api", "a.spec.ts", "flaky")]);
    expect(fixedSince(report, undefined)).toEqual([]);
  });
});
