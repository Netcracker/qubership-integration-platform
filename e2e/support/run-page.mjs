/**
 * Writes `test-results/run-page.html`: the run on one self-contained page, from the run model
 * `run-model.mjs` reads and the `coverage.json` that `npm run reconcile` writes. The workflow
 * uploads it unzipped.
 *
 *     npm run run-page [-- <dir>]   # dir defaults to test-results/
 *
 * The page holds its CSS inline and loads nothing. A failed case's screenshot is embedded as a data
 * URI, up to `IMAGE_BYTES` for the whole page; the report zip keeps the rest.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GROUPS, GROUP_TITLES, clock, duration, escapeHtml, plural, readIfPresent, shortDate, shortTime, slowestTests, stripAnsi, totalOf } from "./run-report.mjs";
import { readHistory } from "./run-history.mjs";
import { byNovelty, countsClause, historyParts, pointLabel, runOf } from "./run-model.mjs";

export const PAGE_FILE = "run-page.html";
/** The screenshots one page embeds at most, in bytes before base64. */
export const IMAGE_BYTES = 10 * 1024 * 1024;
export const SLOWEST_LIMIT = 20;

/**
 * @typedef {import("./run-report.mjs").Report} Report
 * @typedef {import("./run-model.mjs").Run} Run
 * @typedef {import("./run-model.mjs").Link} Link
 * @typedef {import("./run-model.mjs").FailedCase} FailedCase
 * @typedef {import("./run-model.mjs").Image} Image
 * @typedef {import("./run-summary.mjs").Coverage} Coverage
 * @typedef {import("./run-summary.mjs").SummaryInput} PageInput
 */

const STYLE = `
:root { color-scheme: light dark; --bg: #f6f7f9; --card: #ffffff; --ink: #101828; --muted: #5d6676; --line: #e1e4ea; --link: #175cd3;
  --code: #f2f4f7; --fail: #b42318; --fail-tint: #fef3f2; --warn: #b54708; --warn-tint: #fffaeb; --pass: #067647; --pass-tint: #ecfdf3; --bar: #98a2b3; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #0f1218; --card: #171b23; --ink: #e6e9ef; --muted: #9aa3b2; --line: #2a303b; --link: #84adff;
    --code: #1f2430; --fail: #f97066; --fail-tint: #3a1d1b; --warn: #fdb022; --warn-tint: #3a2a12; --pass: #47cd89; --pass-tint: #133126; --bar: #667085; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 48px; }
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; }
h1 { font-size: 24px; line-height: 30px; margin: 0 0 6px; }
h2 { font-size: 17px; margin: 32px 0 10px; }
h3 { font-size: 14px; margin: 18px 0 8px; color: var(--muted); }
code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace; font-size: 12px; }
pre { background: var(--code); border-radius: 6px; padding: 10px 12px; margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
pre.error { background: var(--fail-tint); }
.head { background: var(--card); border: 1px solid var(--line); border-left: 6px solid var(--tone); border-radius: 8px; padding: 18px 20px; }
.tone-failed { --tone: var(--fail); } .tone-warning { --tone: var(--warn); } .tone-passed { --tone: var(--pass); }
.status { font-weight: 700; color: var(--tone); }
.verdict { font-size: 16px; margin: 4px 0 10px; }
.meta { color: var(--muted); }
.links { display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 14px 0 0; padding: 0; list-style: none; }
.groups { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin-top: 16px; }
.group, .case { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; }
.group b { display: block; }
.case { border-left: 4px solid var(--fail); margin-top: 10px; }
.case .label { font-size: 12px; font-weight: 700; color: var(--fail); }
.case .title { font-weight: 600; font-size: 15px; }
.case img { display: block; max-width: 100%; margin-top: 10px; border: 1px solid var(--line); border-radius: 4px; }
.note { color: var(--muted); font-size: 13px; }
table { width: 100%; border-collapse: collapse; background: var(--card); border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
th, td { text-align: left; padding: 5px 10px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { font-size: 12px; color: var(--muted); font-weight: 600; }
td.num, th.num { text-align: right; white-space: nowrap; }
.bar { display: inline-block; height: 8px; border-radius: 4px; background: var(--bar); vertical-align: middle; margin-right: 6px; }
.bar.fail { background: var(--fail); }
.pill { display: inline-block; border-radius: 999px; padding: 0 8px; font-size: 12px; font-weight: 600; }
.pill.failed { background: var(--fail-tint); color: var(--fail); } .pill.passed { background: var(--pass-tint); color: var(--pass); } .pill.warning { background: var(--warn-tint); color: var(--warn); }
.wide { overflow-x: auto; }
`;

