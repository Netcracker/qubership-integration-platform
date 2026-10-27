/**
 * What a run found, read once from its `report.json`, `.last-run.json`, `stack.json`, and the
 * `coverage.json` that `npm run reconcile` writes, the earlier runs under `E2E_HISTORY_DIR`, and
 * the variables GitHub Actions sets. The step summary (`run-summary.mjs`), the email
 * (`run-email.mjs`), and the run page (`run-page.mjs`) render this one model, so the three say the
 * same thing.
 *
 * The links come from `GITHUB_SERVER_URL`, `GITHUB_REPOSITORY`, `GITHUB_SHA`, `GITHUB_REF_NAME`,
 * `GITHUB_RUN_ID`, and `GITHUB_EVENT_NAME`, and from the artifact links the workflow passes:
 * `REPORT_URL` (the zipped `e2e-report`), `PLAYWRIGHT_REPORT_URL` (Playwright's report with its traces,
 * zipped), `REPORT_HTML_URL` (Playwright's HTML report as one file),
 * `RUN_PAGE_URL` (the run page), and `CLUSTER_URL`. A link whose variable is unset is left out.
 */
import path from "node:path";
import {
  duration,
  eachTest,
  excerpt,
  fixedPinIn,
  groupCounts,
  groupOf,
  joined,
  notRunOf,
  plural,
  projectRows,
  rerunCommand,
  shortDate,
  totalOf,
  utcDate,
} from "./run-report.mjs";
import { fixedSince, historyOf, outcomesOf, testKey } from "./run-history.mjs";

/** How many runs the trend shows, this one included. */
export const TREND_RUNS = 10;

/**
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {import("./run-report.mjs").Counts} Counts
 * @typedef {import("./run-report.mjs").Excerpt} Excerpt
 * @typedef {import("./run-report.mjs").Group} Group
 * @typedef {import("./run-report.mjs").NotRun} NotRun
 * @typedef {import("./run-report.mjs").ProjectRow} ProjectRow
 * @typedef {import("./run-history.mjs").Outcomes} Outcomes
 * @typedef {import("./run-history.mjs").History} History
 * @typedef {{ status: string }} LastRun
 * @typedef {import("./run-summary.mjs").Coverage} Coverage
 * @typedef {{ startedAt?: string, target?: string, commit?: { sha?: string, branch?: string }, absent?: { name: string, reason: string }[] }} Stack
 * @typedef {Record<string, string | undefined>} Env
 *
 * @typedef {{ text: string, href?: string }} Link
 * @typedef {"new" | "recurring" | "intermittent" | "unknown"} Novelty
 * @typedef {{ name: string, contentType: string, path?: string, body?: string }} Image
 * @typedef {{
 *   project: string, group: Group, title: string, location: Link, failedAt?: Link, error: Excerpt, retry: string,
 *   history?: History, novelty: Novelty, sameCommit?: boolean, images: Image[]
 * }} FailedCase
 * @typedef {{ project: string, title: string, location: Link, defect?: string }} FixedPin
 * @typedef {{ project: string, file: string, title: string }} FixedTest
 * @typedef {{ projects: string[], didNotRun: number }} Infra
 * @typedef {{ runId?: string, url?: string, startedAt: string, status: string, failed: number, durationMs?: number, current: boolean }} TrendPoint
 * @typedef {"failed" | "warning" | "passed"} Tone
 * @typedef {{
 *   tone: Tone, title: string, verdict: string, trend?: string, repo?: string, branch?: string, commit?: Link, commitSubject?: string,
 *   trigger: string, started?: string, duration?: string, durationMs?: number, target: string,
 *   counts?: Counts, groups?: Record<Group, Counts>, infra?: Infra,
 *   runUrl?: string, artifactsUrl?: string, reportZipUrl?: string, reportHtmlUrl?: string, runPageUrl?: string, clusterUrl?: string, readmeUrl?: string,
 *   failed: FailedCase[], pins: FixedPin[], fixed: FixedTest[], failedProjects: string[], errors: Excerpt[], failedStep?: string, coverageProblems: number,
 *   notRun: NotRun[], dependenciesKnown: boolean, absent: { name: string, reason: string }[], projects: ProjectRow[],
 *   previous?: Outcomes, points: TrendPoint[]
 * }} Run
 */

