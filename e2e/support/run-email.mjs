/**
 * Writes the failure email from the run's `report.json`, `.last-run.json`, and `stack.json`: the HTML
 * part to `email.html`, the plain-text part to `email.txt`, and the subject to stdout as one
 * `subject=<text>` line in the `$GITHUB_OUTPUT` format. A missing file leaves its sections out.
 *
 *     node support/run-email.mjs [<dir>] >> "$GITHUB_OUTPUT"   # dir defaults to test-results/
 *
 * The links come from the variables GitHub Actions sets (`GITHUB_SERVER_URL`, `GITHUB_REPOSITORY`,
 * `GITHUB_SHA`, `GITHUB_REF_NAME`, `GITHUB_RUN_ID`, `GITHUB_EVENT_NAME`) and from the artifact links
 * the workflow passes (`REPORT_URL`, `CLUSTER_URL`). The subject names the failed tests, or else the
 * first error raised outside any test, or else the step `FAILED_STEP` names. The earlier runs under
 * `E2E_HISTORY_DIR` mark each failed test as new, intermittent, or still failing. A `test.fail()`
 * case that passed is listed apart, as a pin to remove.
 *
 * The HTML is laid out with tables and inline styles only, because Gmail drops a `<style>` block in
 * many of its clients and Outlook renders with Word.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { eachTest, fixedPinIn, readIfPresent, shortDate, stripAnsi, utcDate } from "./run-report.mjs";
import { fixedSince, historyOf, readHistory, testKey } from "./run-history.mjs";

export const HTML_FILE = "email.html";
export const TEXT_FILE = "email.txt";
export const CARDS_LIMIT = 20;
export const ERROR_LINES = 8;
export const LINE_CHARS = 200;
const WORKFLOW_FILE = ".github/workflows/e2e-k8s.yaml";

/**
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {import("./run-history.mjs").Outcomes} Outcomes
 * @typedef {import("./run-history.mjs").History} History
 * @typedef {{ status: string }} LastRun
 * @typedef {{ startedAt?: string, target?: string, commit?: { sha?: string, branch?: string }, absent?: { name: string, reason: string }[] }} Stack
 * @typedef {Record<string, string | undefined>} Env
 *
 * @typedef {{ text: string, href?: string }} Link
 * @typedef {{ lines: string[], cut: boolean }} Excerpt
 * @typedef {{ project: string, title: string, location: Link, failedAt?: Link, error: Excerpt, retry: string, history?: History, sameCommit?: boolean }} FailedCase
 * @typedef {{ project: string, title: string, location: Link, defect?: string }} FixedPin
 * @typedef {{ name: string, skipped: number }} IdleProject
 * @typedef {{ cause: string[], projects: IdleProject[] }} NotRun
 * @typedef {{ passed: number, failed: number, pins: number, skipped: number, total: number }} Counts
 * @typedef {"failed" | "warning" | "passed"} Tone
 * @typedef {{
 *   tone: Tone, title: string, verdict: string, trend?: string, repo?: string, branch?: string, commit?: Link, commitSubject?: string,
 *   trigger: string, started?: string, duration?: string, target: string, counts?: Counts,
 *   runUrl?: string, artifactsUrl?: string, clusterUrl?: string, readmeUrl?: string, failed: FailedCase[], pins: FixedPin[],
 *   failedProjects: string[], errors: Excerpt[], failedStep?: string, notRun: NotRun[],
 *   dependenciesKnown: boolean, absent: { name: string, reason: string }[]
 * }} Email
 */

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

/** @param {string[]} names */
const joined = (names) => (names.length <= 2 ? names.join(" and ") : `${names.slice(0, -1).join(", ")}, and ${names.at(-1)}`);

/** @param {string[]} names */
const shortList = (names) => (names.length <= 2 ? joined(names) : `${names.slice(0, 2).join(", ")}, and ${names.length - 2} more`);

/**
 * @param {number} count
 * @param {string} noun
 */
const plural = (count, noun) => `${count} ${count === 1 ? noun : `${noun}s`}`;

