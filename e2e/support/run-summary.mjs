/**
 * Writes `test-results/summary.md` for the CI step summary, from the run model `run-model.mjs`
 * reads and the `coverage.json` that `npm run reconcile` writes. It also writes this run's
 * `outcomes.json` for the runs after it. A missing file leaves its sections out. `run-email.mjs`
 * and `run-page.mjs` render the same model.
 *
 *     npm run run-summary [-- <dir>]   # dir defaults to test-results/
 *
 * From the top: the verdict with the counts and the previous run, the links, the failures by how
 * long they have been failing, the pins to remove, the trend, and collapsed tables.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GROUPS, GROUP_TITLES, clock, eachTest, escapeHtml, publishedLabel, readIfPresent, shortDate, shortTime, slowestTests, stripAnsi } from "./run-report.mjs";
import { readHistory, writeOutcomes } from "./run-history.mjs";
import { byNovelty, groupsSentence, historyParts, runOf } from "./run-model.mjs";

export const SUMMARY_FILE = "summary.md";
export const COVERAGE_FILE = "coverage.json";
export const FAILED_TESTS_LIMIT = 50;
export const SLOWEST_LIMIT = 10;
/** GitHub refuses a step summary over 1 MiB, so the summary drops tables before it gets there. */
export const SUMMARY_BYTES = 900 * 1024;
/** The attachments `specs/env/process-envelope.spec.ts` leaves for the summary to read. */
export const PEAKS_ATTACHMENT = "resource-peaks";
export const METASPACE_ATTACHMENT = "metaspace";

/**
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {import("./run-history.mjs").Outcomes} Outcomes
 * @typedef {import("./run-model.mjs").Run} Run
 * @typedef {import("./run-model.mjs").FailedCase} FailedCase
 * @typedef {import("./run-model.mjs").LastRun} LastRun
 * @typedef {import("./run-report.mjs").PublishedImage} PublishedImage
 * @typedef {{ workers?: number, provision?: { durationMs?: number, published?: PublishedImage[] }, startedAt?: string, target?: string, commit?: { sha?: string, branch?: string }, absent?: { name: string, reason: string }[] }} Stack
 * @typedef {{
 *   target: string, elements: { covered: number, inScope: number, outOfScope: number, shared: number },
 *   operations: { total: number, reached: number, covered: number }, messages: { covered: number, handled: number, unhandled: number }, problems: number
 * }} Coverage
 * @typedef {{ role: string, memoryBytes: number, cpuPercent: number, samples: number, band: { memoryBytes: number, cpuPercent: number } }} PeakReading
 * @typedef {{ role: string, usedBytes: number, maxBytes: number, band: number }} MetaspaceReading
 * @typedef {{ report?: Report, lastRun?: LastRun, stack?: Stack, coverage?: Coverage, history?: Outcomes[], env?: Record<string, string | undefined> }} SummaryInput
 */

/** @param {string} text */
const cell = (text) => text.replace(/\|/g, "\\|");

/** @param {number} bytes */
const mib = (bytes) => `${Math.round(bytes / 1024 ** 2)} MiB`;

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

/** @param {{ text: string, href?: string }} link */
const mdLink = (link) => (link.href ? `[${link.text}](${link.href})` : link.text);

/**
 * The verdict, the counts of each group, and the run against the previous one, as one paragraph.
 *
 * @param {Run} run
 */
function verdictLine(run) {
  const parts = [`**${run.title}.** ${run.verdict}`];
  if (run.groups) parts.push(groupsSentence(run.groups));
  const previous = run.previous;
  if (previous) {
    const count = (/** @type {string} */ novelty) => run.failed.filter((each) => each.novelty === novelty).length;
    const intermittent = count("intermittent");
    const when = previous.startedAt ? ` of ${shortDate(previous.startedAt)}` : "";
    const fresh = count("new");
    let against =
      `Against the ${mdLink({ text: "previous run", href: previous.url })}${when}: ${fresh} new ${fresh === 1 ? "failure" : "failures"},` +
      `${intermittent > 0 ? ` ${intermittent} intermittent,` : ""} ${count("recurring")} still failing, ${run.fixed.length} fixed.`;
    if (previous.counts) {
      against += ` That run failed ${previous.counts.failed} product ${previous.counts.failed === 1 ? "case" : "cases"}${previous.durationMs === undefined ? "" : ` in ${minutes(previous.durationMs)}`}.`;
    }
    parts.push(against);
  }
  return parts.join(" ");
}

