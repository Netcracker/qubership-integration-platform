/**
 * Writes `test-results/summary.md` for the CI step summary, from the run's `report.json`,
 * `.last-run.json`, and `stack.json`, the `coverage.json` that `npm run reconcile` writes, and the
 * earlier runs under `E2E_HISTORY_DIR`. It also writes this run's `outcomes.json` for the runs after
 * it. A missing file leaves its sections out. `run-email.mjs` writes the failure email from the
 * same files.
 *
 *     npm run run-summary [-- <dir>]   # dir defaults to test-results/
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { eachTest, knownDefectIn, readIfPresent, shortDate, stripAnsi } from "./run-report.mjs";
import { fixedSince, historyOf, readHistory, testKey, writeOutcomes } from "./run-history.mjs";

export const SUMMARY_FILE = "summary.md";
export const COVERAGE_FILE = "coverage.json";
export const FAILED_TESTS_LIMIT = 50;
export const SLOWEST_LIMIT = 10;
/** The attachments `specs/env/process-envelope.spec.ts` leaves for the summary to read. */
export const PEAKS_ATTACHMENT = "resource-peaks";
export const METASPACE_ATTACHMENT = "metaspace";

/**
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {import("./run-report.mjs").ReportError} ReportError
 * @typedef {import("./run-history.mjs").Outcomes} Outcomes
 * @typedef {import("./run-history.mjs").History} History
 * @typedef {{ status: string }} LastRun
 * @typedef {{ workers?: number, provision?: { durationMs?: number }, absent?: { name: string, reason: string }[] }} Stack
 * @typedef {{ target: string, elements: { covered: number, total: number }, operations: { total: number, reached: number, covered: number }, problems: number }} Coverage
 * @typedef {{ role: string, memoryBytes: number, cpuPercent: number, samples: number, band: { memoryBytes: number, cpuPercent: number } }} PeakReading
 * @typedef {{ role: string, usedBytes: number, maxBytes: number, band: number }} MetaspaceReading
 * @typedef {{ project: string, location: string, title: string, error: string, history?: History }} FailedTest
 */

/** @param {string} text */
const cell = (text) => text.replace(/\|/g, "\\|");

/** @param {number} bytes */
const mib = (bytes) => `${Math.round(bytes / 1024 ** 2)} MiB`;

