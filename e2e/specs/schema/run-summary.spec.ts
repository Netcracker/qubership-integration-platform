/**
 * The run summary the CI step summary and the failure email carry, read off reports trimmed from a
 * green and a red CI run.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import {
  FAILED_TESTS_LIMIT,
  SUMMARY_FILE,
  failedTests,
  failureOf,
  summaryMarkdown,
  writeRunSummary,
} from "../../support/run-summary.mjs";

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

test("a green run gets its status and counts and no failed list", { tag: ["@infra", "@tier2"] }, () => {
  const dir = copyRun("green");

  expect(writeRunSummary(dir)).toBeUndefined();
  const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
  expect(summary).toContain("Status: **passed**");
  expect(summary).toContain("| 944 | 0 | 0 | 0 |");
  expect(summary).not.toContain("### Failed tests");
  expect(summary).not.toContain("### Errors");
});

test("a red run lists each failed test with its project, location, title, and first error line", { tag: ["@infra", "@tier2"] }, () => {
  const dir = copyRun("red");

  expect(writeRunSummary(dir)).toBe("1 of 944 tests");
  const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
  expect(summary).toContain("Status: **failed**");
  expect(summary).toContain("| 895 | 1 | 0 | 48 |");
  expect(summary).toContain(
    "| ui | `ui/admin-tools.spec.ts:126` | a live exchange is killed only after its termination is confirmed | " +
      "Error: expect(received).toBe(expected) // Object.is equality |",
  );
  expect(summary).not.toContain("\u001b");
  // The red run predates the broker subcharts, so its stack.json still lists brokers-seed as absent.
  expect(summary).toContain("### Absent\n\n- `brokers-seed`: the brokers have no chart on the k8s target");
});

test("a failed test inside a describe carries the describe title", { tag: ["@infra", "@tier2"] }, () => {
  const { report } = readRun("red");
  const misc = report.suites.find((suite: { file: string }) => suite.file === "runtime/misc-elements.spec.ts");
  misc.suites[0].specs[0].tests[0].status = "unexpected";

  expect(failedTests(report).map((each) => each.title)).toContain(
    "context-storage › operation=SET stores a value that operation=GET reads back into the body",
  );
});

test("only an unexpected test is listed, never a skipped or a flaky one", { tag: ["@infra", "@tier2"] }, () => {
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

test("a pipe in a title or an error line does not split its table cell", { tag: ["@infra", "@tier2"] }, () => {
  const { report } = readRun("red");
  const { failing } = failingSpec(report);
  failing.title = "a | b";
  failing.tests[0].results[0].errors = [{ message: "Error: c | d" }];

  expect(summaryMarkdown({ report })).toContain("| a \\| b | Error: c \\| d |");
});

test("the failed list stops at its limit and points at the HTML report", { tag: ["@infra", "@tier2"] }, () => {
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

test("global errors appear with their ANSI colors stripped", { tag: ["@infra", "@tier2"] }, () => {
  const { report } = readRun("green");
  report.errors = [{ message: "Error: \u001b[31mTimed out waiting 8400s for the test suite to finish\u001b[39m" }];

  expect(summaryMarkdown({ report })).toContain(
    "### Errors\n\n```text\nError: Timed out waiting 8400s for the test suite to finish\n```",
  );
});

test("without a failed test, the failure the email names is the first global error", { tag: ["@infra", "@tier2"] }, () => {
  const { report } = readRun("green");
  expect(failureOf(report)).toBeUndefined();

  report.errors = [
    { message: "\u001b[31mError: Command failed: helm upgrade --install qip\u001b[39m\n    at provision" },
    { message: "Error: a second one" },
  ];
  expect(failureOf(report)).toBe("Error: Command failed: helm upgrade --install qip");

  const { report: red } = readRun("red");
  red.errors = report.errors;
  expect(failureOf(red), "a failed test wins over a global error").toBe("1 of 944 tests");
});

test("a missing run file leaves out only its own sections", { tag: ["@infra", "@tier2"] }, () => {
  const onlyLastRun = copyRun("red", [".last-run.json"]);
  expect(writeRunSummary(onlyLastRun)).toBeUndefined();
  expect(fs.readFileSync(path.join(onlyLastRun, SUMMARY_FILE), "utf8")).toBe("Status: **failed**\n");

  const noStack = copyRun("green", ["report.json"]);
  expect(writeRunSummary(noStack)).toBeUndefined();
  const summary = fs.readFileSync(path.join(noStack, SUMMARY_FILE), "utf8");
  expect(summary).not.toContain("Status:");
  expect(summary).toContain("| 944 | 0 | 0 | 0 |");
});

test("the script prints the failure as a step output, and nothing for a green run", { tag: ["@infra", "@tier2"] }, () => {
  const red = copyRun("red");
  expect(execFileSync(process.execPath, [SCRIPT, red], { encoding: "utf8" })).toBe("failure=1 of 944 tests\n");
  expect(fs.existsSync(path.join(red, SUMMARY_FILE))).toBe(true);

  const green = copyRun("green");
  expect(execFileSync(process.execPath, [SCRIPT, green], { encoding: "utf8" })).toBe("");
});