/**
 * @param {Run} run
 * @param {Stack | undefined} stack
 */
function timing(run, stack) {
  const parts = [];
  if (run.durationMs !== undefined) parts.push(`Took ${minutes(run.durationMs)}`);
  if (stack?.provision?.durationMs !== undefined) parts.push(`${minutes(stack.provision.durationMs)} of it provisioning the stack`);
  if (stack?.workers) parts.push(`on ${stack.workers} workers`);
  return parts.length > 0 ? `${parts.join(", ")}.` : undefined;
}

/**
 * The images the run took from GHCR rather than building them, which `E2E_BUILD` decides.
 *
 * @param {Stack | undefined} stack
 */
function publishedLine(stack) {
  const published = stack?.provision?.published ?? [];
  if (published.length === 0) return undefined;
  return `From GHCR: ${published.map((each) => `${each.name} \`${publishedLabel(each)}\``).join(", ")}.`;
}

/**
 * The run, the two HTML pages when the workflow passed their links, and the zip.
 *
 * @param {Run} run
 */
function linksSection(run) {
  const lines = [];
  if (run.runUrl) lines.push(`- ${mdLink({ text: "The run", href: run.runUrl })}`);
  if (run.runPageUrl) lines.push(`- ${mdLink({ text: "Run page", href: run.runPageUrl })}: the failures with their screenshots, the trend, and the projects, on one page`);
  if (run.reportHtmlUrl) lines.push(`- ${mdLink({ text: "HTML report", href: run.reportHtmlUrl })}: Playwright's report, without the traces and attachments`);
  if (run.reportZipUrl) {
    lines.push(
      `- ${mdLink({ text: "Report with traces", href: run.reportZipUrl })}: Playwright's report with its traces and attachments. ` +
        "Download it and run `npx playwright show-report e2e-playwright-report.zip`.",
    );
  }
  if (run.runUrl && run.artifactsUrl) {
    lines.push(`- ${mdLink({ text: "Run artifacts", href: run.artifactsUrl })}: the test results, the run's state files, and the UI server log`);
  }
  return lines.length > 0 ? lines.join("\n") : undefined;
}

/**
 * One failure as a collapsed block whose summary names the test, its project, and its first error
 * line. Without its body, the block is the summary line alone.
 *
 * @param {FailedCase} each
 * @param {boolean} body
 */
function failureBlock(each, body) {
  const first = each.error.lines[0] ?? "(no error message)";
  const parts = historyParts(each);
  const past = each.history;
  let since = "";
  if (past?.kind === "recurring") {
    const date = escapeHtml(past.since.startedAt ? shortDate(past.since.startedAt) : "an earlier run");
    since = ` (${past.runs + 1} runs, since ${past.since.url ? `<a href="${escapeHtml(past.since.url)}">${date}</a>` : date}${past.always ? " or earlier" : ""})`;
  }
  const summary = `<b>${escapeHtml(each.title)}</b> in <code>${escapeHtml(each.project)}</code>: ${escapeHtml(first)}${since}`;
  if (!body) return `- ${summary}`;
  const where = [`\`${each.location.text}\``, each.failedAt && `failed at \`${each.failedAt.text}\``].filter(Boolean).join(", ");
  const history = parts && each.novelty !== "recurring" ? ` ${parts.before}${mdLink(parts.link)}${parts.after}.` : "";
  const fence = (/** @type {string} */ lang, /** @type {string[]} */ lines) => `\`\`\`${lang}\n${lines.join("\n")}\n\`\`\``;
  const error = each.error.lines.length > 0 ? [...each.error.lines, ...(each.error.cut ? ["…"] : [])] : ["(no error message)"];
  return details(summary, `${fence("text", error)}\n\n${where}.${history}\n\n${fence("bash", [each.retry])}`);
}

