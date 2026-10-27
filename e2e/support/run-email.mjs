/**
 * Writes the run email from the run model `run-model.mjs` reads: the HTML part to `email.html`,
 * the plain-text part to `email.txt`, and the subject to stdout as one `subject=<text>` line in the
 * `$GITHUB_OUTPUT` format. A missing run file leaves its sections out.
 *
 *     node support/run-email.mjs [<dir>] >> "$GITHUB_OUTPUT"   # dir defaults to test-results/
 *
 * The subject opens with the verdict: a failed setup project, the failed tests by how long they
 * have been failing, or else the first error raised outside any test, or else the step
 * `FAILED_STEP` names. A `test.fail()` case that passed is listed apart, as a pin to remove.
 *
 * The HTML is laid out with tables and inline styles only, because Gmail drops a `<style>` block in
 * many of its clients and Outlook renders with Word. Every background is set twice, as a `bgcolor`
 * attribute and as a style, and every status is written as a word beside its color.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GROUPS, GROUP_TITLES, clock, escapeHtml, joined, plural, readIfPresent, shortDate, shortList, totalOf, duration } from "./run-report.mjs";
import { readHistory } from "./run-history.mjs";
import { COVERAGE_FILE } from "./run-summary.mjs";
import { countsClause, historyParts, pointLabel, runOf } from "./run-model.mjs";

export const HTML_FILE = "email.html";
export const TEXT_FILE = "email.txt";
export const CARDS_LIMIT = 20;
/** Gmail clips a message over 102 KB behind a link, so the HTML part shows fewer cards past this size. */
export const HTML_BYTES = 100 * 1024;
/** The inbox shows about 90 characters of the preview line. */
export const PREHEADER_CHARS = 89;
const WORKFLOW_FILE = ".github/workflows/e2e-k8s.yaml";

/**
 * @typedef {import("./run-model.mjs").Run} Run
 * @typedef {import("./run-model.mjs").Link} Link
 * @typedef {import("./run-model.mjs").FailedCase} FailedCase
 * @typedef {import("./run-model.mjs").FixedPin} FixedPin
 * @typedef {import("./run-model.mjs").TrendPoint} TrendPoint
 * @typedef {import("./run-report.mjs").Counts} Counts
 * @typedef {import("./run-report.mjs").Excerpt} Excerpt
 * @typedef {import("./run-report.mjs").NotRun} NotRun
 * @typedef {Record<string, string | undefined>} Env
 */

/**
 * The failed tests by how long they have been failing, and where. Without an earlier run, or when
 * every one also failed the run before, the count alone.
 *
 * @param {Run} run
 */
function headline(run) {
  const where = shortList(run.failedProjects);
  const count = (/** @type {string} */ novelty) => run.failed.filter((each) => each.novelty === novelty).length;
  const [fresh, intermittent, still] = [count("new"), count("intermittent"), count("recurring")];
  if (!run.previous || still === run.failed.length) return `${plural(run.failed.length, "test")} in ${where}`;
  const parts = [fresh > 0 && `${fresh} new`, intermittent > 0 && `${intermittent} intermittent`, still > 0 && `${still} still failing`].filter(Boolean);
  return `${parts.join(", ")} in ${where}`;
}

/**
 * One line, because it travels as a step output.
 *
 * @param {Run} run
 */
