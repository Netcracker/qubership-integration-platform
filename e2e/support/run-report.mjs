/**
 * Reading the JSON report a run leaves in `test-results/`, for the scripts the workflow runs after
 * the suite: `run-summary.mjs`, `run-email.mjs`, and `run-history.mjs`.
 */
import fs from "node:fs";

/**
 * @typedef {{ message?: string, location?: { file: string, line: number } }} ReportError
 * @typedef {{ name: string, contentType?: string, body?: string }} Attachment
 * @typedef {{ status?: string, startTime?: string, duration?: number, errors?: ReportError[], attachments?: Attachment[] }} TestResult
 * @typedef {{ type: string, description?: string }} Annotation
 * @typedef {{ projectName: string, status: string, annotations?: Annotation[], results: TestResult[] }} ReportTest
 * @typedef {{ title: string, file: string, line: number, tests: ReportTest[] }} ReportSpec
 * @typedef {{ title: string, specs?: ReportSpec[], suites?: ReportSuite[] }} ReportSuite
 * @typedef {{ name: string, metadata?: { dependencies?: string[], stack?: boolean } }} ReportProject
 * @typedef {{ target?: string, gitCommit?: { subject?: string } }} ReportMetadata
 * @typedef {{ rootDir: string, metadata?: ReportMetadata, projects?: ReportProject[] }} ReportConfig
 * @typedef {{ startTime?: string, duration?: number, expected: number, unexpected: number, flaky: number, skipped: number }} ReportStats
 * @typedef {{ config: ReportConfig, suites: ReportSuite[], errors: ReportError[], stats: ReportStats }} Report
 */

/** @param {string} text */
export const stripAnsi = (text) => text.replace(/\u001b\[[0-9;]*m/g, "");

/**
 * @param {string} file
 * @returns {any}
 */
export const readIfPresent = (file) => (fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : undefined);

/**
 * Visits every test with its title. The file-level suite's title is the file, so only `describe`
 * titles join the spec's.
 *
 * @param {Report} report
 * @param {(spec: ReportSpec, test: ReportTest, title: string) => void} visit
 */
export function eachTest(report, visit) {
  /**
   * @param {ReportSuite} suite
   * @param {string[]} titles
   */
  const walk = (suite, titles) => {
    for (const spec of suite.specs ?? []) for (const test of spec.tests) visit(spec, test, [...titles, spec.title].join(" › "));
    for (const child of suite.suites ?? []) walk(child, [...titles, child.title]);
  };
  for (const suite of report.suites) walk(suite, []);
}

/** The projects that test the harness rather than the product, counted apart from the run's own tests. */
export const HARNESS_PROJECTS = ["harness"];

/**
 * @typedef {{ expected: number, unexpected: number, flaky: number, skipped: number }} OutcomeCounts
 */

/**
 * The run's counts with the tests of `exclude` taken out, and the counts of those tests alone.
 * `report.stats` tallies every project and cannot be filtered by one, so the excluded tests are
 * counted off the report and subtracted.
 *
 * @param {Report} report
 * @param {string[]} exclude project names
 * @returns {{ counted: OutcomeCounts, excluded: OutcomeCounts }}
 */
export function countsByProject(report, exclude) {
  /** @type {OutcomeCounts} */
  const excluded = { expected: 0, unexpected: 0, flaky: 0, skipped: 0 };
  eachTest(report, (_spec, test) => {
    if (exclude.includes(test.projectName)) excluded[/** @type {keyof OutcomeCounts} */ (test.status)]++;
  });
  const { expected, unexpected, flaky, skipped } = report.stats;
  return {
    counted: {
      expected: expected - excluded.expected,
      unexpected: unexpected - excluded.unexpected,
      flaky: flaky - excluded.flaky,
      skipped: skipped - excluded.skipped,
    },
    excluded,
  };
}

/**
 * Which tests pin a product defect that no longer reproduces. A `test.fail()` case that passed is
 * `unexpected`, with a passed result and the `fail` annotation. A project that needs no stack pins
 * no product defect: its pins test the pinning itself, so a pass there is an ordinary failure.
 *
 * @param {Report} report
 * @returns {(test: ReportTest) => boolean}
 */
export function fixedPinIn(report) {
  const stackless = new Set((report.config.projects ?? []).filter((each) => each.metadata?.stack === false).map((each) => each.name));
  return (test) =>
    test.status === "unexpected" &&
    !stackless.has(test.projectName) &&
    (test.annotations ?? []).some((each) => each.type === "fail") &&
    test.results.at(-1)?.status === "passed";
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** @param {string} iso */
export function shortDate(iso) {
  const date = new Date(iso);
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCDate()}`;
}

/** @param {string} iso */
export function utcDate(iso) {
  const date = new Date(iso);
  const time = `${String(date.getUTCHours()).padStart(2, "0")}:${String(date.getUTCMinutes()).padStart(2, "0")}`;
  return `${shortDate(iso)}, ${date.getUTCFullYear()}, ${time} UTC`;
}