/** @param {number} ms */
function duration(ms) {
  const minutes = Math.round(ms / 60_000);
  if (minutes < 1) return `${Math.round(ms / 1000)} s`;
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

/**
 * Projects that ran none of their tests, grouped by the failed projects they depend on. The JSON
 * report leaves `dependencies` out, so `playwright.config.ts` copies them into each project's
 * metadata; a report without them yields one group with no cause.
 *
 * @param {Report} report
 * @param {Map<string, { total: number, skipped: number, failed: number }>} perProject
 * @returns {NotRun[]}
 */
function notRunOf(report, perProject) {
  const idle = [...perProject].filter(([, each]) => each.total > 0 && each.skipped === each.total);
  if (idle.length === 0) return [];
  const dependencies = new Map((report.config.projects ?? []).map((each) => [each.name, each.metadata?.dependencies ?? []]));
  /** @type {Map<string, NotRun>} */
  const groups = new Map();
  for (const [name, each] of idle) {
    /** @type {Set<string>} */
    const seen = new Set();
    const pending = [...(dependencies.get(name) ?? [])];
    while (pending.length > 0) {
      const next = /** @type {string} */ (pending.pop());
      if (seen.has(next)) continue;
      seen.add(next);
      pending.push(...(dependencies.get(next) ?? []));
    }
    const cause = [...perProject.keys()].filter((project) => seen.has(project) && (perProject.get(project)?.failed ?? 0) > 0);
    const key = cause.join(" ");
    if (!groups.has(key)) groups.set(key, { cause, projects: [] });
    groups.get(key)?.projects.push({ name, skipped: each.skipped });
  }
  for (const group of groups.values()) group.projects.sort((a, b) => b.skipped - a.skipped);
  return [...groups.values()];
}

/**
 * @param {{ report?: Report, lastRun?: LastRun, stack?: Stack, history?: Outcomes[], env?: Env }} run
 * @returns {Email}
 */
export function emailOf({ report, lastRun, stack, history = [], env = {} }) {
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

  /** @type {Map<string, { total: number, skipped: number, failed: number }>} */
  const perProject = new Map((report?.config.projects ?? []).map((each) => [each.name, { total: 0, skipped: 0, failed: 0 }]));
  /** @type {FailedCase[]} */
  const failed = [];
  /** @type {FixedPin[]} */
  const pins = [];
  if (report) {
    const e2eDir = path.dirname(report.config.rootDir);
    const isFixedPin = fixedPinIn(report);
    eachTest(report, (spec, test, title) => {
      const project = perProject.get(test.projectName) ?? { total: 0, skipped: 0, failed: 0 };
      perProject.set(test.projectName, project);
      project.total++;
      if (test.status === "skipped") project.skipped++;
      if (test.status !== "unexpected") return;
      const location = { text: `${spec.file}:${spec.line}`, href: blob(`specs/${spec.file}`, spec.line) };
      if (isFixedPin(test)) {
        const defect = test.annotations?.find((each) => each.type === "fail" && each.description)?.description;
        pins.push({ project: test.projectName, title, location, defect });
        return;
      }
      project.failed++;
      const error = test.results.at(-1)?.errors?.[0];
      const thrown = error?.location && path.relative(e2eDir, error.location.file);
      failed.push({
        project: test.projectName,
        title,
        location,
        failedAt: thrown && error?.location ? { text: `${thrown}:${error.location.line}`, href: blob(thrown, error.location.line) } : undefined,
        error: excerpt(error?.message),
        // Every iteration of a loop shares one file and line, so the pattern picks out the one that failed.
        retry: `npm run test:${target} -- --project=${test.projectName} --no-deps specs/${spec.file}:${spec.line} -g "${grepPathPattern(title)}"`,
        history: historyOf(testKey(test.projectName, spec.file, title), history),
      });
      const last = failed.at(-1);
      if (last?.history && last.history.kind !== "recurring") last.sameCommit = Boolean(sha) && last.history.previous.sha === sha;
    });
  }
  // Grouped by project, in the order the config declares the projects.
  const failedProjects = [...perProject.keys()].filter((name) => (perProject.get(name)?.failed ?? 0) > 0);
  failed.sort((a, b) => failedProjects.indexOf(a.project) - failedProjects.indexOf(b.project));

  const errors = (report?.errors ?? []).map((each) => excerpt(each.message, 12));
  const stats = report?.stats;
  /** @type {Counts | undefined} */
  const counts = stats && {
    passed: stats.expected,
    failed: stats.unexpected - pins.length,
    pins: pins.length,
    skipped: stats.skipped,
    total: stats.expected + stats.unexpected + stats.skipped,
  };
  const failedStep = failed.length === 0 && pins.length === 0 && errors.length === 0 ? env.FAILED_STEP || undefined : undefined;

  /** @type {[Tone, string]} */
  const [tone, verdict] =
    failed.length > 0
      ? ["failed", "failed"]
      : lastRun?.status === "timedout"
        ? ["warning", "timed out"]
        : errors.length > 0 || failedStep || !report
          ? ["failed", "failed"]
          : pins.length > 0
            ? ["warning", "found fixed defects"]
            : ["passed", "passed"];
  const event = env.GITHUB_EVENT_NAME;
  const started = stats?.startTime ?? stack?.startedAt;
  // A run whose every failed test also failed the run before it says so in its title.
  const still = failed.length > 0 && errors.length === 0 && failed.every((each) => each.history?.kind === "recurring");
  /** @type {Email} */
  const email = {
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
    target,
    counts,
    runUrl,
    artifactsUrl: env.REPORT_URL || (runUrl && `${runUrl}#artifacts`),
    clusterUrl: env.CLUSTER_URL || undefined,
    readmeUrl: blob("README.md") && `${blob("README.md")}#reproducing-one-case`,
    failed,
    pins,
    failedProjects,
    errors,
    failedStep,
    notRun: report ? notRunOf(report, perProject) : [],
    dependenciesKnown: (report?.config.projects ?? []).some((each) => each.metadata?.dependencies),
    absent: stack?.absent ?? [],
  };
  email.verdict = verdictOf(email, lastRun?.status === "timedout", Boolean(report));
  // After the trend, whose "It" names the failed test.
  const trend = [report && trendOf(email, fixedSince(report, history[0]).length, history[0]), failed.length > 0 && pins.length > 0 && noLongerReproduce(pins.length)];
  email.trend = trend.filter(Boolean).join(" ") || undefined;
  return email;
}

/**
 * The run's outcome as one sentence, the way the tone was chosen: failed tests first, then a
 * timeout, an error outside any test, a failed step, pins to remove, and a pass.
 *
 * @param {Email} email
 * @param {boolean} timedOut
 * @param {boolean} reported
 */
function verdictOf(email, timedOut, reported) {
  const counts = email.counts ?? { passed: 0, failed: 0, pins: 0, skipped: 0, total: 0 };
  const ran = counts.passed + counts.failed + counts.pins;
  const didNotRun = counts.skipped > 0 ? `, and ${plural(counts.skipped, "test")} did not run` : "";
  if (email.failed.length > 0) return failedVerdict(email, counts);
  if (timedOut) return `The run timed out${email.duration ? ` after ${email.duration}` : ""}${didNotRun}.`;
  const error = email.errors[0]?.lines[0];
  if (error !== undefined) {
    const line = error.replace(/^Error: /, "");
    return ran === 0 ? `The run stopped before its tests finished: ${line}` : `An error outside any test failed the run: ${line}`;
  }
  if (email.failedStep) {
    // The workflow's fallback, "a step after the suite", reads as prose; a step's own name is quoted.
    return /^[a-z]/.test(email.failedStep) ? `The run failed at ${email.failedStep}.` : `The run failed at the step "${email.failedStep}".`;
  }
  if (!reported) return "The run failed before it wrote a report.";
  if (counts.pins > 0) return `${noLongerReproduce(counts.pins)} Every other test ${counts.skipped > 0 ? "that ran " : ""}passed${didNotRun}.`;
  const passed = counts.skipped > 0 ? `All ${plural(ran, "test")} that ran passed` : `All ${plural(ran, "test")} passed`;
  return `${passed}${counts.skipped > 0 ? `, and ${counts.skipped} did not run` : ""}.`;
}

/** @param {number} pins */
const noLongerReproduce = (pins) => `${plural(pins, "pinned defect")} no longer ${pins === 1 ? "reproduces" : "reproduce"}.`;

/**
 * The failed tests, the projects they failed in, and the tests those projects kept from running.
 *
 * @param {Email} email
 * @param {Counts} counts
 */
function failedVerdict(email, counts) {
  const projects = email.failedProjects;
  const where = projects.length <= 3 ? joined(projects) : `${projects.slice(0, 2).join(", ")}, and ${projects.length - 2} other projects`;
  const head = `${plural(email.failed.length, "test")} failed in ${where}`;
  if (counts.skipped === 0) return `${head}.`;
  // Only a project whose dependency failed counts here; a test the spec skipped itself does not.
  const dependent = email.notRun.filter((group) => group.cause.length > 0).flatMap((group) => group.projects).reduce((sum, each) => sum + each.skipped, 0);
  const tests = plural(counts.skipped, "test");
  const tail = dependent === counts.skipped ? `${tests} that ${counts.skipped === 1 ? "depends" : "depend"} on ${projects.length === 1 ? "it" : "them"} did not run` : `${tests} did not run`;
  // A list of three already holds an ", and", so the rest gets a sentence of its own.
  return projects.length < 3 ? `${head}, and ${tail}.` : `${head}. ${tail[0].toUpperCase()}${tail.slice(1)}.`;
}

/**
 * The failed tests against the run before this one, and the tests that run failed and this one
 * passed. Nothing without an earlier run.
 *
 * @param {Email} email
 * @param {number} fixed
 * @param {Outcomes | undefined} previous
 */
function trendOf(email, fixed, previous) {
  if (!previous) return undefined;
  const total = email.failed.length;
  const fresh = email.failed.filter((each) => each.history?.kind === "new").length;
  const intermittent = email.failed.filter((each) => each.history?.kind === "intermittent").length;
  const still = email.failed.filter((each) => each.history?.kind === "recurring").length;
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
 * How long a failed test has been failing, as the words around one link.
 *
 * @param {FailedCase} each
 * @returns {{ before: string, link: Link, after: string } | undefined}
 */
function historyParts(each) {
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

/** @param {Email} email */
function headline(email) {
  const { counts } = email;
  if (counts && counts.failed > 0) {
    const tests = counts.failed === 1 ? "1 test" : `${counts.failed} tests`;
    return `${tests} in ${shortList(email.failedProjects)}`;
  }
  return email.errors[0]?.lines[0] ?? (email.failedStep && `${email.failedStep} failed`);
}

/**
 * One line, because it travels as a step output.
 *
 * @param {Email} email
 */
export function emailSubject(email) {
  const ref = email.branch && email.commit ? ` (${email.branch} @ ${email.commit.text})` : "";
  const { counts } = email;
  let detail = "";
  const pins = counts && counts.pins > 0 ? plural(counts.pins, "pin") + " to remove" : "";
  if (counts && counts.failed > 0) {
    detail = `: ${headline(email)}${counts.skipped > 0 ? `, ${counts.skipped} skipped` : ""}${pins ? `, ${pins}` : ""}`;
  } else if (pins && email.errors.length === 0) {
    detail = `: ${pins}`;
  } else if (email.errors[0]) {
    const line = email.errors[0].lines[0] ?? "";
    detail = `: ${line.length > 100 ? `${line.slice(0, 99)}…` : line}`;
  } else if (email.failedStep) {
    detail = ` at ${email.failedStep}`;
  }
  return `${email.title}${detail}${ref}`.replace(/\s+/g, " ");
}

/**
 * The inbox preview line: the verdict, then the counts it leaves out.
 *
 * @param {Email} email
 */
export function preheader(email) {
  const verdict = (/[.!?]$/.test(email.verdict) ? email.verdict : `${email.verdict}.`) + (email.trend ? ` ${email.trend}` : "");
  const parts = [];
  if (email.counts && email.counts.total > 0) parts.push(`${email.counts.passed} passed`);
  const tail = [email.duration, `on ${email.target}`].filter(Boolean).join(" ");
  const counts = parts.length > 0 ? ` ${parts.join(", ")}.` : "";
  return `${verdict}${counts} ${tail[0].toUpperCase()}${tail.slice(1)}.`;
}

/** @param {string} text */
export const escapeHtml = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

const SANS = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";

/** @type {Record<Tone, string>} */
const BAND = { failed: "#b42318", warning: "#b54708", passed: "#067647" };
/** White at 90% over each band, written out because Outlook ignores `opacity`. */
const BAND_SOFT = { failed: "#f8e9e8", warning: "#f8ede6", passed: "#e6f1ed" };
const LINK = "#175cd3";
const MUTED = "#667085";
const TEXT = "#101828";
const RED_TINT = "#fef3f2";
const RED_INK = "#7a271a";
const NIGHT = "#101828";

/**
 * A project name in prose, with non-breaking hyphens so that a phone does not wrap
 * `brokers-micro-seed` at a hyphen. Commands, links, and the plain-text part keep the name as is.
 *
 * @param {string} name
 */
const projectHtml = (name) => escapeHtml(name).replace(/-/g, "&#8209;");

/**
 * @param {Link} link
 * @param {string} style
 */
function anchor(link, style = "") {
  const text = escapeHtml(link.text);
  return link.href ? `<a href="${escapeHtml(link.href)}" style="color:${LINK};text-decoration:none;${style}">${text}</a>` : text;
}

/**
 * Lines in a monospace box that wraps at a space, or inside a token too long for a line; leading
 * spaces keep their width. A table cell rather than a `div`, because Outlook drops a `div`'s padding.
 *
 * @param {string[]} lines
 * @param {string} background
 * @param {string} color
 * @param {string} style
 */
function codeBox(lines, background, color, style = "") {
  const body = lines.map((line) => escapeHtml(line).replace(/^ +/, (spaces) => "&nbsp;".repeat(spaces.length))).join("<br>");
  return (
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;table-layout:fixed;"><tr>` +
    `<td bgcolor="${background}" style="background-color:${background};color:${color};border-radius:4px;padding:10px 12px;${style}` +
    `font-family:${MONO};font-size:12px;line-height:18px;word-break:break-word;overflow-wrap:anywhere;">${body}</td></tr></table>`
  );
}

/** @param {string} text */
function sectionTitle(text) {
  return `<tr><td style="padding:28px 28px 4px;font-family:${SANS};font-size:13px;line-height:18px;font-weight:700;color:#475467;">${text}</td></tr>`;
}

/** @param {string} name */
const badge = (name) =>
  `<span style="display:inline-block;background-color:#eef4ff;color:#3538cd;border-radius:999px;padding:2px 10px;font-family:${SANS};font-size:12px;line-height:18px;font-weight:600;">${projectHtml(name)}</span>`;

/** @param {Counts} counts */
function tiles(counts) {
  /** @type {[string, number, string, string][]} */
  const shown = [
    ["Passed", counts.passed, "#ecfdf3", "#067647"],
    ["Failed", counts.failed, "#fef3f2", "#b42318"],
    ["Skipped", counts.skipped, "#f2f4f7", "#344054"],
  ];
  if (counts.pins > 0) shown.push(["Pins to remove", counts.pins, "#fffaeb", "#b54708"]);
  const tile = shown.map(([label, value, background, color]) => {
    const [bg, fg] = value === 0 ? ["#f9fafb", MUTED] : [background, color];
    return (
      `<td width="${Math.floor(100 / shown.length)}%" valign="top" style="padding:0 3px;">` +
      `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>` +
      `<td align="center" bgcolor="${bg}" style="background-color:${bg};border-radius:6px;padding:12px 2px;font-family:${SANS};">` +
      `<div style="font-size:22px;line-height:28px;font-weight:700;color:${fg};">${value}</div>` +
      `<div style="font-size:12px;line-height:15px;font-weight:600;color:${fg};">${label}</div>` +
      `</td></tr></table></td>`
    );
  });
  return (
    `<tr><td style="padding:20px 25px 0;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>${tile.join("")}</tr></table>` +
    `<div style="padding-top:8px;text-align:center;font-family:${SANS};font-size:12px;line-height:16px;color:${MUTED};">${counts.total} tests</div>` +
    `</td></tr>`
  );
}

/** @param {FailedCase} each */
function card(each) {
  const failedAt = each.failedAt ? `<br><span style="color:${MUTED};">failed at</span> ${anchor(each.failedAt)}` : "";
  const parts = historyParts(each);
  const color = each.history?.kind === "new" ? "#b42318" : each.history?.kind === "intermittent" ? "#b54708" : "#475467";
  const history = parts
    ? `<div style="padding-top:6px;font-size:13px;line-height:19px;font-weight:600;color:${color};">${escapeHtml(parts.before)}${anchor(parts.link)}${escapeHtml(parts.after)}</div>`
    : "";
  const error = each.error.lines.length > 0 ? [...each.error.lines, ...(each.error.cut ? ["…"] : [])] : ["(no error message)"];
  return (
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr><td style="padding-top:10px;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;table-layout:fixed;border:1px solid #e4e7ec;border-left:4px solid #d92d20;border-radius:6px;">` +
    `<tr><td style="padding:14px 16px;font-family:${SANS};">` +
    `<div style="font-size:15px;line-height:21px;font-weight:600;color:${TEXT};">${escapeHtml(each.title)}</div>` +
    `<div style="padding-top:4px;font-family:${MONO};font-size:12px;line-height:18px;word-break:break-word;overflow-wrap:anywhere;">${anchor(each.location)}${failedAt}</div>` +
    history +
    `<div style="padding-top:10px;">${codeBox(error, RED_TINT, RED_INK)}</div>` +
    `<div style="padding-top:12px;font-size:12px;line-height:16px;font-weight:600;color:#475467;">Retry locally</div>` +
    `<div style="padding-top:4px;">${codeBox([each.retry], NIGHT, "#f2f4f7")}</div>` +
    `</td></tr></table></td></tr></table>`
  );
}

/** @param {Email} email */
function failedSection(email) {
  if (email.failed.length === 0) return "";
  const shown = email.failed.slice(0, CARDS_LIMIT);
  let body = "";
  for (const name of email.failedProjects) {
    const cases = shown.filter((each) => each.project === name);
    if (cases.length === 0) continue;
    const total = email.failed.filter((each) => each.project === name).length;
    body +=
      `<div style="padding-top:14px;font-family:${SANS};font-size:13px;line-height:18px;color:${MUTED};">${badge(name)}&nbsp; ${total} failed</div>` +
      cases.map(card).join("");
  }
  const more = email.failed.length - shown.length;
  if (more > 0) {
    const where = email.runUrl ? anchor({ text: "on the run page", href: email.artifactsUrl }) : "of the run";
    body += `<div style="padding-top:14px;font-family:${SANS};font-size:14px;line-height:20px;color:#344054;">And ${more} more, listed in the HTML report ${where}.</div>`;
  }
  return sectionTitle("What failed") + `<tr><td style="padding:0 28px;">${body}</td></tr>`;
}

const PINS_LEAD = "Each test.fail() case below passed, so the defect it pins no longer reproduces. Remove the annotation and close the defect's issue.";

/** @param {FixedPin} each */
function pinCard(each) {
  const defect = each.defect ? `<div style="padding-top:6px;font-size:13px;line-height:19px;color:#475467;">${escapeHtml(each.defect)}</div>` : "";
  return (
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr><td style="padding-top:10px;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;table-layout:fixed;border:1px solid #e4e7ec;border-left:4px solid #f79009;border-radius:6px;">` +
    `<tr><td style="padding:14px 16px;font-family:${SANS};">` +
    `<div style="font-size:15px;line-height:21px;font-weight:600;color:${TEXT};">${escapeHtml(each.title)}</div>` +
    `<div style="padding-top:4px;font-family:${MONO};font-size:12px;line-height:18px;word-break:break-word;overflow-wrap:anywhere;">${badge(each.project)}&nbsp; ${anchor(each.location)}</div>` +
    defect +
    `</td></tr></table></td></tr></table>`
  );
}

/** @param {Email} email */
function pinsSection(email) {
  if (email.pins.length === 0) return "";
  return (
    sectionTitle("Pins to remove") +
    `<tr><td style="padding:4px 28px 0;font-family:${SANS};font-size:14px;line-height:20px;color:#344054;">${escapeHtml(PINS_LEAD)}${email.pins.map(pinCard).join("")}</td></tr>`
  );
}

/** @param {Email} email */
function errorsSection(email) {
  if (email.errors.length === 0) return "";
  const boxes = email.errors
    .map((each) => codeBox([...each.lines, ...(each.cut ? ["…"] : [])], RED_TINT, RED_INK, "border-left:4px solid #d92d20;"))
    .join(`<div style="height:8px;line-height:8px;font-size:0;">&nbsp;</div>`);
  return sectionTitle("Errors outside any test") + `<tr><td style="padding:8px 28px 0;">${boxes}</td></tr>`;
}

/**
 * @param {NotRun} group
 * @param {(name: string) => string} name how a failed project's name is written
 */
function notRunLead(group, name = (each) => each) {
  if (group.cause.length === 0) return "These projects ran none of their tests:";
  return `${joined(group.cause.map(name))} failed, so ${group.projects.length === 1 ? "this project" : "these projects"} did not run:`;
}

/** @param {Email} email */
function notRunSection(email) {
  if (email.notRun.length === 0) return "";
  const groups = email.notRun.map((group) => {
    const rows = group.projects
      .map(
        (each) =>
          `<tr><td style="padding:3px 0;font-family:${MONO};font-size:12px;line-height:18px;color:${TEXT};">${projectHtml(each.name)}</td>` +
          `<td align="right" style="padding:3px 0 3px 12px;font-family:${SANS};font-size:13px;line-height:18px;color:${MUTED};white-space:nowrap;">${plural(each.skipped, "test")}</td></tr>`,
      )
      .join("");
    return (
      `<div style="font-size:14px;line-height:21px;color:#93370d;">${notRunLead(group, (each) => `<strong>${projectHtml(each)}</strong>`)}</div>` +
      `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin-top:6px;">${rows}</table>`
    );
  });
  const hint = email.dependenciesKnown
    ? ""
    : `<div style="padding-top:8px;font-size:13px;line-height:19px;color:${MUTED};">Playwright skips a project when a project it depends on fails.</div>`;
  return (
    sectionTitle("What did not run") +
    `<tr><td style="padding:8px 28px 0;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr>` +
    `<td bgcolor="#fffaeb" style="background-color:#fffaeb;border:1px solid #fedf89;border-radius:6px;padding:12px 16px;font-family:${SANS};">` +
    groups.join(`<div style="height:12px;line-height:12px;font-size:0;">&nbsp;</div>`) +
    hint +
    `</td></tr></table></td></tr>`
  );
}

/** @param {string} text */
const inlineCode = (text) =>
  `<span style="font-family:${MONO};font-size:12px;background-color:#f2f4f7;border-radius:4px;padding:1px 5px;color:${TEXT};">${escapeHtml(text)}</span>`;

/** @param {Email} email */
function footer(email) {
  const absent =
    email.absent.length === 0
      ? ""
      : `<div style="padding-bottom:14px;font-size:12px;line-height:18px;color:${MUTED};">` +
        `<strong style="color:#475467;">Left out on this target:</strong> ` +
        email.absent.map((each) => `${inlineCode(each.name)} ${escapeHtml(each.reason)}`).join("; ") +
        `</div>`;
  const readme = email.readmeUrl ? anchor({ text: "Reproducing one case", href: email.readmeUrl }) : "Reproducing one case";
  return (
    `<tr><td style="padding:28px 28px 0;"><div style="border-top:1px solid #e4e7ec;font-size:0;line-height:0;">&nbsp;</div></td></tr>` +
    `<tr><td style="padding:16px 28px 24px;font-family:${SANS};">` +
    absent +
    `<div style="font-size:13px;line-height:20px;color:#475467;">Reproduce the run with ${inlineCode(`npm run test:${email.target}`)} in ${inlineCode("e2e/")}. ` +
    `To rerun one case against a kept corpus, see ${readme} in the e2e README.</div>` +
    `<div style="padding-top:10px;font-size:12px;line-height:18px;color:${MUTED};">Sent by ${escapeHtml(WORKFLOW_FILE)}</div>` +
    `</td></tr>`
  );
}

/**
 * The verdict, with each project it names written the way the rest of the HTML writes a project.
 *
 * @param {Email} email
 */
function verdictHtml(email) {
  const names = [...new Set([...email.failedProjects, ...email.notRun.flatMap((group) => [...group.cause, ...group.projects.map((each) => each.name)])])]
    .filter((name) => name.includes("-"))
    .sort((a, b) => b.length - a.length)
    .map((name) => escapeHtml(name).replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  const html = escapeHtml(email.verdict);
  if (names.length === 0) return html;
  return html.replace(new RegExp(`(?<![\\w-])(?:${names.join("|")})(?![\\w-])`, "g"), (name) => name.replace(/-/g, "&#8209;"));
}

/**
 * When and where the run ran, as two short lines.
 *
 * @param {Email} email
 * @returns {string[]}
 */
function when(email) {
  const where = `on the ${email.target} target`;
  return [
    email.started ? `${email.trigger}, started ${email.started}` : email.trigger,
    email.duration ? `${email.duration} ${where}` : `${where[0].toUpperCase()}${where.slice(1)}`,
  ];
}

/** @param {Email} email */
function header(email) {
  const band = BAND[email.tone];
  const white = "color:#ffffff;";
  const soft = `color:${BAND_SOFT[email.tone]};`;
  const commit = email.commit
    ? email.commit.href
      ? `<a href="${escapeHtml(email.commit.href)}" style="${white}text-decoration:underline;font-family:${MONO};">${escapeHtml(email.commit.text)}</a>`
      : `<span style="font-family:${MONO};">${escapeHtml(email.commit.text)}</span>`
    : "";
  const ref = [email.branch && `<span style="font-family:${MONO};">${escapeHtml(email.branch)}</span>`, commit].filter(Boolean).join(" @ ");
  return (
    `<tr><td bgcolor="${band}" style="background-color:${band};padding:24px 28px;border-radius:8px 8px 0 0;font-family:${SANS};">` +
    (email.repo ? `<div style="font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.04em;${soft}">${escapeHtml(email.repo)}</div>` : "") +
    `<div style="padding-top:6px;font-size:24px;line-height:30px;font-weight:700;${white}">${escapeHtml(email.title)}</div>` +
    `<div style="padding-top:6px;font-size:17px;line-height:24px;font-weight:normal;${white}">${verdictHtml(email)}${email.trend ? ` ${escapeHtml(email.trend)}` : ""}</div>` +
    (ref ? `<div style="padding-top:14px;font-size:14px;line-height:20px;font-weight:600;${white}">${ref}</div>` : "") +
    (email.commitSubject ? `<div style="padding-top:2px;font-size:14px;line-height:20px;${white}">${escapeHtml(email.commitSubject)}</div>` : "") +
    `<div style="padding-top:8px;font-size:13px;line-height:18px;${soft}">${when(email).map(escapeHtml).join("<br>")}</div>` +
    `</td></tr>`
  );
}

/** @param {Email} email */
function actions(email) {
  if (!email.runUrl) return "";
  const links = [
    email.artifactsUrl && anchor({ text: "HTML report and artifacts", href: email.artifactsUrl }),
    email.clusterUrl && anchor({ text: "Cluster dump", href: email.clusterUrl }),
    email.commit?.href && anchor({ text: `Commit ${email.commit.text}`, href: email.commit.href }),
  ].filter(Boolean);
  // mso-padding-alt gives Outlook, which ignores an anchor's padding, the same button.
  return (
    `<tr><td style="padding:22px 28px 0;font-family:${SANS};">` +
    `<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr>` +
    `<td bgcolor="${LINK}" style="background-color:${LINK};border-radius:6px;mso-padding-alt:11px 22px;">` +
    `<a href="${escapeHtml(email.runUrl)}" style="display:inline-block;padding:11px 22px;font-family:${SANS};font-size:15px;line-height:20px;font-weight:600;color:#ffffff;text-decoration:none;">Open the run</a>` +
    `</td></tr></table>` +
    `<div style="padding-top:12px;font-size:14px;line-height:20px;color:${MUTED};">${links.join("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; ")}</div>` +
    `</td></tr>`
  );
}

/**
 * @param {Email} email
 * @returns {string}
 */
export function emailHtml(email) {
  // Filler after the preview line keeps the inbox from appending the body's first words.
  const filler = "&#8199;&#65279;&#847; ".repeat(80);
  return [
    `<!doctype html>`,
    `<html lang="en">`,
    `<head>`,
    `<meta charset="utf-8">`,
    `<meta name="viewport" content="width=device-width, initial-scale=1">`,
    `<meta name="color-scheme" content="light">`,
    `<meta name="supported-color-schemes" content="light">`,
    `<title>${escapeHtml(emailSubject(email))}</title>`,
    `</head>`,
    `<body style="margin:0;padding:0;background-color:#f2f4f7;">`,
    `<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:#f2f4f7;opacity:0;">${escapeHtml(preheader(email))}${filler}</div>`,
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f2f4f7" style="background-color:#f2f4f7;">`,
    `<tr><td align="center" style="padding:24px 12px;">`,
    `<!--[if mso]><table role="presentation" width="640" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->`,
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" style="max-width:640px;background-color:#ffffff;border:1px solid #e4e7ec;border-radius:8px;border-collapse:separate;">`,
    header(email),
    email.counts ? tiles(email.counts) : "",
    actions(email),
    errorsSection(email),
    failedSection(email),
    pinsSection(email),
    notRunSection(email),
    footer(email),
    `</table>`,
    `<!--[if mso]></td></tr></table><![endif]-->`,
    `</td></tr>`,
    `</table>`,
    `</body>`,
    `</html>`,
    ``,
  ].join("\n");
}

/**
 * @param {string} text
 * @returns {string[]}
 */
const underlined = (text) => [text, "-".repeat(text.length)];

/**
 * @param {Email} email
 * @returns {string}
 */
export function emailText(email) {
  const out = [email.trend ? `${email.verdict} ${email.trend}` : email.verdict, ""];
  if (email.repo) out.push(email.repo);
  /** @type {[string, string | undefined][]} */
  const facts = [
    ["Branch", email.branch],
    ["Commit", email.commit && [email.commit.text, email.commitSubject].filter(Boolean).join(" ")],
    ["Trigger", email.trigger],
    ["Started", email.started],
    ["Duration", email.duration],
    ["Target", email.target],
  ];
  for (const [label, value] of facts) if (value) out.push(`${label.padEnd(10)}${value}`);
  if (email.counts) {
    const c = email.counts;
    out.push("", `${c.passed} passed, ${c.failed} failed, ${c.skipped} skipped${c.pins > 0 ? `, ${plural(c.pins, "pin")} to remove` : ""} (${c.total} tests)`);
  }
  if (email.runUrl) {
    out.push("", `Open the run: ${email.runUrl}`);
    if (email.artifactsUrl) out.push(`HTML report and artifacts: ${email.artifactsUrl}`);
    if (email.clusterUrl) out.push(`Cluster dump: ${email.clusterUrl}`);
    if (email.commit?.href) out.push(`Commit ${email.commit.text}: ${email.commit.href}`);
  }
  /** @param {Excerpt} each */
  const indented = (each) => [...each.lines, ...(each.cut ? ["…"] : [])].map((line) => `      ${line}`);
  if (email.errors.length > 0) {
    out.push("", "", ...underlined("Errors outside any test"));
    for (const each of email.errors) out.push("", ...indented(each).map((line) => line.slice(4)));
  }
  if (email.failed.length > 0) {
    out.push("", "", ...underlined("What failed"));
    const shown = email.failed.slice(0, CARDS_LIMIT);
    for (const name of email.failedProjects) {
      const cases = shown.filter((each) => each.project === name);
      if (cases.length === 0) continue;
      out.push("", `[${name}] ${email.failed.filter((each) => each.project === name).length} failed`);
      for (const each of cases) {
        out.push("", `  * ${each.title}`, `    ${each.location.text}${each.location.href ? `  ${each.location.href}` : ""}`);
        if (each.failedAt) out.push(`    failed at ${each.failedAt.text}`);
        const parts = historyParts(each);
        if (parts) out.push(`    ${parts.before}${parts.link.text}${parts.after}${parts.link.href ? `  ${parts.link.href}` : ""}`);
        out.push(...indented(each.error), "    Retry locally:", `      ${each.retry}`);
      }
    }
    const more = email.failed.length - shown.length;
    if (more > 0) out.push("", `And ${more} more, listed in the HTML report${email.artifactsUrl ? `: ${email.artifactsUrl}` : " of the run."}`);
  }
  if (email.pins.length > 0) {
    out.push("", "", ...underlined("Pins to remove"), "", PINS_LEAD);
    for (const each of email.pins) {
      out.push("", `  * ${each.title}`, `    [${each.project}] ${each.location.text}${each.location.href ? `  ${each.location.href}` : ""}`);
      if (each.defect) out.push(`    ${each.defect}`);
    }
  }
  if (email.notRun.length > 0) {
    out.push("", "", ...underlined("What did not run"));
    for (const group of email.notRun) {
      out.push("", notRunLead(group));
      const width = Math.max(...group.projects.map((each) => each.name.length)) + 4;
      for (const each of group.projects) out.push(`  ${each.name.padEnd(width)}${plural(each.skipped, "test")}`);
    }
    if (!email.dependenciesKnown) out.push("", "Playwright skips a project when a project it depends on fails.");
  }
  out.push("", "--");
  if (email.absent.length > 0) out.push("Left out on this target:", ...email.absent.map((each) => `  - ${each.name}: ${each.reason}`), "");
  out.push(`Reproduce the run with \`npm run test:${email.target}\` in e2e/.`);
  out.push(`To rerun one case against a kept corpus, see "Reproducing one case" in the e2e README${email.readmeUrl ? `: ${email.readmeUrl}` : "."}`);
  out.push("", `Sent by ${WORKFLOW_FILE}`);
  return out.join("\n") + "\n";
}

/**
 * Writes both parts into `dir` and returns the subject.
 *
 * @param {string} dir
 * @param {Env} env
 * @returns {string}
 */
export function writeRunEmail(dir, env) {
  const report = readIfPresent(path.join(dir, "report.json"));
  const lastRun = readIfPresent(path.join(dir, ".last-run.json"));
  const stack = readIfPresent(path.join(dir, "stack.json"));
  const email = emailOf({ report, lastRun, stack, history: readHistory(env.E2E_HISTORY_DIR), env });
  // A run that failed before the suite started has no directory yet.
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, HTML_FILE), emailHtml(email));
  fs.writeFileSync(path.join(dir, TEXT_FILE), emailText(email));
  return emailSubject(email);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  console.log(`subject=${writeRunEmail(dir, process.env)}`);
}