/**
 * The failures under New, Still failing, Intermittent, or Failed, then the tests fixed since the
 * previous run.
 *
 * @param {Run} run
 * @param {boolean} bodies
 */
function failuresSection(run, bodies) {
  const sections = [];
  if (run.failed.length > 0) {
    let shown = 0;
    const groups = [];
    for (const group of byNovelty(run.failed)) {
      const cases = group.cases.slice(0, Math.max(0, FAILED_TESTS_LIMIT - shown));
      shown += cases.length;
      if (cases.length > 0) groups.push(`#### ${group.title} (${group.cases.length})\n\n${cases.map((each) => failureBlock(each, bodies)).join("\n\n")}`);
    }
    const more = run.failed.length - shown;
    const where = run.runPageUrl ? "The run page" : "The HTML report in the `e2e-playwright-report` artifact";
    sections.push(`### Failures\n\n${groups.join("\n\n")}${more > 0 ? `\n\n${more} more failed. ${where} lists every failure.` : ""}`);
  }
  if (run.fixed.length > 0) {
    const lines = run.fixed.map((each) => `- ${cell(each.title)} in \`${each.project}\`, \`${each.file}\``);
    sections.push(`#### Fixed since the last run (${run.fixed.length})\n\n${lines.join("\n")}`);
  }
  return sections.length > 0 ? sections.join("\n\n") : undefined;
}

/** @param {Run} run */
function pinsSection(run) {
  if (run.pins.length === 0) return undefined;
  const rows = run.pins.map((each) => [cell(each.project), `\`${each.location.text}\``, cell(each.title), cell(each.defect ?? "")]);
  return (
    "### Pins to remove\n\n" +
    "Each `test.fail()` case below passed, so the defect it pins no longer reproduces. " +
    "Remove the annotation and close the defect's issue.\n\n" +
    table(["Project", "Location", "Test", "Defect"], rows)
  );
}

/**
 * The failed product cases and the duration of each run in the trend, as two charts over the same
 * runs, since the two have different units.
 *
 * @param {Run} run
 */
function trendSection(run) {
  const points = run.points;
  if (points.length < 2) return undefined;
  const labels = `[${points.map((each) => `"${shortDate(each.startedAt)} ${shortTime(each.startedAt)}"`).join(", ")}]`;
  const failed = points.map((each) => each.failed);
  const chart = (/** @type {string[]} */ lines) => `\`\`\`mermaid\nxychart-beta\n${lines.map((line) => `  ${line}`).join("\n")}\n\`\`\``;
  const parts = [
    `### The last ${points.length} runs`,
    "The oldest run is on the left and this run on the right; the times are UTC.",
    chart(['title "Failed product cases"', `x-axis ${labels}`, `y-axis "Cases" 0 --> ${Math.max(1, ...failed)}`, `bar [${failed.join(", ")}]`]),
  ];
  if (points.every((each) => each.durationMs !== undefined)) {
    const durations = points.map((each) => (/** @type {number} */ (each.durationMs) / 60_000).toFixed(1));
    parts.push(chart(['title "Duration in minutes"', `x-axis ${labels}`, 'y-axis "Minutes"', `line [${durations.join(", ")}]`]));
  }
  return parts.join("\n\n");
}

