/**
 * Writes `test-results/summary.md` from the run's `report.json`, `.last-run.json`, and `stack.json`,
 * for the CI step summary and the failure email. A missing file leaves its sections out.
 *
 *     npm run --silent run-summary [-- <dir>] >> "$GITHUB_OUTPUT"   # dir defaults to test-results/
 *
 * Stdout carries what the failure email names in its subject, as one `failure=<text>` line in the
 * `$GITHUB_OUTPUT` format, so the workflow reads it as a step output and no second file is needed:
 * the failed and total test counts, or else the first line of the first error raised outside any
 * test (a provisioning failure, the global timeout). It prints nothing when `report.json` is
 * missing or shows neither, and the workflow then names the failed step.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SUMMARY_FILE = "summary.md";
export const FAILED_TESTS_LIMIT = 50;

/**
 * @typedef {{ message?: string }} ReportError
 * @typedef {{ errors?: ReportError[] }} TestResult
 * @typedef {{ projectName: string, status: string, results: TestResult[] }} ReportTest
 * @typedef {{ title: string, file: string, line: number, tests: ReportTest[] }} ReportSpec
 * @typedef {{ title: string, specs?: ReportSpec[], suites?: ReportSuite[] }} ReportSuite
 * @typedef {{ expected: number, unexpected: number, flaky: number, skipped: number }} ReportStats
 * @typedef {{ suites: ReportSuite[], errors: ReportError[], stats: ReportStats }} Report
 * @typedef {{ status: string }} LastRun
 * @typedef {{ absent?: { name: string, reason: string }[] }} Stack
 * @typedef {{ project: string, location: string, title: string, error: string }} FailedTest
 */

/** @param {string} text */
const stripAnsi = (text) => text.replace(/\u001b\[[0-9;]*m/g, "");

/** @param {string} text */
const cell = (text) => text.replace(/\|/g, "\\|");

/**
 * @param {ReportError | undefined} error
 * @returns {string}
 */
function firstLine(error) {
  return stripAnsi(error?.message ?? "").split("\n").find((line) => line.trim() !== "")?.trim() ?? "";
}

/**
 * The file-level suite's title is the file, so only `describe` titles join the spec's.
 *
 * @param {Report} report
 * @returns {FailedTest[]}
 */
export function failedTests(report) {
  /** @type {FailedTest[]} */
  const failed = [];
  /**
   * @param {ReportSuite} suite
   * @param {string[]} titles
   */
  const walk = (suite, titles) => {
    for (const spec of suite.specs ?? []) {
      for (const test of spec.tests) {
        if (test.status !== "unexpected") continue;
        failed.push({
          project: test.projectName,
          location: `${spec.file}:${spec.line}`,
          title: [...titles, spec.title].join(" › "),
          error: firstLine(test.results.at(-1)?.errors?.[0]),
        });
      }
    }
    for (const child of suite.suites ?? []) walk(child, [...titles, child.title]);
  };
  for (const suite of report.suites) walk(suite, []);
  return failed;
}

/**
 * @param {{ report?: Report, lastRun?: LastRun, stack?: Stack }} run
 * @returns {string}
 */
export function summaryMarkdown({ report, lastRun, stack }) {
  /** @type {string[]} */
  const parts = [];
  if (lastRun) parts.push(`Status: **${lastRun.status}**`);
  if (report) {
    const { expected, unexpected, flaky, skipped } = report.stats;
    parts.push(
      [
        "| Passed | Failed | Flaky | Skipped |",
        "| --- | --- | --- | --- |",
        `| ${expected} | ${unexpected} | ${flaky} | ${skipped} |`,
      ].join("\n"),
    );
    const failed = failedTests(report);
    if (failed.length > 0) {
      const rows = failed
        .slice(0, FAILED_TESTS_LIMIT)
        .map((each) => `| ${cell(each.project)} | \`${each.location}\` | ${cell(each.title)} | ${cell(each.error)} |`);
      const table = ["| Project | Location | Test | First error line |", "| --- | --- | --- | --- |", ...rows];
      let section = `### Failed tests\n\n${table.join("\n")}`;
      if (failed.length > FAILED_TESTS_LIMIT) {
        section += `\n\n${failed.length - FAILED_TESTS_LIMIT} more failed. The HTML report in the \`e2e-report\` artifact lists every failure.`;
      }
      parts.push(section);
    }
    // Playwright colors the messages with ANSI escapes.
    if (report.errors.length > 0) {
      const messages = report.errors.map((each) => stripAnsi(each.message ?? "")).join("\n\n");
      parts.push(`### Errors\n\n\`\`\`text\n${messages}\n\`\`\``);
    }
  }
  if (stack?.absent?.length) {
    parts.push(`### Absent\n\n${stack.absent.map((each) => `- \`${each.name}\`: ${each.reason}`).join("\n")}`);
  }
  return parts.join("\n\n") + "\n";
}

/**
 * @param {string} file
 * @returns {any}
 */
const readIfPresent = (file) => (fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : undefined);

/**
 * What the failure email names: the failed and total test counts, or else the first global error.
 *
 * @param {Report} report
 * @returns {string | undefined}
 */
export function failureOf(report) {
  const { expected, unexpected, flaky, skipped } = report.stats;
  if (unexpected > 0) return `${unexpected} of ${expected + unexpected + flaky + skipped} tests`;
  return firstLine(report.errors[0]) || undefined;
}

/**
 * Writes the summary into `dir` and returns what the failure email names.
 *
 * @param {string} dir
 * @returns {string | undefined}
 */
export function writeRunSummary(dir) {
  /** @type {Report | undefined} */
  const report = readIfPresent(path.join(dir, "report.json"));
  const lastRun = readIfPresent(path.join(dir, ".last-run.json"));
  const stack = readIfPresent(path.join(dir, "stack.json"));
  fs.writeFileSync(path.join(dir, SUMMARY_FILE), summaryMarkdown({ report, lastRun, stack }));
  return report && failureOf(report);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  const failure = writeRunSummary(dir);
  if (failure) console.log(`failure=${failure}`);
}
