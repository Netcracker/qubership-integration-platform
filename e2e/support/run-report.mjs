/**
 * Reading the JSON report a run leaves in `test-results/`, for the scripts the workflow runs after
 * the suite: `run-summary.mjs`, `run-email.mjs`, `run-page.mjs`, and `run-history.mjs`.
 */
import fs from "node:fs";

/**
 * @typedef {{ message?: string, location?: { file: string, line: number } }} ReportError
 * @typedef {{ name: string, contentType?: string, body?: string, path?: string }} Attachment
 * @typedef {{ status?: string, startTime?: string, duration?: number, errors?: ReportError[], attachments?: Attachment[] }} TestResult
 * @typedef {{ type: string, description?: string }} Annotation
 * @typedef {{ projectName: string, status: string, expectedStatus?: string, annotations?: Annotation[], results: TestResult[] }} ReportTest
 * @typedef {{ title: string, file: string, line: number, tests: ReportTest[] }} ReportSpec
 * @typedef {{ title: string, specs?: ReportSpec[], suites?: ReportSuite[] }} ReportSuite
 * @typedef {"product" | "setup" | "suite"} Group
 * @typedef {{ name: string, metadata?: { dependencies?: string[], stack?: boolean, group?: Group } }} ReportProject
 * @typedef {{ target?: string, gitCommit?: { hash?: string, subject?: string } }} ReportMetadata
 * @typedef {{ rootDir: string, metadata?: ReportMetadata, projects?: ReportProject[] }} ReportConfig
 * @typedef {{ startTime?: string, duration?: number, expected: number, unexpected: number, flaky: number, skipped: number }} ReportStats
 * @typedef {{ config: ReportConfig, suites: ReportSuite[], errors: ReportError[], stats: ReportStats }} Report
 *
 * @typedef {{ passed: number, failed: number, pins: number, flaky: number, skipped: number }} Counts
 * @typedef {{ name: string, group: Group, tests: number, failed: number, skipped: number, first?: number, last?: number }} ProjectRow
 * @typedef {{ lines: string[], cut: boolean }} Excerpt
 * @typedef {{ name: string, skipped: number }} IdleProject
 * @typedef {{ cause: string[], projects: IdleProject[] }} NotRun
 * @typedef {{ ms: number, project: string, location: string, title: string }} TimedTest
 */

export const ERROR_LINES = 8;
export const LINE_CHARS = 200;

/** @type {readonly Group[]} */
export const GROUPS = ["product", "setup", "suite"];

/** @type {Record<Group, string>} */
export const GROUP_TITLES = { product: "Product projects", setup: "Setup and teardown", suite: "Suite checks" };

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

/**
 * The group each project declares in its metadata in `playwright.config.ts` and
 * `env/target-setup.ts`. A project that declares none runs product cases.
 *
 * @param {Report} report
 * @returns {(project: string) => Group}
 */
export function groupOf(report) {
  const groups = new Map((report.config.projects ?? []).map((each) => [each.name, each.metadata?.group ?? "product"]));
  return (project) => groups.get(project) ?? "product";
}

/**
 * Which tests pin a product defect that no longer reproduces. A `test.fail()` case that passed is
 * `unexpected`, with a passed result and the `fail` annotation. Only a product project pins a
 * product defect: a suite check's pins test the pinning itself, so a pass there is an ordinary
 * failure.
 *
 * @param {Report} report
 * @returns {(test: ReportTest) => boolean}
 */
export function fixedPinIn(report) {
  const group = groupOf(report);
  return (test) =>
    test.status === "unexpected" &&
    group(test.projectName) === "product" &&
    (test.annotations ?? []).some((each) => each.type === "fail") &&
    test.results.at(-1)?.status === "passed";
}

/** @returns {Counts} */
const zero = () => ({ passed: 0, failed: 0, pins: 0, flaky: 0, skipped: 0 });

/**
 * The counts of each group. The product group's failed count leaves out the pins to remove, which
 * it counts apart; the setup and suite groups hold no pins. `report.stats` tallies every project, so
 * the setup and suite groups are counted off the report and the product counts are what remains of
 * the stats.
 *
 * @param {Report} report
 * @returns {Record<Group, Counts>}
 */