/**
 * @param {{ report?: Report, lastRun?: LastRun, stack?: Stack, coverage?: Coverage, history?: Outcomes[], env?: Env }} run
 * @returns {Run}
 */
export function runOf({ report, lastRun, stack, coverage, history = [], env = {} }) {
  const server = env.GITHUB_SERVER_URL ?? "https://github.com";
  const repo = env.GITHUB_REPOSITORY;
  const sha = env.GITHUB_SHA ?? stack?.commit?.sha;
  const repoUrl = repo && `${server}/${repo}`;
  const runUrl = repoUrl && env.GITHUB_RUN_ID ? `${repoUrl}/actions/runs/${env.GITHUB_RUN_ID}` : undefined;
  /**
   * @param {string} file relative to `e2e/`
   * @param {number} [line]
   */
  const blob = (file, line) => (repoUrl && sha ? `${repoUrl}/blob/${sha}/e2e/${file}${line ? `#L${line}` : ""}` : undefined);
  const target = report?.config.metadata?.target ?? stack?.target ?? "k8s";
  const projects = report ? projectRows(report) : [];

  /** @type {FailedCase[]} */
  const failed = [];
  /** @type {FixedPin[]} */
  const pins = [];
  if (report) {
    const e2eDir = path.dirname(report.config.rootDir);
    const isFixedPin = fixedPinIn(report);
    const group = groupOf(report);
    eachTest(report, (spec, test, title) => {
      if (test.status !== "unexpected") return;
      const location = { text: `${spec.file}:${spec.line}`, href: blob(`specs/${spec.file}`, spec.line) };
      if (isFixedPin(test)) {
        const defect = test.annotations?.find((each) => each.type === "fail" && each.description)?.description;
        pins.push({ project: test.projectName, title, location, defect });
        return;
      }
      const result = test.results.at(-1);
      const error = result?.errors?.[0];
      const thrown = error?.location && path.relative(e2eDir, error.location.file);
      const past = historyOf(testKey(test.projectName, spec.file, title), history);
      failed.push({
        project: test.projectName,
        group: group(test.projectName),
        title,
        location,
        failedAt: thrown && error?.location ? { text: `${thrown}:${error.location.line}`, href: blob(thrown, error.location.line) } : undefined,
        error: excerpt(error?.message),
        retry: rerunCommand(target, test.projectName, location.text, title),
        history: past,
        novelty: past?.kind ?? (history.length > 0 ? "new" : "unknown"),
        sameCommit: past && past.kind !== "recurring" ? Boolean(sha) && past.previous.sha === sha : undefined,
        images: (result?.attachments ?? [])
          .filter((each) => each.contentType?.startsWith("image/") && (each.path || each.body))
          .map((each) => ({ name: each.name, contentType: /** @type {string} */ (each.contentType), path: each.path, body: each.body })),
      });
    });
  }
  // Grouped by project, in the order the config declares the projects.
  const failedProjects = projects.filter((each) => each.failed > 0).map((each) => each.name);
  failed.sort((a, b) => failedProjects.indexOf(a.project) - failedProjects.indexOf(b.project));

  const errors = (report?.errors ?? []).map((each) => excerpt(each.message, 12));
  const stats = report?.stats;
  const groups = report && groupCounts(report);
  const counts = groups?.product;
  // `npm run reconcile` writes the count before it fails the step, so a run whose tests passed still fails on it.
  const coverageProblems = coverage?.problems ?? 0;
  // On a canceled job the workflow passes its fallback step name; the interruption is the cause.
  const failedStep =
    failed.length === 0 && pins.length === 0 && errors.length === 0 && lastRun?.status !== "interrupted" ? env.FAILED_STEP || undefined : undefined;
  const notRun = report ? notRunOf(report, projects) : [];
  const setupFailed = projects.filter((each) => each.group === "setup" && each.failed > 0).map((each) => each.name);
  /** @type {Infra | undefined} */
  const infra =
    setupFailed.length > 0
      ? {
          projects: setupFailed,
          didNotRun: notRun.filter((each) => each.cause.some((name) => setupFailed.includes(name))).flatMap((each) => each.projects).reduce((sum, each) => sum + each.skipped, 0),
        }
      : undefined;

  /** @type {[Tone, string]} */
  const [tone, verdict] =
    failed.length > 0
      ? ["failed", "failed"]
      : lastRun?.status === "timedout"
        ? ["warning", "timed out"]
        : lastRun?.status === "interrupted"
          ? ["warning", "interrupted"]
          : errors.length > 0 || coverageProblems > 0 || failedStep || !report
            ? ["failed", "failed"]
            : pins.length > 0
              ? ["warning", "found fixed defects"]
              : ["passed", "passed"];
  const event = env.GITHUB_EVENT_NAME;
  const started = stats?.startTime ?? stack?.startedAt;
  // A run whose every failed test also failed the run before it says so in its title.
  const still = failed.length > 0 && errors.length === 0 && failed.every((each) => each.novelty === "recurring");
  const previous = history[0];
  const fixedKeys = report ? fixedSince(report, previous) : [];
  /** @type {Run} */
  const run = {
    tone,
    title: `${event === "schedule" ? "E2E nightly" : "E2E run"} ${still ? "still failing" : verdict}`,
    verdict: "",
    repo,
    branch: env.GITHUB_REF_NAME ?? stack?.commit?.branch,
    commit: sha ? { text: sha.slice(0, 7), href: repoUrl && `${repoUrl}/commit/${sha}` } : undefined,
    commitSubject: report?.config.metadata?.gitCommit?.subject,
    trigger: event === "schedule" ? "Scheduled run" : event === "workflow_dispatch" ? "Manual run" : event ? `${event} run` : "Local run",
    started: started && utcDate(started),
    duration: stats?.duration === undefined ? undefined : duration(stats.duration),
    durationMs: stats?.duration,
    target,
    counts,
    groups,
    infra,
    runUrl,
    artifactsUrl: env.REPORT_URL || (runUrl && `${runUrl}#artifacts`),
    reportZipUrl: env.PLAYWRIGHT_REPORT_URL || undefined,
    reportHtmlUrl: env.REPORT_HTML_URL || undefined,
    runPageUrl: env.RUN_PAGE_URL || undefined,
    clusterUrl: env.CLUSTER_URL || undefined,
    readmeUrl: blob("README.md") && `${blob("README.md")}#reproducing-one-case`,
    failed,
    pins,
    fixed: fixedKeys.map((key) => {
      const [project, file, ...title] = key.split(" › ");
      return { project, file, title: title.join(" › ") };
    }),
    failedProjects,
    errors,
    failedStep,
    coverageProblems,
    notRun,
    dependenciesKnown: (report?.config.projects ?? []).some((each) => each.metadata?.dependencies),
    absent: stack?.absent ?? [],
    projects,
    previous,
    points: report ? trendPoints(outcomesOf(report, env, lastRun, coverage), history) : [],
  };
  run.verdict = verdictOf(run, lastRun?.status, Boolean(report));
  // After the trend, whose "It" names the failed test.
  const trend = [report && trendOf(run, fixedKeys.length, previous), failed.length > 0 && pins.length > 0 && noLongerReproduce(pins.length)];
  run.trend = trend.filter(Boolean).join(" ") || undefined;
  return run;
}

/**
 * The runs the trend shows, the oldest first and this one last. A run whose outcomes predate the
 * status and the counts is left out, since nothing says how it ended.
 *
 * @param {Outcomes} current
 * @param {Outcomes[]} history the earlier runs, the latest first
 * @returns {TrendPoint[]}
 */
export function trendPoints(current, history) {
  /** @param {Outcomes} each @param {boolean} isCurrent @returns {TrendPoint[]} */
  const point = (each, isCurrent) =>
    each.status && each.counts && each.startedAt
      ? [{ runId: each.runId, url: each.url, startedAt: each.startedAt, status: each.status, failed: each.counts.failed, durationMs: each.durationMs, current: isCurrent }]
      : [];
  const earlier = history.flatMap((each) => point(each, false)).slice(0, TREND_RUNS - 1);
  return [...earlier.reverse(), ...point(current, true)];
}

/**
 * A trend point as a few words: the failed count, or how the run ended when no case failed.
 *
 * @param {TrendPoint} point
 */
export function pointLabel(point) {
  if (point.failed > 0) return `${point.failed} failed`;
  if (point.status === "timedout") return "timed out";
  return point.status === "passed" ? "passed" : point.status;
}

/** @param {number} pins */
const noLongerReproduce = (pins) => `${plural(pins, "pinned defect")} no longer ${pins === 1 ? "reproduces" : "reproduce"}.`;

/**
 * The run's outcome as one sentence or two, the way the tone was chosen: a failed setup project
 * first, then failed tests, a timeout or an interruption, an error outside any test, failed
 * coverage reconciliation, a failed step, pins to remove, and a pass.
 *
 * @param {Run} run
 * @param {string | undefined} status the one `.last-run.json` records
 * @param {boolean} reported
 */
function verdictOf(run, status, reported) {
  const counts = run.counts ?? { passed: 0, failed: 0, pins: 0, flaky: 0, skipped: 0 };
  const ran = counts.passed + counts.failed + counts.pins + counts.flaky;
  const didNotRun = counts.skipped > 0 ? `, and ${plural(counts.skipped, "test")} did not run` : "";
  if (run.infra) return infraVerdict(run, run.infra);
  if (run.failed.length > 0) return failedVerdict(run, counts);
  if (status === "timedout") return `The run timed out${run.duration ? ` after ${run.duration}` : ""}${didNotRun}.`;
  // A canceled job interrupts the suite, which may have passed every test it ran.
  if (status === "interrupted") return `The run was interrupted${run.duration ? ` after ${run.duration}` : ""}${didNotRun}.`;
  const error = run.errors[0]?.lines[0];
  if (error !== undefined) {
    const line = error.replace(/^Error: /, "");
    return ran === 0 ? `The run stopped before its tests finished: ${line}` : `An error outside any test failed the run: ${line}`;
  }
  if (run.coverageProblems > 0) return `Coverage reconciliation found ${plural(run.coverageProblems, "problem")}; the reconcile step lists them.`;
  if (run.failedStep) {
    // The workflow's fallback, "a step after the suite", reads as prose; a step's own name is quoted.
    return /^[a-z]/.test(run.failedStep) ? `The run failed at ${run.failedStep}.` : `The run failed at the step "${run.failedStep}".`;
  }
  if (!reported) return "The run failed before it wrote a report.";
  if (counts.pins > 0) return `${noLongerReproduce(counts.pins)} Every other test ${counts.skipped > 0 ? "that ran " : ""}passed${didNotRun}.`;
  const passed = counts.skipped > 0 ? `All ${plural(ran, "test")} that ran passed` : `All ${plural(ran, "test")} passed`;
  return `${passed}${counts.skipped > 0 ? `, and ${counts.skipped} did not run` : ""}.`;
}

/**
 * A failed setup project as an infrastructure failure, with the cases it kept from running, and
 * then the failures of the other projects.
 *
 * @param {Run} run
 * @param {Infra} infra
 */
function infraVerdict(run, infra) {
  const kept = infra.didNotRun > 0 ? `, so ${plural(infra.didNotRun, "case")} did not run` : "";
  const head = `Infrastructure failure: setup ${infra.projects.length === 1 ? "project" : "projects"} ${joined(infra.projects)} failed${kept}.`;
  const others = run.failed.filter((each) => each.group !== "setup");
  if (others.length === 0) return head;
  const where = [...new Set(others.map((each) => each.project))];
  return `${head} ${plural(others.length, "other test")} failed in ${where.length <= 3 ? joined(where) : `${where.slice(0, 2).join(", ")}, and ${where.length - 2} other projects`}.`;
}

/**
 * The failed tests, the projects they failed in, and the tests those projects kept from running.
 *
 * @param {Run} run
 * @param {Counts} counts
 */
function failedVerdict(run, counts) {
  const projects = run.failedProjects;
  const where = projects.length <= 3 ? joined(projects) : `${projects.slice(0, 2).join(", ")}, and ${projects.length - 2} other projects`;
  const head = `${plural(run.failed.length, "test")} failed in ${where}`;
  if (counts.skipped === 0) return `${head}.`;
  // Only a project whose dependency failed counts here; a test the spec skipped itself does not.
  const dependent = run.notRun.filter((group) => group.cause.length > 0).flatMap((group) => group.projects).reduce((sum, each) => sum + each.skipped, 0);
  const tests = plural(counts.skipped, "test");
  const tail = dependent === counts.skipped ? `${tests} that ${counts.skipped === 1 ? "depends" : "depend"} on ${projects.length === 1 ? "it" : "them"} did not run` : `${tests} did not run`;
  // A list of three already holds an ", and", so the rest gets a sentence of its own.
  return projects.length < 3 ? `${head}, and ${tail}.` : `${head}. ${tail[0].toUpperCase()}${tail.slice(1)}.`;
}

/**
 * The failed tests against the run before this one, and the tests that run failed and this one
 * passed. Nothing without an earlier run.
 *
 * @param {Run} run
 * @param {number} fixed
 * @param {Outcomes | undefined} previous
 */
function trendOf(run, fixed, previous) {
  if (!previous) return undefined;
  const total = run.failed.length;
  const fresh = run.failed.filter((each) => each.novelty === "new").length;
  const intermittent = run.failed.filter((each) => each.novelty === "intermittent").length;
  const still = run.failed.filter((each) => each.novelty === "recurring").length;
  const parts = [];
  if (total > 0 && fresh === total) parts.push(total === 1 ? "It is new since the previous run." : "All of them are new since the previous run.");
  else if (total > 0 && still === total) parts.push(total === 1 ? "It failed in the previous run too." : "All of them failed in the previous run too.");
  else if (total > 0 && intermittent === total) parts.push(total === 1 ? "It fails intermittently." : "All of them fail intermittently.");
  else {
    if (fresh > 0) parts.push(`${fresh} of them ${fresh === 1 ? "is" : "are"} new since the previous run.`);
    if (intermittent > 0) parts.push(`${intermittent} of them ${intermittent === 1 ? "fails" : "fail"} intermittently.`);
  }
  if (fixed > 0) parts.push(`${plural(fixed, "test")} that the previous run failed ${fixed === 1 ? "passes" : "pass"} now.`);
  return parts.length > 0 ? parts.join(" ") : undefined;
}

/**
 * The failures under each heading the summary and the run page group them by, in reading order. A
 * run with no history puts every failure under "Failed".
 *
 * @param {FailedCase[]} failed
 * @returns {{ title: string, cases: FailedCase[] }[]}
 */
export function byNovelty(failed) {
  /** @type {[Novelty, string][]} */
  const order = [
    ["new", "New"],
    ["recurring", "Still failing"],
    ["intermittent", "Intermittent"],
    ["unknown", "Failed"],
  ];
  return order.map(([kind, title]) => ({ title, cases: failed.filter((each) => each.novelty === kind) })).filter((each) => each.cases.length > 0);
}

/**
 * How long a failed test has been failing, as the words around one link.
 *
 * @param {FailedCase} each
 * @returns {{ before: string, link: Link, after: string } | undefined}
 */
export function historyParts(each) {
  const history = each.history;
  if (!history) return undefined;
  const same = each.sameCommit ? ", which ran the same commit" : "";
  if (history.kind === "new") return { before: "New since ", link: { text: "the last run that ran it", href: history.previous.url }, after: same };
  if (history.kind === "intermittent") {
    return {
      before: `Intermittent: failed in ${history.failures} of the ${history.runs} runs before this one, and passed in `,
      link: { text: "the last run that ran it", href: history.previous.url },
      after: same,
    };
  }
  return {
    before: `Failing for ${history.runs + 1} runs, since `,
    link: { text: history.since.startedAt ? shortDate(history.since.startedAt) : "an earlier run", href: history.since.url },
    after: history.always ? " or earlier" : "",
  };
}

/**
 * The counts of one group as a clause: `1270 passed, 1 failed, 61 did not run`. A zero is left out
 * except for passed.
 *
 * @param {Counts} counts
 */
export function countsClause(counts) {
  const parts = [`${counts.passed} passed`];
  if (counts.failed > 0) parts.push(`${counts.failed} failed`);
  if (counts.flaky > 0) parts.push(`${counts.flaky} flaky`);
  if (counts.pins > 0) parts.push(`${plural(counts.pins, "pin")} to remove`);
  if (counts.skipped > 0) parts.push(`${counts.skipped} did not run`);
  return parts.join(", ");
}

/**
 * Every group's counts as one sentence each, the product cases first. A group that ran no test is
 * left out.
 *
 * @param {Record<Group, Counts>} groups
 */
export function groupsSentence(groups) {
  /** @type {[Group, string][]} */
  const names = [
    ["product", "Product cases"],
    ["setup", "Setup and teardown"],
    ["suite", "Suite checks"],
  ];
  return names
    .filter(([group]) => group === "product" || totalOf(groups[group]) > 0)
    .map(([group, name]) => `${name}: ${countsClause(groups[group])}.`)
    .join(" ");
}