/** @param {number} ms */
function clock(ms) {
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** @param {number} ms */
const minutes = (ms) => `${(ms / 60_000).toFixed(1)} min`;

/**
 * @param {string} title
 * @param {string} body
 */
const details = (title, body) => `<details><summary>${title}</summary>\n\n${body}\n\n</details>`;

/**
 * @param {string[]} head
 * @param {string[][]} rows
 */
const table = (head, rows) =>
  [`| ${head.join(" | ")} |`, `| ${head.map(() => "---").join(" | ")} |`, ...rows.map((row) => `| ${row.join(" | ")} |`)].join("\n");

/**
 * @param {ReportError | undefined} error
 * @returns {string}
 */
function firstLine(error) {
  return stripAnsi(error?.message ?? "").split("\n").find((line) => line.trim() !== "")?.trim() ?? "";
}

/**
 * @param {Report} report
 * @param {Outcomes[]} [history] earlier runs, the latest first
 * @returns {FailedTest[]}
 */
export function failedTests(report, history = []) {
  /** @type {FailedTest[]} */
  const failed = [];
  eachTest(report, (spec, test, title) => {
    if (test.status !== "unexpected") return;
    failed.push({
      project: test.projectName,
      location: `${spec.file}:${spec.line}`,
      title,
      error: firstLine(test.results.at(-1)?.errors?.[0]),
      history: historyOf(testKey(test.projectName, spec.file, title), history),
    });
  });
  return failed;
}

/**
 * How long a failed test has been failing, as a table cell.
 *
 * @param {History | undefined} history
 */
function failingCell(history) {
  if (!history) return "";
  if (history.kind === "new") return "new";
  const since = history.since.startedAt ? shortDate(history.since.startedAt) : "";
  const link = history.since.url && since ? `[${since}](${history.since.url})` : since;
  return `${history.runs + 1} runs, since ${link}${history.always ? " or earlier" : ""}`;
}

/**
 * @param {FailedTest[]} failed
 * @param {Report} report
 * @param {Outcomes[]} history
 */
function againstPrevious(failed, report, history) {
  const previous = history[0];
  if (!previous) return undefined;
  const fresh = failed.filter((each) => each.history?.kind === "new").length;
  const still = failed.filter((each) => each.history?.kind === "recurring").length;
  const fixed = fixedSince(report, previous).length;
  const when = previous.startedAt ? ` of ${shortDate(previous.startedAt)}` : "";
  const run = previous.url ? `[previous run](${previous.url})${when}` : `previous run${when}`;
  return `Against the ${run}: ${fresh} new ${fresh === 1 ? "failure" : "failures"}, ${still} still failing, ${fixed} fixed.`;
}

/**
 * @param {Report} report
 * @param {Stack | undefined} stack
 */
function timing(report, stack) {
  const parts = [];
  if (report.stats.duration !== undefined) parts.push(`Took ${minutes(report.stats.duration)}`);
  if (stack?.provision?.durationMs !== undefined) parts.push(`${minutes(stack.provision.durationMs)} of it provisioning the stack`);
  if (stack?.workers) parts.push(`on ${stack.workers} workers`);
  return parts.length > 0 ? `${parts.join(", ")}.` : undefined;
}

/** @param {Report} report */
function countsTable(report) {
  const isKnown = knownDefectIn(report);
  let known = 0;
  eachTest(report, (_spec, test) => {
    if (isKnown(test)) known++;
  });
  const { expected, unexpected, flaky, skipped } = report.stats;
  return table(["Passed", "Failed", "Flaky", "Skipped", "Known defects"], [[expected - known, unexpected, flaky, skipped, known].map(String)]);
}

/** @param {Coverage} coverage */
function coverageSection(coverage) {
  const { elements, operations } = coverage;
  const verdict =
    coverage.problems === 0
      ? "Reconciliation passed."
      : `Reconciliation found ${coverage.problems} ${coverage.problems === 1 ? "problem" : "problems"}; the reconcile step lists them.`;
  return (
    `### Coverage\n\n` +
    `Elements: ${elements.covered} of ${elements.total} registry rows covered on ${coverage.target}. ` +
    `API operations: ${operations.covered} of ${operations.total} covered, ${operations.reached} reached. ${verdict}`
  );
}

/**
 * The first body of an attachment by that name, anywhere in the report.
 *
 * @param {Report} report
 * @param {string} name
 * @returns {any}
 */
function attachment(report, name) {
  /** @type {any} */
  let found;
  eachTest(report, (_spec, test) => {
    for (const result of test.results) {
      const each = result.attachments?.find((one) => one.name === name && one.body);
      if (found === undefined && each?.body) found = JSON.parse(Buffer.from(each.body, "base64").toString("utf8"));
    }
  });
  return found;
}

/** @param {Report} report */
function resourcesSection(report) {
  /** @type {PeakReading[] | undefined} */
  const peaks = attachment(report, PEAKS_ATTACHMENT);
  /** @type {MetaspaceReading[] | undefined} */
  const metaspace = attachment(report, METASPACE_ATTACHMENT);
  if (!peaks && !metaspace) return undefined;
  const roles = [...new Set([...(peaks ?? []).map((each) => each.role), ...(metaspace ?? []).map((each) => each.role)])];
  const rows = roles.map((role) => {
    const peak = peaks?.find((each) => each.role === role);
    const meta = metaspace?.find((each) => each.role === role);
    return [
      role,
      peak ? `${mib(peak.memoryBytes)} of ${mib(peak.band.memoryBytes)}` : "",
      peak ? `${Math.round(peak.cpuPercent)}% of ${peak.band.cpuPercent}%` : "",
      meta ? `${mib(meta.usedBytes)} of ${mib(meta.maxBytes)}, ${Math.round((meta.usedBytes / meta.maxBytes) * 100)}% of ${Math.round(meta.band * 100)}%` : "",
    ];
  });
  return details(
    "Resources",
    "Peaks sampled from the start of the run to the `env` project, against the band each service may reach, " +
      "and Metaspace when `env` started, against its ceiling and the share it may commit. CPU is a percent of one core.\n\n" +
      table(["Service", "Peak memory", "Peak CPU", "Metaspace"], rows),
  );
}

/** @param {Report} report */
function timeSections(report) {
  const start = report.stats.startTime ? Date.parse(report.stats.startTime) : undefined;
  /** @type {Map<string, { tests: number, first: number, last: number }>} */
  const projects = new Map();
  /** @type {{ ms: number, project: string, location: string, title: string }[]} */
  const tests = [];
  eachTest(report, (spec, test, title) => {
    for (const result of test.results) {
      if (!result.startTime || result.duration === undefined) continue;
      const from = Date.parse(result.startTime);
      const to = from + result.duration;
      const project = projects.get(test.projectName) ?? { tests: 0, first: from, last: to };
      project.tests++;
      project.first = Math.min(project.first, from);
      project.last = Math.max(project.last, to);
      projects.set(test.projectName, project);
      tests.push({ ms: result.duration, project: test.projectName, location: `${spec.file}:${spec.line}`, title });
    }
  });
  if (projects.size === 0) return [];
  const byStart = [...projects].sort(([, a], [, b]) => a.first - b.first);
  const projectRows = byStart.map(([name, each]) => [
    `\`${name}\``,
    String(each.tests),
    start === undefined ? "" : `+${clock(each.first - start)}`,
    clock(each.last - each.first),
  ]);
  const slowest = tests
    .sort((a, b) => b.ms - a.ms)
    .slice(0, SLOWEST_LIMIT)
    .map((each) => [clock(each.ms), `\`${each.project}\``, `\`${each.location}\``, cell(each.title)]);
  return [
    details("Time by project", table(["Project", "Tests", "Started", "Took"], projectRows)),
    details(`Slowest ${slowest.length} tests`, table(["Took", "Project", "Location", "Test"], slowest)),
  ];
}

/**
 * The cases a `test.fail()` pins, grouped by the defect a conditional pin names. A pin that names
 * none gets a row of its own.
 *
 * @param {Report} report
 */
function knownDefectsSection(report) {
  const isKnown = knownDefectIn(report);
  /** @type {Map<string, { projects: Set<string>, cases: number }>} */
  const defects = new Map();
  eachTest(report, (spec, test, title) => {
    if (!isKnown(test)) return;
    const named = test.annotations?.find((each) => each.type === "fail" && each.description)?.description;
    const key = named ?? `\`${spec.file}:${spec.line}\` ${title}`;
    const defect = defects.get(key) ?? { projects: new Set(), cases: 0 };
    defect.projects.add(test.projectName);
    defect.cases++;
    defects.set(key, defect);
  });
  if (defects.size === 0) return undefined;
  const total = [...defects.values()].reduce((sum, each) => sum + each.cases, 0);
  const rows = [...defects]
    .sort(([, a], [, b]) => b.cases - a.cases)
    .map(([defect, each]) => [cell(defect), [...each.projects].map((name) => `\`${name}\``).join(", "), String(each.cases)]);
  return details(`Known defects: ${total} cases`, table(["Defect", "Projects", "Cases"], rows));
}

/**
 * @param {{ report?: Report, lastRun?: LastRun, stack?: Stack, coverage?: Coverage, history?: Outcomes[] }} run
 * @returns {string}
 */
export function summaryMarkdown({ report, lastRun, stack, coverage, history = [] }) {
  /** @type {(string | undefined)[]} */
  const parts = [];
  if (lastRun) parts.push(`Status: **${lastRun.status}**`);
  if (report) {
    parts.push(timing(report, stack), countsTable(report));
    const failed = failedTests(report, history);
    parts.push(againstPrevious(failed, report, history));
    if (failed.length > 0) {
      const rows = failed
        .slice(0, FAILED_TESTS_LIMIT)
        .map((each) => [cell(each.project), `\`${each.location}\``, cell(each.title), failingCell(each.history), cell(each.error)]);
      let section = `### Failed tests\n\n${table(["Project", "Location", "Test", "Failing", "First error line"], rows)}`;
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
  if (coverage) parts.push(coverageSection(coverage));
  if (report) parts.push(resourcesSection(report), ...timeSections(report), knownDefectsSection(report));
  if (stack?.absent?.length) {
    parts.push(`### Absent\n\n${stack.absent.map((each) => `- \`${each.name}\`: ${each.reason}`).join("\n")}`);
  }
  return parts.filter(Boolean).join("\n\n") + "\n";
}

/**
 * Writes the summary and, when the run left a report, its outcomes into `dir`.
 *
 * @param {string} dir
 * @param {Record<string, string | undefined>} env
 */
export function writeRunSummary(dir, env = {}) {
  /** @type {Report | undefined} */
  const report = readIfPresent(path.join(dir, "report.json"));
  const lastRun = readIfPresent(path.join(dir, ".last-run.json"));
  const stack = readIfPresent(path.join(dir, "stack.json"));
  const coverage = readIfPresent(path.join(dir, COVERAGE_FILE));
  const history = readHistory(env.E2E_HISTORY_DIR);
  if (report) writeOutcomes(dir, report, env);
  fs.writeFileSync(path.join(dir, SUMMARY_FILE), summaryMarkdown({ report, lastRun, stack, coverage, history }));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  writeRunSummary(dir, process.env);
}
