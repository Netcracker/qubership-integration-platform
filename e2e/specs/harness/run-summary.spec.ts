/**
 * The run summary the CI step summary carries, read off reports trimmed from a green and a red CI run.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import {
  FAILED_TESTS_LIMIT,
  METASPACE_ATTACHMENT,
  PEAKS_ATTACHMENT,
  SUMMARY_FILE,
  failedTests,
  summaryMarkdown,
  writeRunSummary,
} from "../../support/run-summary.mjs";
import { OUTCOMES_FILE, testKey } from "../../support/run-history.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, "../../fixtures/run-summary");
const SCRIPT = path.resolve(HERE, "../../support/run-summary.mjs");
const RUN_FILES = ["report.json", ".last-run.json", "stack.json"];

function copyRun(run: "green" | "red", files = RUN_FILES): string {
  const dir = test.info().outputPath(run);
  fs.mkdirSync(dir, { recursive: true });
  for (const file of files) fs.copyFileSync(path.join(FIXTURES, run, file), path.join(dir, file));
  return dir;
}

function readRun(run: "green" | "red") {
  const read = (file: string) => JSON.parse(fs.readFileSync(path.join(FIXTURES, run, file), "utf8"));
  return { report: read("report.json"), lastRun: read(".last-run.json"), stack: read("stack.json") };
}

/** The red run's one failed spec, and the suite that holds it. */
function failingSpec(report: ReturnType<typeof readRun>["report"]) {
  const admin = report.suites.find((suite: { file: string }) => suite.file === "ui/admin-tools.spec.ts");
  const failing = admin.specs.find((spec: { title: string }) => spec.title.startsWith("a live exchange"));
  return { admin, failing };
}