export function emailSubject(run) {
  const ref = run.branch && run.commit ? ` (${run.branch} @ ${run.commit.text})` : "";
  const { counts } = run;
  const pins = counts && counts.pins > 0 ? `${plural(counts.pins, "pin")} to remove` : "";
  const skipped = counts && counts.skipped > 0 ? `${counts.skipped} skipped` : "";
  let detail = "";
  if (run.infra) {
    const kept = run.infra.didNotRun > 0 ? `${plural(run.infra.didNotRun, "case")} did not run` : "";
    detail = `: ${[`${joined(run.infra.projects)} failed in setup`, kept, pins].filter(Boolean).join(", ")}`;
  } else if (run.failed.length > 0) {
    detail = `: ${[headline(run), skipped, pins].filter(Boolean).join(", ")}`;
  } else if (pins && run.errors.length === 0) {
    detail = `: ${pins}`;
  } else if (run.errors[0]) {
    const line = run.errors[0].lines[0] ?? "";
    detail = `: ${line.length > 100 ? `${line.slice(0, 99)}…` : line}`;
  } else if (run.coverageProblems > 0) {
    detail = `: ${plural(run.coverageProblems, "coverage problem")}`;
  } else if (run.failedStep) {
    detail = ` at ${run.failedStep}`;
  }
  return `${run.title}${detail}${ref}`.replace(/\s+/g, " ");
}

/**
 * The inbox preview line: the verdict, with the trend when both fit, cut at a word when the verdict
 * alone does not.
 *
 * @param {Run} run
 */
export function preheader(run) {
  const verdict = /[.!?]$/.test(run.verdict) ? run.verdict : `${run.verdict}.`;
  const full = run.trend ? `${verdict} ${run.trend}` : verdict;
  if (full.length <= PREHEADER_CHARS) return full;
  if (verdict.length <= PREHEADER_CHARS) return verdict;
  const cut = verdict.slice(0, PREHEADER_CHARS - 1);
  return `${cut.slice(0, cut.lastIndexOf(" "))}…`;
}

const SANS = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";
const WIDTH = 600;

/** @type {Record<import("./run-model.mjs").Tone, string>} */
const BAND = { failed: "#b42318", warning: "#b54708", passed: "#067647" };
/** White at 90% over each band, written out because Outlook ignores `opacity`. */
const BAND_SOFT = { failed: "#f8e9e8", warning: "#f8ede6", passed: "#e6f1ed" };
const LINK = "#175cd3";
const MUTED = "#667085";
const TEXT = "#101828";
const RED_TINT = "#fef3f2";
const RED_INK = "#7a271a";
const NIGHT = "#101828";
const PAGE = "#f2f4f7";

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

/** @param {Run} run */
function tiles(run) {
  const counts = /** @type {Counts} */ (run.counts);
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
  const note = (/** @type {string} */ text) => `<div style="font-family:${SANS};font-size:12px;line-height:16px;text-align:center;color:${MUTED};">${text}</div>`;
  const groups = run.groups;
  const others = groups
    ? /** @type {["setup" | "suite", string][]} */ ([["setup", "Setup and teardown"], ["suite", "Suite checks"]])
        .filter(([group]) => totalOf(groups[group]) > 0)
        .map(([group, name]) => note(`${name}: ${countsClause(groups[group])}`))
        .join("")
    : "";
  return (
    `<tr><td style="padding:20px 25px 0;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>${tile.join("")}</tr></table>` +
    `<div style="padding-top:8px;text-align:center;font-family:${SANS};font-size:12px;line-height:16px;color:${MUTED};">${plural(totalOf(counts), "product case")}</div>` +
    others +
    `</td></tr>`
  );
}

/** @type {Record<import("./run-model.mjs").Novelty, string>} */
const NOVELTY = { new: "New", recurring: "Still failing", intermittent: "Intermittent", unknown: "Failed" };