/** @param {Coverage} coverage */
function coverageSection(coverage) {
  const { elements, operations, messages } = coverage;
  const verdict =
    coverage.problems === 0
      ? "Reconciliation passed."
      : `Reconciliation found ${coverage.problems} ${coverage.problems === 1 ? "problem" : "problems"}; the reconcile step lists them.`;
  return details(
    "Coverage",
    `Elements: ${elements.covered} of ${elements.inScope} registry rows in scope covered on ${coverage.target}` +
      ` (${elements.outOfScope} out of scope, ${elements.shared} shared). ` +
      `API operations: ${operations.covered} of ${operations.total} covered, ${operations.reached} reached. ` +
      `VS Code messages: ${messages.covered} of ${messages.handled} handled message types covered, ${messages.unhandled} unhandled. ${verdict}`,
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

/**
 * One table per project group, each project with its tests, when it started, and how long it took.
 *
 * @param {Run} run
 * @param {Report} report
 */
function projectsSection(run, report) {
  const start = report.stats.startTime ? Date.parse(report.stats.startTime) : undefined;
  const timed = run.projects.filter((each) => each.first !== undefined && each.last !== undefined);
  if (timed.length === 0) return undefined;
  const tables = GROUPS.flatMap((group) => {
    const rows = timed
      .filter((each) => each.group === group)
      .sort((a, b) => /** @type {number} */ (a.first) - /** @type {number} */ (b.first))
      .map((each) => {
        const [first, last] = [/** @type {number} */ (each.first), /** @type {number} */ (each.last)];
        return [`\`${each.name}\``, String(each.tests), start === undefined ? "" : `+${clock(first - start)}`, clock(last - first)];
      });
    return rows.length > 0 ? [`#### ${GROUP_TITLES[group]}\n\n${table(["Project", "Tests", "Started", "Took"], rows)}`] : [];
  });
  return details("Time by project", tables.join("\n\n"));
}

/** @param {Report} report */
function slowestSection(report) {
  const slowest = slowestTests(report, SLOWEST_LIMIT).map((each) => [clock(each.ms), `\`${each.project}\``, `\`${each.location}\``, cell(each.title)]);
  return slowest.length > 0 ? details(`Slowest ${slowest.length} tests`, table(["Took", "Project", "Location", "Test"], slowest)) : undefined;
}

/**
 * The summary as Markdown. Over `limit` bytes it drops the slowest tests, then the project tables,
 * then the bodies of the failures.
 *
 * @param {SummaryInput} input
 * @param {number} [limit]
 * @returns {string}
 */
export function summaryMarkdown({ report, lastRun, stack, coverage, history = [], env = {} }, limit = SUMMARY_BYTES) {
  const run = runOf({ report, lastRun, stack, coverage, history, env });
  /** @param {{ slowest: boolean, projects: boolean, bodies: boolean }} keep */
  const render = (keep) => {
    /** @type {(string | undefined)[]} */
    const parts = [];
    if (report || lastRun) parts.push(verdictLine(run));
    if (report) parts.push(timing(run, stack));
    parts.push(publishedLine(stack));
    parts.push(linksSection(run));
    // Playwright colors the messages with ANSI escapes.
    if (report && report.errors.length > 0) {
      const messages = report.errors.map((each) => stripAnsi(each.message ?? "")).join("\n\n");
      parts.push(`### Errors outside any test\n\n\`\`\`text\n${messages}\n\`\`\``);
    }
    parts.push(failuresSection(run, keep.bodies), pinsSection(run), trendSection(run));
    if (coverage) parts.push(coverageSection(coverage));
    if (report) {
      if (keep.projects) parts.push(projectsSection(run, report));
      if (keep.slowest) parts.push(slowestSection(report));
      parts.push(resourcesSection(report));
    }
    if (stack?.absent?.length) parts.push(details("Left out on this target", stack.absent.map((each) => `- \`${each.name}\`: ${each.reason}`).join("\n")));
    return parts.filter(Boolean).join("\n\n") + "\n";
  };
  const steps = [
    { slowest: true, projects: true, bodies: true },
    { slowest: false, projects: true, bodies: true },
    { slowest: false, projects: false, bodies: true },
    { slowest: false, projects: false, bodies: false },
  ];
  let markdown = "";
  for (const keep of steps) {
    markdown = render(keep);
    if (Buffer.byteLength(markdown) <= limit) break;
  }
  return markdown;
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
  if (report) writeOutcomes(dir, report, env, lastRun, coverage);
  fs.writeFileSync(path.join(dir, SUMMARY_FILE), summaryMarkdown({ report, lastRun, stack, coverage, history, env }));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  writeRunSummary(dir, process.env);
}