export function groupCounts(report) {
  const group = groupOf(report);
  const isFixedPin = fixedPinIn(report);
  /** @type {Record<Group, Counts>} */
  const counts = { product: zero(), setup: zero(), suite: zero() };
  let productPins = 0;
  eachTest(report, (_spec, test) => {
    const into = group(test.projectName);
    if (into === "product") {
      if (isFixedPin(test)) productPins++;
      return;
    }
    const each = counts[into];
    if (test.status === "expected") each.passed++;
    else if (test.status === "flaky") each.flaky++;
    else if (test.status === "skipped") each.skipped++;
    else if (test.status === "unexpected") each.failed++;
  });
  const { setup, suite } = counts;
  const { expected, unexpected, flaky, skipped } = report.stats;
  counts.product = {
    passed: expected - setup.passed - suite.passed,
    failed: unexpected - setup.failed - suite.failed - productPins,
    pins: productPins,
    flaky: flaky - setup.flaky - suite.flaky,
    skipped: skipped - setup.skipped - suite.skipped,
  };
  return counts;
}

/** @param {Counts} counts */
export const totalOf = (counts) => counts.passed + counts.failed + counts.pins + counts.flaky + counts.skipped;

/**
 * Every project in the order the config declares it, with its group, its tests, and the window its
 * results ran in. A pin to remove is not counted as failed.
 *
 * @param {Report} report
 * @returns {ProjectRow[]}
 */
export function projectRows(report) {
  const group = groupOf(report);
  const isFixedPin = fixedPinIn(report);
  /** @type {Map<string, ProjectRow>} */
  const rows = new Map((report.config.projects ?? []).map((each) => [each.name, { name: each.name, group: group(each.name), tests: 0, failed: 0, skipped: 0 }]));
  eachTest(report, (_spec, test) => {
    const row = rows.get(test.projectName) ?? { name: test.projectName, group: group(test.projectName), tests: 0, failed: 0, skipped: 0 };
    rows.set(test.projectName, row);
    row.tests++;
    if (test.status === "skipped") row.skipped++;
    if (test.status === "unexpected" && !isFixedPin(test)) row.failed++;
    for (const result of test.results) {
      if (!result.startTime || result.duration === undefined) continue;
      const from = Date.parse(result.startTime);
      row.first = Math.min(row.first ?? from, from);
      row.last = Math.max(row.last ?? from, from + result.duration);
    }
  });
  return [...rows.values()];
}

/**
 * Projects that ran none of their tests, grouped by the failed projects they depend on. The JSON
 * report leaves `dependencies` out, so `playwright.config.ts` copies them into each project's
 * metadata; a report without them yields one group with no cause.
 *
 * @param {Report} report
 * @param {ProjectRow[]} rows
 * @returns {NotRun[]}
 */
export function notRunOf(report, rows) {
  const idle = rows.filter((each) => each.tests > 0 && each.skipped === each.tests);
  if (idle.length === 0) return [];
  const dependencies = new Map((report.config.projects ?? []).map((each) => [each.name, each.metadata?.dependencies ?? []]));
  /** @type {Map<string, NotRun>} */
  const groups = new Map();
  for (const each of idle) {
    /** @type {Set<string>} */
    const seen = new Set();
    const pending = [...(dependencies.get(each.name) ?? [])];
    while (pending.length > 0) {
      const next = /** @type {string} */ (pending.pop());
      if (seen.has(next)) continue;
      seen.add(next);
      pending.push(...(dependencies.get(next) ?? []));
    }
    const cause = rows.filter((row) => seen.has(row.name) && row.failed > 0).map((row) => row.name);
    const key = cause.join(" ");
    if (!groups.has(key)) groups.set(key, { cause, projects: [] });
    groups.get(key)?.projects.push({ name: each.name, skipped: each.skipped });
  }
  for (const group of groups.values()) group.projects.sort((a, b) => b.skipped - a.skipped);
  return [...groups.values()];
}

/**
 * The longest test results, the slowest first.
 *
 * @param {Report} report
 * @param {number} limit
 * @returns {TimedTest[]}
 */