/** @param {FailedCase} each */
function card(each) {
  const failedAt = each.failedAt ? `<br><span style="color:${MUTED};">failed at</span> ${anchor(each.failedAt)}` : "";
  const parts = historyParts(each);
  const color = each.novelty === "new" ? "#b42318" : each.novelty === "intermittent" ? "#b54708" : "#475467";
  const history = parts
    ? `<div style="padding-top:6px;font-size:13px;line-height:19px;font-weight:600;color:${color};">${escapeHtml(parts.before)}${anchor(parts.link)}${escapeHtml(parts.after)}</div>`
    : "";
  const error = each.error.lines.length > 0 ? [...each.error.lines, ...(each.error.cut ? ["…"] : [])] : ["(no error message)"];
  return (
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr><td style="padding-top:10px;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;table-layout:fixed;border:1px solid #e4e7ec;border-left:4px solid #d92d20;border-radius:6px;">` +
    `<tr><td style="padding:14px 16px;font-family:${SANS};">` +
    `<div style="font-size:12px;line-height:16px;font-weight:700;color:${color};">${NOVELTY[each.novelty]}</div>` +
    `<div style="padding-top:2px;font-size:15px;line-height:21px;font-weight:600;color:${TEXT};">${escapeHtml(each.title)}</div>` +
    `<div style="padding-top:4px;font-family:${MONO};font-size:12px;line-height:18px;word-break:break-word;overflow-wrap:anywhere;">${anchor(each.location)}${failedAt}</div>` +
    history +
    `<div style="padding-top:10px;">${codeBox(error, RED_TINT, RED_INK)}</div>` +
    `<div style="padding-top:12px;font-size:12px;line-height:16px;font-weight:600;color:#475467;">Retry locally</div>` +
    `<div style="padding-top:4px;">${codeBox([each.retry], NIGHT, "#f2f4f7")}</div>` +
    `</td></tr></table></td></tr></table>`
  );
}

/**
 * @param {Run} run
 * @param {number} limit the cards shown at most
 */
function failedSection(run, limit) {
  if (run.failed.length === 0) return "";
  const shown = run.failed.slice(0, limit);
  let body = "";
  for (const name of run.failedProjects) {
    const cases = shown.filter((each) => each.project === name);
    if (cases.length === 0) continue;
    const total = run.failed.filter((each) => each.project === name).length;
    body +=
      `<div style="padding-top:14px;font-family:${SANS};font-size:13px;line-height:18px;color:${MUTED};">${badge(name)}&nbsp; ${total} failed</div>` +
      cases.map(card).join("");
  }
  const more = run.failed.length - shown.length;
  if (more > 0) {
    const report = run.runPageUrl ? { text: "the run page", href: run.runPageUrl } : { text: "the HTML report", href: run.reportHtmlUrl ?? run.artifactsUrl };
    body += `<div style="padding-top:14px;font-family:${SANS};font-size:14px;line-height:20px;color:#344054;">And ${more} more, listed in ${anchor(report)}.</div>`;
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

/** @param {Run} run */
function pinsSection(run) {
  if (run.pins.length === 0) return "";
  return (
    sectionTitle("Pins to remove") +
    `<tr><td style="padding:4px 28px 0;font-family:${SANS};font-size:14px;line-height:20px;color:#344054;">${escapeHtml(PINS_LEAD)}${run.pins.map(pinCard).join("")}</td></tr>`
  );
}

/** @param {Run} run */
function errorsSection(run) {
  if (run.errors.length === 0) return "";
  const boxes = run.errors
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

/** @param {Run} run */
function notRunSection(run) {
  if (run.notRun.length === 0) return "";
  const groups = run.notRun.map((group) => {
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
  const hint = run.dependenciesKnown
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

/**
 * How a trend point ended, as the tint and the ink of its cell.
 *
 * @param {TrendPoint} point
 * @returns {[string, string]}
 */
function pointColors(point) {
  if (point.status === "passed" && point.failed === 0) return ["#ecfdf3", "#067647"];
  if (point.status === "timedout") return ["#fffaeb", "#b54708"];
  return ["#fef3f2", "#b42318"];
}

/**
 * The runs the trend holds as a strip of table cells, the oldest first. Each cell names how its run
 * ended, so the strip reads without its colors.
 *
 * @param {Run} run
 */
function trendStrip(run) {
  if (run.points.length < 2) return "";
  const width = Math.floor(100 / run.points.length);
  const cells = run.points.map((point) => {
    const [bg, fg] = pointColors(point);
    const label = escapeHtml(pointLabel(point));
    const when = `${shortDate(point.startedAt)}${point.durationMs === undefined ? "" : `<br>${duration(point.durationMs)}`}`;
    const name = point.url ? `<a href="${escapeHtml(point.url)}" style="color:${fg};text-decoration:none;">${label}</a>` : label;
    const border = point.current ? `border:2px solid ${fg};` : `border:2px solid ${bg};`;
    return (
      `<td width="${width}%" valign="top" style="padding:0 2px;">` +
      `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr>` +
      `<td align="center" bgcolor="${bg}" style="background-color:${bg};${border}border-radius:4px;padding:6px 1px;font-family:${SANS};">` +
      `<div style="font-size:11px;line-height:14px;font-weight:700;color:${fg};">${name}</div>` +
      `<div style="font-size:10px;line-height:13px;color:${MUTED};">${when}</div>` +
      `</td></tr></table></td>`
    );
  });
  return (
    sectionTitle(`The last ${run.points.length} runs`) +
    `<tr><td style="padding:6px 26px 0;">` +
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="table-layout:fixed;"><tr>${cells.join("")}</tr></table>` +
    `<div style="padding-top:6px;font-family:${SANS};font-size:12px;line-height:16px;color:${MUTED};">The oldest first; the outlined cell is this run. A failed count counts product cases.</div>` +
    `</td></tr>`
  );
}