test.describe("run summary", { tag: ["@harness"] }, () => {
  test("a green run shows its status, counts, and no failed list", () => {
    const dir = copyRun("green");

    writeRunSummary(dir);
    const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
    expect(summary).toContain("Status: **passed**");
    expect(summary).toContain("| 944 | 0 | 0 |");
    expect(summary).not.toContain("Harness:");
    expect(summary).not.toContain("### Failed tests");
    expect(summary).not.toContain("### Errors");
  });

  test("a red run lists each failed test with its first error line", () => {
    const dir = copyRun("red");

    writeRunSummary(dir);
    const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
    expect(summary).toContain("Status: **failed**");
    expect(summary).toContain("| 895 | 1 | 48 |");
    expect(summary).not.toContain("Pins to remove");
    expect(summary).toContain(
      "| ui | `ui/admin-tools.spec.ts:126` | a live exchange is killed only after its termination is confirmed |  | " +
        "Error: expect(received).toBe(expected) // Object.is equality |",
    );
    expect(summary).not.toContain("\u001b");
    // The red run predates the broker subcharts, so its stack.json still lists brokers-seed as absent.
    expect(summary).toContain("### Absent\n\n- `brokers-seed`: the brokers have no chart on the k8s target");
  });

  test("a failed test under a describe carries the describe path", () => {
    const { report } = readRun("red");
    const misc = report.suites.find((suite: { file: string }) => suite.file === "runtime/misc-elements.spec.ts");
    misc.suites[0].specs[0].tests[0].status = "unexpected";

    expect(failedTests(report).map((each) => each.title)).toContain(
      "context-storage › operation=SET stores a value that operation=GET reads back into the body",
    );
  });

  // A failed harness test is still listed.

  test("counts harness tests on a line of their own", () => {
    const { report } = readRun("green");
    const [spec] = report.suites[0].specs;
    const harness = (title: string, status: string) => ({ ...spec, title, tests: [{ ...spec.tests[0], projectName: "harness", status, results: [{ status: status === "expected" ? "passed" : "failed", errors: [] }] }] });
    report.suites[0].specs.push(harness("h1", "expected"), harness("h2", "expected"), harness("h3", "unexpected"));
    // Playwright's stats tally every project.
    report.stats.expected += 2;
    report.stats.unexpected += 1;

    const summary = summaryMarkdown({ report });
    expect(summary).toContain("| 944 | 0 | 0 |\n\nHarness: 2 passed, 1 failed");
    expect(failedTests(report).map((each) => `${each.project} ${each.title}`)).toEqual(["harness h3"]);
  });

  test("lists an unexpected test, never a skipped or flaky one", () => {
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    const [failingTest] = failing.tests;
    const failedResult = failingTest.results[0];
    admin.specs.push(
      { ...failing, title: "skipped", tests: [{ ...failingTest, status: "skipped", results: [{ ...failedResult, status: "skipped" }] }] },
      { ...failing, title: "flaky", tests: [{ ...failingTest, status: "flaky", results: [failedResult, { ...failedResult, status: "passed", errors: [] }] }] },
    );

    expect(failedTests(report).map((each) => each.title)).toEqual([failing.title]);
  });

  test("escapes a pipe in a title or an error line", () => {
    const { report } = readRun("red");
    const { failing } = failingSpec(report);
    failing.title = "a | b";
    failing.tests[0].results[0].errors = [{ message: "Error: c | d" }];

    expect(summaryMarkdown({ report })).toContain("| a \\| b |  | Error: c \\| d |");
  });

  test("cuts the failed list at its limit and links the HTML report", () => {
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    const extra = 10;
    admin.specs = Array.from({ length: FAILED_TESTS_LIMIT + extra }, (_, i) => ({ ...failing, line: i + 1 }));

    const summary = summaryMarkdown({ report });
    expect(summary.match(/^\| ui \|/gm)).toHaveLength(FAILED_TESTS_LIMIT);
    expect(summary).toContain(`\`ui/admin-tools.spec.ts:${FAILED_TESTS_LIMIT}\``);
    expect(summary).not.toContain(`\`ui/admin-tools.spec.ts:${FAILED_TESTS_LIMIT + 1}\``);
    expect(summary).toContain(`${extra} more failed. The HTML report in the \`e2e-report\` artifact lists every failure.`);
  });

  test("lists global errors with their ANSI colors stripped", () => {
    const { report } = readRun("green");
    report.errors = [{ message: "Error: \u001b[31mTimed out waiting 8400s for the test suite to finish\u001b[39m" }];

    expect(summaryMarkdown({ report })).toContain(
      "### Errors\n\n```text\nError: Timed out waiting 8400s for the test suite to finish\n```",
    );
  });

  test("a missing run file leaves out only its own sections", () => {
    const onlyLastRun = copyRun("red", [".last-run.json"]);
    writeRunSummary(onlyLastRun);
    expect(fs.readFileSync(path.join(onlyLastRun, SUMMARY_FILE), "utf8")).toBe("Status: **failed**\n");

    const noStack = copyRun("green", ["report.json"]);
    writeRunSummary(noStack);
    const summary = fs.readFileSync(path.join(noStack, SUMMARY_FILE), "utf8");
    expect(summary).not.toContain("Status:");
    expect(summary).toContain("| 944 | 0 | 0 |");
  });

  test("the script writes the summary and prints nothing", () => {
    const red = copyRun("red");
    expect(execFileSync(process.execPath, [SCRIPT, red], { encoding: "utf8" })).toBe("");
    expect(fs.readFileSync(path.join(red, SUMMARY_FILE), "utf8")).toContain("### Failed tests");
  });

  const RUNS = "https://github.com/Netcracker/qubership-integration-platform/actions/runs";
  const FAILING_KEY = testKey("ui", "ui/admin-tools.spec.ts", "a live exchange is killed only after its termination is confirmed");

  /** An earlier run's outcomes, as the history directory holds them. */
  const earlier = (runId: string, startedAt: string, failed: string[] = [], skipped: string[] = []) => ({ runId, url: `${RUNS}/${runId}`, startedAt, failed, skipped });

  test("marks a failure as new or as failing since a linked run", () => {
    const { report } = readRun("red");
    const passedBefore = summaryMarkdown({ report, history: [earlier("2", "2026-10-14T02:00:00Z"), earlier("1", "2026-10-13T02:00:00Z")] });
    expect(passedBefore).toContain("| a live exchange is killed only after its termination is confirmed | new | Error:");
    expect(passedBefore).toContain(`Against the [previous run](${RUNS}/2) of Oct 14: 1 new failure, 0 still failing, 0 fixed.`);

    // A run that skipped the test says nothing either way, and the streak reads through it.
    const history = [earlier("3", "2026-10-15T02:00:00Z", [FAILING_KEY]), earlier("2", "2026-10-14T02:00:00Z", [], [FAILING_KEY]), earlier("1", "2026-10-13T02:00:00Z", [FAILING_KEY]), earlier("0", "2026-10-12T02:00:00Z")];
    const still = summaryMarkdown({ report, history });
    expect(still).toContain(`| 3 runs, since [Oct 13](${RUNS}/1) |`);
    expect(still).toContain("0 new failures, 1 still failing, 0 fixed.");

    // Failing in every run kept, the start lies before the history.
    expect(summaryMarkdown({ report, history: history.slice(0, 3) })).toContain(`| 3 runs, since [Oct 13](${RUNS}/1) or earlier |`);
  });

  test("marks an intermittent failure and counts it in the header", () => {
    const { report } = readRun("red");
    const history = [earlier("3", "2026-10-15T02:00:00Z"), earlier("1", "2026-10-13T02:00:00Z", [FAILING_KEY])];
    const summary = summaryMarkdown({ report, history });
    expect(summary).toContain("| intermittent, failed 1 of the 2 runs before | Error:");
    expect(summary).toContain(`Against the [previous run](${RUNS}/3) of Oct 15: 0 new failures, 1 intermittent, 0 still failing, 0 fixed.`);
  });

  test("counts the fixed tests in the header", () => {
    const { report } = readRun("green");
    const fixed = testKey("runtime", "runtime/misc-elements.spec.ts", "httpMethod=POST: an http sender sends POST");
    const summary = summaryMarkdown({ report, history: [earlier("2", "2026-10-14T02:00:00Z", [fixed])] });
    expect(summary).toContain("0 new failures, 0 still failing, 1 fixed.");
  });

  test("writes the outcomes the next run reads as history", () => {
    const dir = copyRun("red");
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    admin.specs.push({ ...failing, title: "skipped", tests: [{ ...failing.tests[0], status: "skipped" }] });
    fs.writeFileSync(path.join(dir, "report.json"), JSON.stringify(report));

    writeRunSummary(dir, { GITHUB_REPOSITORY: "Netcracker/qubership-integration-platform", GITHUB_RUN_ID: "38916652673", GITHUB_SHA: "8b5ae2c" });
    const outcomes = JSON.parse(fs.readFileSync(path.join(dir, OUTCOMES_FILE), "utf8"));
    expect(outcomes).toEqual({
      runId: "38916652673",
      url: `${RUNS}/38916652673`,
      sha: "8b5ae2c",
      startedAt: report.stats.startTime,
      failed: [FAILING_KEY],
      skipped: [testKey("ui", "ui/admin-tools.spec.ts", "skipped")],
    });
  });

  test("shows the coverage reconcile wrote and its verdict", () => {
    const coverage = { target: "k8s", elements: { covered: 204, inScope: 300, outOfScope: 136, shared: 3 }, operations: { total: 303, reached: 301, covered: 301 }, problems: 0 };
    expect(summaryMarkdown({ coverage })).toContain(
      "### Coverage\n\nElements: 204 of 300 registry rows in scope covered on k8s (136 out of scope, 3 shared). " +
        "API operations: 301 of 303 covered, 301 reached. Reconciliation passed.",
    );
    expect(summaryMarkdown({ coverage: { ...coverage, problems: 2 } })).toContain("Reconciliation found 2 problems; the reconcile step lists them.");
  });

  test("shows the peaks and Metaspace the envelope spec attached", () => {
    const { report } = readRun("green");
    const body = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64");
    const MIB = 1024 ** 2;
    report.suites[0].specs[0].tests[0].results[0].attachments = [
      { name: PEAKS_ATTACHMENT, contentType: "application/json", body: body([{ role: "engine", memoryBytes: 1079 * MIB, cpuPercent: 324.4, samples: 300, band: { memoryBytes: 3072 * MIB, cpuPercent: 900 } }]) },
      { name: METASPACE_ATTACHMENT, contentType: "application/json", body: body([{ role: "engine", usedBytes: 151 * MIB, maxBytes: 512 * MIB, band: 0.95 }]) },
    ];
    expect(summaryMarkdown({ report })).toContain("| engine | 1079 MiB of 3072 MiB | 324% of 900% | 151 MiB of 512 MiB, 29% of 95% |");
    expect(summaryMarkdown(readRun("green"))).not.toContain("<summary>Resources</summary>");
  });

  test("times each project and lists the slowest tests", () => {
    const summary = summaryMarkdown(readRun("green"));
    expect(summary).toContain("Took 18.7 min.");
    expect(summary).toMatch(/<summary>Time by project<\/summary>\n\n\| Project \| Tests \| Started \| Took \|/);
    expect(summary).toMatch(/\| `schema` \| 2 \| \+6:14 \| 0:00 \|/);
    expect(summary).toContain("<summary>Slowest 5 tests</summary>");
  });

  // A passed pin in a project that needs no stack stays a failure.

  test("lists a passed test.fail() case as a pin to remove", () => {
    const { report } = readRun("green");
    const [spec] = report.suites[0].specs;
    const passedPin = (projectName: string, description?: string) => ({ ...spec.tests[0], projectName, status: "unexpected", annotations: [{ type: "fail", description }], results: [{ status: "passed", errors: [] }] });
    const struckPin = { ...spec.tests[0], projectName: "api", status: "expected", annotations: [{ type: "fail" }], results: [{ status: "failed", errors: [] }] };
    // notTheKnownDefect fails a pinned case for real.
    const realFailure = { ...struckPin, status: "unexpected", results: [{ status: "failed", errors: [{ message: "Error: a new failure" }] }] };
    report.config.projects = [{ name: "schema", metadata: { stack: false } }];
    report.stats.unexpected = 4;
    report.suites[0].specs.push(
      { ...spec, title: "a", line: 8, tests: [passedPin("runtime-micro", "the micro engine drops it")] },
      { ...spec, title: "b", line: 9, tests: [passedPin("api")] },
      { ...spec, title: "c", line: 10, tests: [passedPin("schema")] },
      { ...spec, title: "d", line: 11, tests: [struckPin] },
      { ...spec, title: "e", line: 12, tests: [realFailure] },
    );
    const summary = summaryMarkdown({ report });
    expect(summary).toContain("| Passed | Failed | Skipped | Pins to remove |\n| --- | --- | --- | --- |\n| 944 | 2 | 0 | 2 |");
    expect(summary).toContain("### Pins to remove\n\nEach `test.fail()` case below passed");
    expect(summary).toContain(`| runtime-micro | \`${spec.file}:8\` | a | the micro engine drops it |`);
    expect(summary).toContain(`| api | \`${spec.file}:9\` | b |  |`);
    expect(failedTests(report).map((each) => each.title)).toEqual(["c", "e"]);
    expect(summary).not.toContain("Known defects");
  });
});