/** @param {Link} link */
const anchor = (link) => (link.href ? `<a href="${escapeHtml(link.href)}">${escapeHtml(link.text)}</a>` : escapeHtml(link.text));

/** @param {string[]} lines */
const pre = (lines, className = "") => `<pre${className ? ` class="${className}"` : ""}>${escapeHtml(lines.join("\n"))}</pre>`;

/**
 * @param {string[]} head
 * @param {string[][]} rows
 * @param {number[]} [numeric] the columns aligned to the right
 */
function table(head, rows, numeric = []) {
  const th = head.map((each, i) => `<th${numeric.includes(i) ? ' class="num"' : ""}>${each}</th>`).join("");
  const body = rows.map((row) => `<tr>${row.map((each, i) => `<td${numeric.includes(i) ? ' class="num"' : ""}>${each}</td>`).join("")}</tr>`).join("");
  return `<div class="wide"><table><thead><tr>${th}</tr></thead><tbody>${body}</tbody></table></div>`;
}

/**
 * The screenshots of a failed case as `<img>` elements, while the page's budget lasts.
 *
 * @param {FailedCase} each
 * @param {{ left: number }} budget the bytes still free, shared by every case on the page
 */
function images(each, budget) {
  return each.images
    .map((image) => {
      const bytes = image.body ? Buffer.from(image.body, "base64") : image.path && fs.existsSync(image.path) ? fs.readFileSync(image.path) : undefined;
      if (!bytes) return "";
      if (bytes.length > budget.left) {
        return `<p class="note">Screenshot ${escapeHtml(image.name)} left out: the page embeds at most ${IMAGE_BYTES / 1024 ** 2} MiB of screenshots. The report zip holds it.</p>`;
      }
      budget.left -= bytes.length;
      return `<img alt="${escapeHtml(`${image.name} of ${each.title}`)}" src="data:${escapeHtml(image.contentType)};base64,${bytes.toString("base64")}">`;
    })
    .join("");
}

/** @type {Record<import("./run-model.mjs").Novelty, string>} */
const NOVELTY = { new: "New", recurring: "Still failing", intermittent: "Intermittent", unknown: "Failed" };

/**
 * @param {FailedCase} each
 * @param {{ left: number }} budget
 */
function caseCard(each, budget) {
  const parts = historyParts(each);
  const history = parts ? `<div>${escapeHtml(parts.before)}${anchor(parts.link)}${escapeHtml(parts.after)}</div>` : "";
  const error = each.error.lines.length > 0 ? [...each.error.lines, ...(each.error.cut ? ["…"] : [])] : ["(no error message)"];
  const failedAt = each.failedAt ? `, failed at <code>${anchor(each.failedAt)}</code>` : "";
  return (
    `<div class="case"><div class="label">${NOVELTY[each.novelty]}</div>` +
    `<div class="title">${escapeHtml(each.title)}</div>` +
    `<div class="meta"><code>${escapeHtml(each.project)}</code>, <code>${anchor(each.location)}</code>${failedAt}</div>` +
    history +
    pre(error, "error") +
    `<div class="note" style="margin-top:8px">Rerun against a kept corpus</div>` +
    pre([each.retry]) +
    images(each, budget) +
    `</div>`
  );
}

/** @param {Run} run */
function header(run) {
  const ref = [run.branch && `<code>${escapeHtml(run.branch)}</code>`, run.commit && `<code>${anchor(run.commit)}</code>`].filter(Boolean).join(" @ ");
  const when = [run.trigger, run.started && `started ${run.started}`, run.duration && `took ${run.duration}`, `on the ${run.target} target`].filter(Boolean).join(", ");
  const links = [
    run.runUrl && { text: "The run", href: run.runUrl },
    run.reportHtmlUrl && { text: "HTML report", href: run.reportHtmlUrl },
    run.reportZipUrl && { text: "Report with traces", href: run.reportZipUrl },
    run.artifactsUrl && { text: "Run artifacts", href: run.artifactsUrl },
    run.clusterUrl && { text: "Cluster dump", href: run.clusterUrl },
  ].filter((each) => typeof each === "object");
  return (
    `<header class="head tone-${run.tone}">` +
    (run.repo ? `<div class="meta">${escapeHtml(run.repo)}</div>` : "") +
    `<h1>${escapeHtml(run.title)}</h1>` +
    `<div class="verdict">${escapeHtml(run.verdict)}${run.trend ? ` ${escapeHtml(run.trend)}` : ""}</div>` +
    (ref ? `<div>${ref}${run.commitSubject ? ` ${escapeHtml(run.commitSubject)}` : ""}</div>` : "") +
    `<div class="meta">${escapeHtml(when)}.</div>` +
    (links.length > 0 ? `<ul class="links">${links.map((each) => `<li>${anchor(each)}</li>`).join("")}</ul>` : "") +
    `</header>`
  );
}