/**
 * On a green run, every project with its tests and how long it took, one table per group.
 *
 * @param {Run} run
 */
function projectsSection(run) {
  if (run.tone !== "passed" || run.projects.length === 0) return "";
  const cell = `font-family:${SANS};font-size:12px;line-height:17px;padding:2px 0;`;
  const rows = GROUPS.flatMap((group) => {
    const projects = run.projects.filter((each) => each.group === group && each.tests > 0);
    if (projects.length === 0) return [];
    return [
      `<tr><td colspan="3" style="${cell}padding-top:10px;font-weight:700;color:#475467;">${GROUP_TITLES[group]}</td></tr>`,
      ...projects.map(
        (each) =>
          `<tr><td style="${cell}font-family:${MONO};color:${TEXT};">${projectHtml(each.name)}</td>` +
          `<td align="right" style="${cell}color:${MUTED};white-space:nowrap;">${plural(each.tests, "test")}</td>` +
          `<td align="right" width="60" style="${cell}color:${MUTED};white-space:nowrap;">${each.first === undefined || each.last === undefined ? "" : clock(each.last - each.first)}</td></tr>`,
      ),
    ];
  });
  return (
    sectionTitle("Projects") +
    `<tr><td style="padding:0 28px;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">${rows.join("")}</table></td></tr>`
  );
}

/** @param {string} text */
const inlineCode = (text) =>
  `<span style="font-family:${MONO};font-size:12px;background-color:#f2f4f7;border-radius:4px;padding:1px 5px;color:${TEXT};">${escapeHtml(text)}</span>`;

/** @param {Run} run */
function footer(run) {
  const absent =
    run.absent.length === 0
      ? ""
      : `<div style="padding-bottom:14px;font-size:12px;line-height:18px;color:${MUTED};">` +
        `<strong style="color:#475467;">Left out on this target:</strong> ` +
        run.absent.map((each) => `${inlineCode(each.name)} ${escapeHtml(each.reason)}`).join("; ") +
        `</div>`;
  const readme = run.readmeUrl ? anchor({ text: "Reproducing one case", href: run.readmeUrl }) : "Reproducing one case";
  return (
    `<tr><td style="padding:28px 28px 0;"><div style="border-top:1px solid #e4e7ec;font-size:0;line-height:0;">&nbsp;</div></td></tr>` +
    `<tr><td style="padding:16px 28px 24px;font-family:${SANS};">` +
    absent +
    `<div style="font-size:13px;line-height:20px;color:#475467;">Reproduce the run with ${inlineCode(`npm run test:${run.target}`)} in ${inlineCode("e2e/")}. ` +
    `To rerun one case against a kept corpus, see ${readme} in the e2e README.</div>` +
    `<div style="padding-top:10px;font-size:12px;line-height:18px;color:${MUTED};">Sent by ${escapeHtml(WORKFLOW_FILE)}</div>` +
    `</td></tr>`
  );
}

