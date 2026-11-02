/**
 * The run page the workflow uploads unzipped, rendered from the red run the run-summary fixtures were
 * trimmed from and from reports built here.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { IMAGE_BYTES, PAGE_FILE, runPageHtml } from "../../support/run-page.mjs";
import { testKey } from "../../support/run-history.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RED_RUN = path.resolve(HERE, "../../fixtures/run-summary/red");
const SCRIPT = path.resolve(HERE, "../../support/run-page.mjs");
const RUNS = "https://github.com/Netcracker/qubership-integration-platform/actions/runs";
const ENV = { GITHUB_REPOSITORY: "Netcracker/qubership-integration-platform", GITHUB_SHA: "7a393a86505c8e12d9b29a7161e70b061ad4a968", GITHUB_RUN_ID: "9", GITHUB_REF_NAME: "main" };

const read = (file: string) => JSON.parse(fs.readFileSync(path.join(RED_RUN, file), "utf8"));

/** The red run's report, with `attachments` on the result of its one failed test. */
function redWith(attachments: { name: string; contentType: string; body?: string; path?: string }[] = []) {
  const report = read("report.json");
  const admin = report.suites.find((suite: { file: string }) => suite.file === "ui/admin-tools.spec.ts");
  const failing = admin.specs.find((spec: { title: string }) => spec.title.startsWith("a live exchange"));
  failing.tests[0].results[0].attachments = attachments;
  return report;
}

test.describe("run page", () => {
  test("the script writes one page that loads nothing", () => {
    const dir = test.info().outputPath("red");
    fs.mkdirSync(dir, { recursive: true });
    for (const each of ["report.json", ".last-run.json", "stack.json"]) fs.copyFileSync(path.join(RED_RUN, each), path.join(dir, each));

    expect(execFileSync(process.execPath, [SCRIPT, dir], { encoding: "utf8", env: { PATH: process.env.PATH, ...ENV } })).toBe("");
    const html = fs.readFileSync(path.join(dir, PAGE_FILE), "utf8");
    expect(html).toContain("<title>E2E run failed, main @ 7a393a8</title>");
    expect(html).not.toMatch(/<script|<link |src="http|@import|url\(/);
    expect(html).toContain("@media (prefers-color-scheme: dark)");
  });

  test("shows each failure, its error, history, and rerun command", () => {
    const key = testKey("ui", "ui/admin-tools.spec.ts", "a live exchange is killed only after its termination is confirmed");
    const history = [{ runId: "8", url: `${RUNS}/8`, startedAt: "2026-10-12T02:00:00Z", failed: [key], skipped: [] }];
    const html = runPageHtml({ report: redWith(), lastRun: read(".last-run.json"), history, env: ENV });

    expect(html).toContain("<h3>Still failing (1)</h3>");
    expect(html).toContain('<div class="label">Still failing</div><div class="title">a live exchange is killed only after its termination is confirmed</div>');
    expect(html).toContain(`Failing for 2 runs, since <a href="${RUNS}/8">Oct 12</a>`);
    expect(html).toContain('<pre class="error">Error: expect(received).toBe(expected) // Object.is equality');
    expect(html).toContain("<pre>npm run test:k8s -- --project=ui --no-deps specs/ui/admin-tools.spec.ts:126 -g &quot;a live exchange");
  });

  test("embeds the screenshots up to its byte budget", () => {
    const dir = test.info().outputPath("shots");
    fs.mkdirSync(dir, { recursive: true });
    const onDisk = path.join(dir, "screenshot.png");
    fs.writeFileSync(onDisk, Buffer.from("on disk"));
    const body = Buffer.from("in the report").toString("base64");
    const html = runPageHtml({ report: redWith([{ name: "screenshot", contentType: "image/png", path: onDisk }, { name: "diff", contentType: "image/png", body }, { name: "trace", contentType: "application/zip", path: onDisk }]) });

    expect(html).toContain(`src="data:image/png;base64,${Buffer.from("on disk").toString("base64")}"`);
    expect(html).toContain(`src="data:image/png;base64,${body}"`);
    expect(html.match(/<img /g)).toHaveLength(2);

    const half = Buffer.alloc(IMAGE_BYTES / 2 + 1).toString("base64");
    const capped = runPageHtml({ report: redWith([{ name: "first", contentType: "image/png", body: half }, { name: "second", contentType: "image/png", body: half }]) });
    expect(capped.match(/<img /g)).toHaveLength(1);
    expect(capped).toContain("Screenshot second left out: the page embeds at most 10 MiB of screenshots. The report zip holds it.");
  });

  test("shows the trend, the project groups, and the coverage", () => {
    const report = redWith();
    report.config.projects = [{ name: "ui", metadata: {} }, { name: "runtime", metadata: {} }, { name: "seed", metadata: { group: "setup" } }];
    const counted = { runId: "8", url: `${RUNS}/8`, startedAt: "2026-10-12T02:00:00Z", failed: [], skipped: [], status: "passed", durationMs: 1_200_000, counts: { passed: 900, failed: 0, pins: 0, flaky: 0, skipped: 0 } };
    const coverage = { target: "k8s", elements: { covered: 204, inScope: 300, outOfScope: 136, shared: 3 }, operations: { total: 303, reached: 301, covered: 301 }, problems: 0 };
    const html = runPageHtml({ report, lastRun: read(".last-run.json"), coverage, history: [counted], env: ENV });

    expect(html).toContain("<h2>The last 2 runs</h2>");
    expect(html).toContain('<span class="pill passed">passed</span>');
    expect(html).toContain('<span class="pill failed">1 failed</span>');
    expect(html).toContain("<h3>Product projects</h3>");
    expect(html).not.toContain("<h3>Setup and teardown</h3>");
    expect(html).toContain("<b>Product projects</b>944 cases: 895 passed, 1 failed, 48 did not run");
    expect(html).toContain("<td>API operations covered</td><td class=\"num\">301 of 303</td>");
    expect(html).toContain("<h2>Slowest");
  });
});
