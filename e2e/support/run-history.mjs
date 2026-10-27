/**
 * What earlier runs of the workflow said about each test. Every run writes its own outcomes to
 * `outcomes.json`, which the workflow uploads as the small `e2e-outcomes` artifact, and before the
 * summary and the email it downloads that artifact from earlier runs on the same branch into the
 * directory `E2E_HISTORY_DIR` names, one subdirectory per run.
 */
import fs from "node:fs";
import path from "node:path";
import { eachTest, groupCounts, readIfPresent } from "./run-report.mjs";

export const OUTCOMES_FILE = "outcomes.json";

/**
 * A run lists only its failed and skipped tests. A test it lists in neither passed, or did not exist
 * yet, and both read the same way here: a failure after it is new.
 *
 * `status` is the one `.last-run.json` records, or `failed` when coverage reconciliation found a
 * problem in a run whose tests passed. `counts` are the product cases, and the run took
 * `durationMs` to `finishedAt`. A file written before these fields existed leaves them out, and the
 * readers treat each one as unknown.
 *
 * @typedef {import("./run-report.mjs").Counts} Counts
 * @typedef {{
 *   runId?: string, url?: string, sha?: string, startedAt?: string, finishedAt?: string, durationMs?: number,
 *   status?: string, counts?: Counts, failed: string[], skipped: string[]
 * }} Outcomes
 * @typedef {{ kind: "new", previous: Outcomes }
 *   | { kind: "intermittent", previous: Outcomes, failures: number, runs: number }
 *   | { kind: "recurring", runs: number, since: Outcomes, always: boolean }} History
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {Record<string, string | undefined>} Env
 */

/**
 * The key a test keeps from run to run: its line number moves with every edit above it, its title
 * and project do not.
 *
 * @param {string} project
 * @param {string} file
 * @param {string} title
 */
export const testKey = (project, file, title) => `${project} › ${file} › ${title}`;

/**
 * @param {Report} report
 * @param {Env} env
 * @param {{ status: string }} [lastRun]
 * @param {{ problems: number }} [coverage]
 * @returns {Outcomes}
 */
export function outcomesOf(report, env, lastRun, coverage) {
  /** @type {Outcomes} */
  const outcomes = { failed: [], skipped: [] };
  const repoUrl = env.GITHUB_REPOSITORY && `${env.GITHUB_SERVER_URL ?? "https://github.com"}/${env.GITHUB_REPOSITORY}`;
  if (env.GITHUB_RUN_ID) {
    outcomes.runId = env.GITHUB_RUN_ID;
    if (repoUrl) outcomes.url = `${repoUrl}/actions/runs/${env.GITHUB_RUN_ID}`;
  }
  const sha = env.GITHUB_SHA ?? report.config.metadata?.gitCommit?.hash;
  if (sha) outcomes.sha = sha;
  const { startTime, duration } = report.stats;
  if (startTime) outcomes.startedAt = startTime;
  if (duration !== undefined) outcomes.durationMs = Math.round(duration);
  if (startTime && duration !== undefined) outcomes.finishedAt = new Date(Math.round(Date.parse(startTime) + duration)).toISOString();
  outcomes.status = lastRun?.status ?? (report.stats.unexpected > 0 || report.errors.length > 0 ? "failed" : "passed");
  if (outcomes.status === "passed" && (coverage?.problems ?? 0) > 0) outcomes.status = "failed";
  outcomes.counts = groupCounts(report).product;
  eachTest(report, (spec, test, title) => {
    if (test.status === "unexpected") outcomes.failed.push(testKey(test.projectName, spec.file, title));
    if (test.status === "skipped") outcomes.skipped.push(testKey(test.projectName, spec.file, title));
  });
  return outcomes;
}

/**
 * Every earlier run under `dir` that left its outcomes, the latest first. A run downloaded without
 * them, or with a file that does not parse, is left out.
 *
 * @param {string | undefined} dir
 * @returns {Outcomes[]}
 */
export function readHistory(dir) {
  if (!dir || !fs.existsSync(dir)) return [];
  /** @type {Outcomes[]} */
  const runs = [];
  for (const entry of fs.readdirSync(dir)) {
    try {
      const outcomes = readIfPresent(path.join(dir, entry, OUTCOMES_FILE));
      if (outcomes?.startedAt) runs.push(outcomes);
    } catch {
      // A truncated download costs one run of history, not the summary.
    }
  }
  return runs.sort((a, b) => String(b.startedAt).localeCompare(String(a.startedAt)));
}

/**
 * How long a test that failed in this run has been failing. A run that skipped it says nothing
 * either way and is passed over. A test the latest run passed is intermittent when an earlier run
 * failed it, and new otherwise. `undefined` means no earlier run reached it.
 *
 * @param {string} key
 * @param {Outcomes[]} history the earlier runs, the latest first
 * @returns {History | undefined}
 */
export function historyOf(key, history) {
  const reached = history.filter((run) => !run.skipped.includes(key));
  if (reached.length === 0) return undefined;
  const streak = reached.findIndex((run) => !run.failed.includes(key));
  if (streak === -1) return { kind: "recurring", runs: reached.length, since: /** @type {Outcomes} */ (reached.at(-1)), always: true };
  if (streak > 0) return { kind: "recurring", runs: streak, since: reached[streak - 1], always: false };
  const failures = reached.filter((run) => run.failed.includes(key)).length;
  return failures > 0 ? { kind: "intermittent", previous: reached[0], failures, runs: reached.length } : { kind: "new", previous: reached[0] };
}

/**
 * The tests the latest earlier run failed that passed in this one. A `test.fail()` case, or one
 * `strikesAsKnown` struck, reports `expected` while it still fails, so it is not counted.
 *
 * @param {Report} report
 * @param {Outcomes | undefined} previous
 * @returns {string[]}
 */
export function fixedSince(report, previous) {
  if (!previous) return [];
  /** @type {Set<string>} */
  const passed = new Set();
  eachTest(report, (spec, test, title) => {
    if (test.expectedStatus !== "failed" && (test.status === "expected" || test.status === "flaky")) passed.add(testKey(test.projectName, spec.file, title));
  });
  return previous.failed.filter((key) => passed.has(key));
}

/**
 * Writes this run's outcomes into `dir` and returns them.
 *
 * @param {string} dir
 * @param {Report} report
 * @param {Env} env
 * @param {{ status: string }} [lastRun]
 * @param {{ problems: number }} [coverage]
 */
export function writeOutcomes(dir, report, env, lastRun, coverage) {
  const outcomes = outcomesOf(report, env, lastRun, coverage);
  fs.writeFileSync(path.join(dir, OUTCOMES_FILE), JSON.stringify(outcomes, null, 2) + "\n");
  return outcomes;
}