/** @param {Run} run */
function groupsSection(run) {
  const groups = run.groups;
  if (!groups) return "";
  const cards = GROUPS.filter((group) => group === "product" || totalOf(groups[group]) > 0).map(
    (group) => `<div class="group"><b>${GROUP_TITLES[group]}</b>${plural(totalOf(groups[group]), "case")}: ${escapeHtml(countsClause(groups[group]))}</div>`,
  );
  return `<div class="groups">${cards.join("")}</div>`;
}

/**
 * @param {Run} run
 * @param {{ left: number }} budget
 */
function failuresSection(run, budget) {
  const out = [];
  if (run.errors.length > 0) out.push(`<h2>Errors outside any test</h2>${run.errors.map((each) => pre([...each.lines, ...(each.cut ? ["…"] : [])], "error")).join("")}`);
  if (run.failed.length > 0) {
    out.push(`<h2>Failures</h2>`);
    for (const group of byNovelty(run.failed)) out.push(`<h3>${group.title} (${group.cases.length})</h3>${group.cases.map((each) => caseCard(each, budget)).join("")}`);
  }
  if (run.fixed.length > 0) {
    const rows = run.fixed.map((each) => [escapeHtml(each.title), `<code>${escapeHtml(each.project)}</code>`, `<code>${escapeHtml(each.file)}</code>`]);
    out.push(`<h2>Fixed since the last run (${run.fixed.length})</h2>${table(["Test", "Project", "File"], rows)}`);
  }
  if (run.pins.length > 0) {
    const rows = run.pins.map((each) => [escapeHtml(each.title), `<code>${escapeHtml(each.project)}</code>`, `<code>${anchor(each.location)}</code>`, escapeHtml(each.defect ?? "")]);
    out.push(
      `<h2>Pins to remove</h2><p>Each <code>test.fail()</code> case below passed, so the defect it pins no longer reproduces. Remove the annotation and close the defect's issue.</p>` +
        table(["Test", "Project", "Location", "Defect"], rows),
    );
  }
  if (run.notRun.length > 0) {
    const rows = run.notRun.flatMap((group) =>
      group.projects.map((each) => [`<code>${escapeHtml(each.name)}</code>`, String(each.skipped), group.cause.length > 0 ? group.cause.map((name) => `<code>${escapeHtml(name)}</code>`).join(", ") : ""]),
    );
    out.push(`<h2>What did not run</h2>${table(["Project", "Tests", "Failed dependency"], rows, [1])}`);
  }
  return out.join("");
}

/**
 * The trend as a table: each run's result, its failed product cases, and its duration, each
 * measure on its own scale.
 *
 * @param {Run} run
 */
function trendSection(run) {
  if (run.points.length < 2) return "";
  const most = Math.max(1, ...run.points.map((each) => each.failed));
  const longest = Math.max(1, ...run.points.map((each) => each.durationMs ?? 0));
  const rows = [...run.points].reverse().map((each) => {
    const tone = each.status === "passed" && each.failed === 0 ? "passed" : each.status === "timedout" ? "warning" : "failed";
    const when = `${shortDate(each.startedAt)} ${shortTime(each.startedAt)}${each.current ? " (this run)" : ""}`;
    return [
      each.url ? anchor({ text: when, href: each.url }) : escapeHtml(when),
      `<span class="pill ${tone}">${escapeHtml(pointLabel(each))}</span>`,
      `<span class="bar fail" style="width:${Math.round((each.failed / most) * 80)}px"></span>${each.failed}`,
      each.durationMs === undefined ? "" : `<span class="bar" style="width:${Math.round((each.durationMs / longest) * 80)}px"></span>${duration(each.durationMs)}`,
    ];
  });
  return `<h2>The last ${run.points.length} runs</h2>${table(["Run (UTC)", "Result", "Failed product cases", "Duration"], rows)}`;
}