/**
 * The verdict, with each project it names written the way the rest of the HTML writes a project.
 *
 * @param {Run} run
 */
function verdictHtml(run) {
  const names = [
    ...new Set([...run.failedProjects, ...(run.infra?.projects ?? []), ...run.notRun.flatMap((group) => [...group.cause, ...group.projects.map((each) => each.name)])]),
  ]
    .filter((name) => name.includes("-"))
    .sort((a, b) => b.length - a.length)
    .map((name) => escapeHtml(name).replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  const html = escapeHtml(run.verdict);
  if (names.length === 0) return html;
  return html.replace(new RegExp(`(?<![\\w-])(?:${names.join("|")})(?![\\w-])`, "g"), (name) => name.replace(/-/g, "&#8209;"));
}

/**
 * When and where the run ran, as two short lines.
 *
 * @param {Run} run
 * @returns {string[]}
 */
function when(run) {
  const where = `on the ${run.target} target`;
  return [
    run.started ? `${run.trigger}, started ${run.started}` : run.trigger,
    run.duration ? `${run.duration} ${where}` : `${where[0].toUpperCase()}${where.slice(1)}`,
  ];
}

/** @param {Run} run */
function header(run) {
  const band = BAND[run.tone];
  const white = "color:#ffffff;";
  const soft = `color:${BAND_SOFT[run.tone]};`;
  const commit = run.commit
    ? run.commit.href
      ? `<a href="${escapeHtml(run.commit.href)}" style="${white}text-decoration:underline;font-family:${MONO};">${escapeHtml(run.commit.text)}</a>`
      : `<span style="font-family:${MONO};">${escapeHtml(run.commit.text)}</span>`
    : "";
  const ref = [run.branch && `<span style="font-family:${MONO};">${escapeHtml(run.branch)}</span>`, commit].filter(Boolean).join(" @ ");
  return (
    `<tr><td bgcolor="${band}" style="background-color:${band};padding:24px 28px;border-radius:8px 8px 0 0;font-family:${SANS};">` +
    (run.repo ? `<div style="font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.04em;${soft}">${escapeHtml(run.repo)}</div>` : "") +
    `<div style="padding-top:6px;font-size:24px;line-height:30px;font-weight:700;${white}">${escapeHtml(run.title)}</div>` +
    `<div style="padding-top:6px;font-size:17px;line-height:24px;font-weight:normal;${white}">${verdictHtml(run)}${run.trend ? ` ${escapeHtml(run.trend)}` : ""}</div>` +
    (ref ? `<div style="padding-top:14px;font-size:14px;line-height:20px;font-weight:600;${white}">${ref}</div>` : "") +
    (run.commitSubject ? `<div style="padding-top:2px;font-size:14px;line-height:20px;${white}">${escapeHtml(run.commitSubject)}</div>` : "") +
    `<div style="padding-top:8px;font-size:13px;line-height:18px;${soft}">${when(run).map(escapeHtml).join("<br>")}</div>` +
    `</td></tr>`
  );
}

/**
 * The links under the button, in the order the HTML and the text parts give them.
 *
 * @param {Run} run
 * @returns {Link[]}
 */
function links(run) {
  return [
    run.runPageUrl ? { text: "Run page", href: run.runPageUrl } : undefined,
    run.reportHtmlUrl ? { text: "HTML report", href: run.reportHtmlUrl } : undefined,
    run.reportZipUrl ? { text: "Report with traces", href: run.reportZipUrl } : undefined,
    run.artifactsUrl ? { text: "Run artifacts", href: run.artifactsUrl } : undefined,
    run.clusterUrl ? { text: "Cluster dump", href: run.clusterUrl } : undefined,
    run.commit?.href ? { text: `Commit ${run.commit.text}`, href: run.commit.href } : undefined,
  ].filter((each) => each !== undefined);
}

/** @param {Run} run */
function actions(run) {
  if (!run.runUrl) return "";
  // mso-padding-alt gives Outlook, which ignores an anchor's padding, the same button.
  return (
    `<tr><td style="padding:22px 28px 0;font-family:${SANS};">` +
    `<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;"><tr>` +
    `<td bgcolor="${LINK}" style="background-color:${LINK};border-radius:6px;mso-padding-alt:11px 22px;">` +
    `<a href="${escapeHtml(run.runUrl)}" style="display:inline-block;padding:11px 22px;font-family:${SANS};font-size:15px;line-height:20px;font-weight:600;color:#ffffff;text-decoration:none;">Open the run</a>` +
    `</td></tr></table>` +
    `<div style="padding-top:12px;font-size:14px;line-height:20px;color:${MUTED};">${links(run)
      .map((each) => anchor(each))
      .join("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; ")}</div>` +
    `</td></tr>`
  );
}

/**
 * The HTML part, with as many cards as fit in `HTML_BYTES`, up to `CARDS_LIMIT`.
 *
 * @param {Run} run
 * @returns {string}
 */
export function emailHtml(run) {
  let html = "";
  for (let cards = CARDS_LIMIT; cards >= 0; cards--) {
    html = render(run, cards);
    if (Buffer.byteLength(html) <= HTML_BYTES) break;
  }
  return html;
}

/**
 * @param {Run} run
 * @param {number} cards
 */
function render(run, cards) {
  // Filler after the preview line keeps the inbox from appending the body's first words.
  const filler = "&#8199;&#65279;&#847; ".repeat(100);
  return [
    `<!doctype html>`,
    `<html lang="en">`,
    `<head>`,
    `<meta charset="utf-8">`,
    `<meta name="viewport" content="width=device-width, initial-scale=1">`,
    `<meta name="color-scheme" content="light">`,
    `<meta name="supported-color-schemes" content="light">`,
    `<title>${escapeHtml(emailSubject(run))}</title>`,
    `</head>`,
    `<body bgcolor="${PAGE}" style="margin:0;padding:0;background-color:${PAGE};">`,
    `<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:${PAGE};opacity:0;">${escapeHtml(preheader(run))}${filler}</div>`,
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="${PAGE}" style="background-color:${PAGE};">`,
    `<tr><td align="center" style="padding:24px 12px;">`,
    `<!--[if mso]><table role="presentation" width="${WIDTH}" align="center" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->`,
    `<table role="presentation" align="center" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#ffffff" style="max-width:${WIDTH}px;margin:0 auto;background-color:#ffffff;border:1px solid #e4e7ec;border-radius:8px;border-collapse:separate;">`,
    header(run),
    run.counts ? tiles(run) : "",
    actions(run),
    trendStrip(run),
    errorsSection(run),
    failedSection(run, cards),
    pinsSection(run),
    notRunSection(run),
    projectsSection(run),
    footer(run),
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
 * @param {Run} run
 * @returns {string}
 */
export function emailText(run) {
  const out = [run.trend ? `${run.verdict} ${run.trend}` : run.verdict, ""];
  if (run.repo) out.push(run.repo);
  /** @type {[string, string | undefined][]} */
  const facts = [
    ["Branch", run.branch],
    ["Commit", run.commit && [run.commit.text, run.commitSubject].filter(Boolean).join(" ")],
    ["Trigger", run.trigger],
    ["Started", run.started],
    ["Duration", run.duration],
    ["Target", run.target],
  ];
  for (const [label, value] of facts) if (value) out.push(`${label.padEnd(10)}${value}`);
  if (run.counts && run.groups) {
    const c = run.counts;
    out.push("", `${c.passed} passed, ${c.failed} failed, ${c.skipped} skipped${c.pins > 0 ? `, ${plural(c.pins, "pin")} to remove` : ""} (${plural(totalOf(c), "product case")})`);
    if (totalOf(run.groups.setup) > 0) out.push(`Setup and teardown: ${countsClause(run.groups.setup)}`);
    if (totalOf(run.groups.suite) > 0) out.push(`Suite checks: ${countsClause(run.groups.suite)}`);
  }
  if (run.runUrl) {
    out.push("", `Open the run: ${run.runUrl}`);
    for (const each of links(run)) out.push(`${each.text}: ${each.href}`);
  }
  if (run.points.length >= 2) {
    out.push("", `The last ${run.points.length} runs, the oldest first: ${run.points.map((each) => `${shortDate(each.startedAt)}: ${pointLabel(each)}${each.current ? " (this run)" : ""}`).join("; ")}`);
  }
  /** @param {Excerpt} each */
  const indented = (each) => [...each.lines, ...(each.cut ? ["…"] : [])].map((line) => `      ${line}`);
  if (run.errors.length > 0) {
    out.push("", "", ...underlined("Errors outside any test"));
    for (const each of run.errors) out.push("", ...indented(each).map((line) => line.slice(4)));
  }
  if (run.failed.length > 0) {
    out.push("", "", ...underlined("What failed"));
    const shown = run.failed.slice(0, CARDS_LIMIT);
    for (const name of run.failedProjects) {
      const cases = shown.filter((each) => each.project === name);
      if (cases.length === 0) continue;
      out.push("", `[${name}] ${run.failed.filter((each) => each.project === name).length} failed`);
      for (const each of cases) {
        out.push("", `  * ${each.title}`, `    ${each.location.text}${each.location.href ? `  ${each.location.href}` : ""}`);
        if (each.failedAt) out.push(`    failed at ${each.failedAt.text}`);
        const parts = historyParts(each);
        if (parts) out.push(`    ${parts.before}${parts.link.text}${parts.after}${parts.link.href ? `  ${parts.link.href}` : ""}`);
        out.push(...indented(each.error), "    Retry locally:", `      ${each.retry}`);
      }
    }
    const more = run.failed.length - shown.length;
    const where = run.runPageUrl ?? run.reportHtmlUrl ?? run.artifactsUrl;
    if (more > 0) out.push("", `And ${more} more, listed in the ${run.runPageUrl ? "run page" : "HTML report"}${where ? `: ${where}` : " of the run."}`);
  }
  if (run.pins.length > 0) {
    out.push("", "", ...underlined("Pins to remove"), "", PINS_LEAD);
    for (const each of run.pins) {
      out.push("", `  * ${each.title}`, `    [${each.project}] ${each.location.text}${each.location.href ? `  ${each.location.href}` : ""}`);
      if (each.defect) out.push(`    ${each.defect}`);
    }
  }
  if (run.notRun.length > 0) {
    out.push("", "", ...underlined("What did not run"));
    for (const group of run.notRun) {
      out.push("", notRunLead(group));
      const width = Math.max(...group.projects.map((each) => each.name.length)) + 4;
      for (const each of group.projects) out.push(`  ${each.name.padEnd(width)}${plural(each.skipped, "test")}`);
    }
    if (!run.dependenciesKnown) out.push("", "Playwright skips a project when a project it depends on fails.");
  }
  out.push("", "--");
  if (run.absent.length > 0) out.push("Left out on this target:", ...run.absent.map((each) => `  - ${each.name}: ${each.reason}`), "");
  out.push(`Reproduce the run with \`npm run test:${run.target}\` in e2e/.`);
  out.push(`To rerun one case against a kept corpus, see "Reproducing one case" in the e2e README${run.readmeUrl ? `: ${run.readmeUrl}` : "."}`);
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
  const coverage = readIfPresent(path.join(dir, COVERAGE_FILE));
  const run = runOf({ report, lastRun, stack, coverage, history: readHistory(env.E2E_HISTORY_DIR), env });
  // A run that failed before the suite started has no directory yet.
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, HTML_FILE), emailHtml(run));
  fs.writeFileSync(path.join(dir, TEXT_FILE), emailText(run));
  return emailSubject(run);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../test-results");
  console.log(`subject=${writeRunEmail(dir, process.env)}`);
}