export function slowestTests(report, limit) {
  /** @type {TimedTest[]} */
  const tests = [];
  eachTest(report, (spec, test, title) => {
    for (const result of test.results) {
      if (result.duration !== undefined && result.startTime) tests.push({ ms: result.duration, project: test.projectName, location: `${spec.file}:${spec.line}`, title });
    }
  });
  return tests.sort((a, b) => b.ms - a.ms).slice(0, limit);
}

/**
 * The first lines of an error, ANSI colors and blank lines stripped. `expect(value, message)` prints
 * its message twice, once after `Error: `, so a line repeating the one before it is dropped.
 *
 * @param {string | undefined} message
 * @param {number} limit
 * @returns {Excerpt}
 */
export function excerpt(message, limit = ERROR_LINES) {
  /** @type {string[]} */
  const lines = [];
  for (const raw of stripAnsi(message ?? "").split("\n")) {
    const line = raw.trimEnd();
    const previous = lines.at(-1);
    if (line.trim() === "" || line === previous || `Error: ${line}` === previous) continue;
    lines.push(line.length > LINE_CHARS ? `${line.slice(0, LINE_CHARS - 1)}…` : line);
  }
  return { lines: lines.slice(0, limit), cut: lines.length > limit };
}

/**
 * A `-g` pattern that matches the title and needs no quoting inside double quotes: every character a
 * regular expression or the shell would read becomes `.`, which matches it.
 *
 * @param {string} title
 */
export const grepPattern = (title) => title.replace(/[\\^$.*+?()[\]{}|"'`!]/g, ".");

/**
 * A `-g` pattern for a case under `describe` blocks. Playwright matches `-g` against the titles with
 * each describe's tags after its title, so the segments are joined with `.*`, never with ` › `.
 *
 * @param {string} title the describe path and the case title, joined with ` › `
 */
export const grepPathPattern = (title) => title.split(" › ").map(grepPattern).join(".*");

/**
 * The command that reruns one case. Every iteration of a loop shares one file and line, so the
 * pattern picks out the one that failed. A case that needs the stack reruns against a kept corpus,
 * with `--no-deps`. A project that needs no stack keeps its dependencies, which build and serve
 * what its cases use, such as the VS Code server of `vscode`.
 *
 * @param {string} target
 * @param {string} project
 * @param {string} location the spec's `file:line` under `specs/`
 * @param {string} title the describe path and the case title
 * @param {boolean} [stack] whether the project needs the stack
 */
export const rerunCommand = (target, project, location, title, stack = true) =>
  `npm run test:${target} -- --project=${project}${stack ? " --no-deps" : ""} specs/${location} -g "${grepPathPattern(title)}"`;

/** @param {string[]} names */
export const joined = (names) => (names.length <= 2 ? names.join(" and ") : `${names.slice(0, -1).join(", ")}, and ${names.at(-1)}`);

/** @param {string[]} names */
export const shortList = (names) => (names.length <= 2 ? joined(names) : `${names.slice(0, 2).join(", ")}, and ${names.length - 2} more`);

/**
 * @param {number} count
 * @param {string} noun
 */
export const plural = (count, noun) => `${count} ${count === 1 ? noun : `${noun}s`}`;

/** @param {number} ms */
export function duration(ms) {
  const minutes = Math.round(ms / 60_000);
  if (minutes < 1) return `${Math.round(ms / 1000)} s`;
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

/** @param {number} ms */
export function clock(ms) {
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** @param {string} text */
export const escapeHtml = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** @param {string} iso */
export function shortDate(iso) {
  const date = new Date(iso);
  return `${MONTHS[date.getUTCMonth()]} ${date.getUTCDate()}`;
}

/** @param {string} iso */
export function shortTime(iso) {
  const date = new Date(iso);
  return `${String(date.getUTCHours()).padStart(2, "0")}:${String(date.getUTCMinutes()).padStart(2, "0")}`;
}

/** @param {string} iso */
export function utcDate(iso) {
  return `${shortDate(iso)}, ${new Date(iso).getUTCFullYear()}, ${shortTime(iso)} UTC`;
}
