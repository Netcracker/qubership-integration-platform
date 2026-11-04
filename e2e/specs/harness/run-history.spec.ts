/**
 * The earlier runs the workflow downloads before it writes the summary and the email.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { OUTCOMES_FILE, fixedSince, historyOf, outcomesOf, readHistory, testKey } from "../../support/run-history.mjs";
import { TREND_RUNS, trendPoints } from "../../support/run-model.mjs";

test.describe("run history", () => {
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

  // A test pinned with test.fail() after it failed still fails, and Playwright reports it as expected.
  test("lists as fixed the tests the previous run failed", () => {
    const spec = (title: string, status: string, expectedStatus = "passed") => ({ title, file: "a.spec.ts", line: 1, tests: [{ projectName: "api", status, expectedStatus, results: [] }] });
    const report = {
      config: { rootDir: "/e2e/specs" },
      suites: [{ title: "a.spec.ts", specs: [spec("passed", "expected"), spec("flaky", "flaky"), spec("failed", "unexpected"), spec("skipped", "skipped"), spec("pinned", "expected", "failed")] }],
      errors: [],
      stats: { expected: 2, unexpected: 1, flaky: 1, skipped: 1 },
    };
    const previous = {
      runId: "1",
      startedAt: "2026-10-15T02:00:00Z",
      failed: ["passed", "flaky", "failed", "skipped", "pinned", "gone"].map((title) => testKey("api", "a.spec.ts", title)),
      skipped: [],
    };

    expect(fixedSince(report, previous)).toEqual([testKey("api", "a.spec.ts", "passed"), testKey("api", "a.spec.ts", "flaky")]);
    expect(fixedSince(report, undefined)).toEqual([]);
  });

  test("records the status, product counts, duration, and commit", () => {
    const spec = (title: string, projectName: string, status: string) => ({ title, file: "a.spec.ts", line: 1, tests: [{ projectName, status, results: [] }] });
    const report = {
      config: {
        rootDir: "/e2e/specs",
        metadata: { gitCommit: { hash: "c1b74ffdba5f" } },
        projects: [{ name: "seed", metadata: { group: "setup" as const } }, { name: "api" }],
      },
      suites: [{ title: "a.spec.ts", specs: [spec("seeds", "seed", "expected"), spec("a", "api", "expected"), spec("b", "api", "unexpected"), spec("c", "api", "flaky")] }],
      errors: [],
      stats: { startTime: "2026-10-26T19:27:16.319Z", duration: 2_294_080.48, expected: 2, unexpected: 1, flaky: 1, skipped: 0 },
    };

    expect(outcomesOf(report, {}, { status: "failed" })).toMatchObject({
      sha: "c1b74ffdba5f",
      startedAt: "2026-10-26T19:27:16.319Z",
      durationMs: 2_294_080,
      finishedAt: "2026-10-26T20:05:30.399Z",
      status: "failed",
      counts: { passed: 1, failed: 1, pins: 0, flaky: 1, skipped: 0 },
    });
    // Without .last-run.json, a failed test decides the status.
    expect(outcomesOf(report, { GITHUB_SHA: "0123456" }).status).toBe("failed");
    expect(outcomesOf(report, { GITHUB_SHA: "0123456" }).sha).toBe("0123456");
    // Reconciliation fails a run whose tests passed.
    expect(outcomesOf(report, {}, { status: "passed" }, { problems: 1 }).status).toBe("failed");
    expect(outcomesOf(report, {}, { status: "passed" }, { problems: 0 }).status).toBe("passed");
  });

  test("a trend leaves out a run written before the counts", () => {
    const run = (runId: string, day: number, counted = true) => ({
      runId,
      startedAt: `2026-10-${String(day).padStart(2, "0")}T02:00:00Z`,
      failed: [],
      skipped: [],
      ...(counted ? { status: "passed", durationMs: 60_000, counts: { passed: 1, failed: 0, pins: 0, flaky: 0, skipped: 0 } } : {}),
    });
    const history = Array.from({ length: 14 }, (_, i) => run(String(20 - i), 20 - i, i !== 2));

    const points = trendPoints(run("21", 21), history);
    expect(points).toHaveLength(TREND_RUNS);
    expect(points.map((each) => each.runId)).toEqual(["10", "11", "12", "13", "14", "15", "16", "17", "19", "20", "21"].slice(-TREND_RUNS));
    expect(points.at(-1)?.current).toBe(true);
    expect(trendPoints(run("21", 21), [run("20", 20, false)])).toHaveLength(1);
  });
});