/**
 * @param {Run} run
 * @param {Report} report
 */
function projectsSection(run, report) {
  const start = report.stats.startTime ? Date.parse(report.stats.startTime) : undefined;
  const out = [`<h2>Projects</h2>`];
  for (const group of GROUPS) {
    const projects = run.projects.filter((each) => each.group === group && each.tests > 0);
    if (projects.length === 0) continue;
    const rows = projects.map((each) => {
      const timed = each.first !== undefined && each.last !== undefined;
      return [
        `<code>${escapeHtml(each.name)}</code>`,
        String(each.tests),
        String(each.failed),
        String(each.skipped),
        timed && start !== undefined ? `+${clock(/** @type {number} */ (each.first) - start)}` : "",
        timed ? clock(/** @type {number} */ (each.last) - /** @type {number} */ (each.first)) : "",
      ];
    });
    out.push(`<h3>${GROUP_TITLES[group]}</h3>${table(["Project", "Tests", "Failed", "Skipped", "Started", "Took"], rows, [1, 2, 3, 4, 5])}`);
  }
  return out.join("");
}

/** @param {Coverage} coverage */
function coverageSection(coverage) {
  const { elements, operations } = coverage;
  const rows = [
    ["Element registry rows in scope", `${elements.covered} of ${elements.inScope} covered`],
    ["Element rows out of scope on this target", String(elements.outOfScope)],
    ["Element rows shared", String(elements.shared)],
    ["API operations covered", `${operations.covered} of ${operations.total}`],
    ["API operations reached", `${operations.reached} of ${operations.total}`],
    ["Reconciliation problems", String(coverage.problems)],
  ];
  return `<h2>Coverage on ${escapeHtml(coverage.target)}</h2>${table(["Measure", "Value"], rows, [1])}`;
}

/** @param {Report} report */
function slowestSection(report) {
  const rows = slowestTests(report, SLOWEST_LIMIT).map((each) => [clock(each.ms), `<code>${escapeHtml(each.project)}</code>`, `<code>${escapeHtml(each.location)}</code>`, escapeHtml(each.title)]);
  return rows.length > 0 ? `<h2>Slowest ${rows.length} tests</h2>${table(["Took", "Project", "Location", "Test"], rows, [0])}` : "";
}

/**
 * @param {PageInput} input
 * @returns {string}
 */
export function runPageHtml({ report, lastRun, stack, coverage, history = [], env = {} }) {
  const run = runOf({ report, lastRun, stack, history, env });
  const budget = { left: IMAGE_BYTES };
  const title = [run.title, run.branch && run.commit ? `${run.branch} @ ${run.commit.text}` : undefined].filter(Boolean).join(", ");
  const absent = run.absent.length > 0 ? `<h2>Left out on this target</h2>${table(["Project", "Reason"], run.absent.map((each) => [`<code>${escapeHtml(each.name)}</code>`, escapeHtml(each.reason)]))}` : "";
  return [
    "<!doctype html>",
    '<html lang="en">',
    "<head>",
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<meta name="color-scheme" content="light dark">',
    `<title>${escapeHtml(stripAnsi(title))}</title>`,
    `<style>${STYLE}</style>`,
    "</head>",
    "<body><main>",
    header(run),
    groupsSection(run),
    failuresSection(run, budget),
    trendSection(run),
    report ? projectsSection(run, report) : "",
    coverage ? coverageSection(coverage) : "",
    report ? slowestSection(report) : "",
    absent,
    `<p class="note" style="margin-top:32px">Written by <code>e2e/support/run-page.mjs</code>. Rerun one case as the e2e README describes under ${run.readmeUrl ? anchor({ text: "Reproducing one case", href: run.readmeUrl }) : "Reproducing one case"}.</p>`,
    "</main></body>",
    "</html>",
    "",
  ].join("\n");
}

/**
 * Writes the page into `dir`.
 *
 * @param {string} dir
 * @param {Record<string, string | undefined>} env
 */
export function writeRunPage(dir, env = {}) {
  const read = (/** @type {string} */ file) => readIfPresent(path.join(dir, file));
  const html = runPageHtml({
    report: read("report.json"),
    lastRun: read(".last-run.json"),
    stack: read("stack.json"),
    coverage: read("coverage.json"),
    history: readHistory(env.E2E_HISTORY_DIR),
    env,
  });
  fs.writeFileSync(path.join(dir, PAGE_FILE), html);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  writeRunPage(dir, process.env);
}
