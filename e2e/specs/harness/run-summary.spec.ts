/**
 * The run summary the CI step summary carries, read off reports trimmed from a green and a red CI run.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { FAILED_TESTS_LIMIT, METASPACE_ATTACHMENT, PEAKS_ATTACHMENT, SUMMARY_FILE, summaryMarkdown, writeRunSummary } from "../../support/run-summary.mjs";
import { OUTCOMES_FILE, testKey } from "../../support/run-history.mjs";
import { runOf } from "../../support/run-model.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, "../../fixtures/run-summary");
const SCRIPT = path.resolve(HERE, "../../support/run-summary.mjs");
const RUN_FILES = ["report.json", ".last-run.json", "stack.json"];
const RUNS = "https://github.com/Netcracker/qubership-integration-platform/actions/runs";

function copyRun(run: "green" | "red", files = RUN_FILES): string {
  const dir = test.info().outputPath(run);
  fs.mkdirSync(dir, { recursive: true });
  for (const file of files) fs.copyFileSync(path.join(FIXTURES, run, file), path.join(dir, file));
  return dir;
}

function readRun(run: "green" | "red") {
  const read = (file: string) => JSON.parse(fs.readFileSync(path.join(FIXTURES, run, file), "utf8"));
  return { report: read("report.json"), lastRun: read(".last-run.json"), stack: read("stack.json") };
}

/** The red run's one failed spec, and the suite that holds it. */
function failingSpec(report: ReturnType<typeof readRun>["report"]) {
  const admin = report.suites.find((suite: { file: string }) => suite.file === "ui/admin-tools.spec.ts");
  const failing = admin.specs.find((spec: { title: string }) => spec.title.startsWith("a live exchange"));
  return { admin, failing };
}

/** The green run with its projects grouped as the config groups them: `schema` checks the suite. */
function groupedGreen() {
  const run = readRun("green");
  run.report.config.projects = [
    { name: "schema", metadata: { stack: false, group: "suite" } },
    { name: "seed", metadata: { group: "setup" } },
    { name: "runtime", metadata: {} },
  ];
  return run;
}

const FAILING_KEY = testKey("ui", "ui/admin-tools.spec.ts", "a live exchange is killed only after its termination is confirmed");

/** An earlier run's outcomes, as the history directory holds them. */
const earlier = (runId: string, startedAt: string, failed: string[] = [], skipped: string[] = []) => ({ runId, url: `${RUNS}/${runId}`, startedAt, failed, skipped });

test.describe("run summary", () => {
  test("a green run opens with its verdict and counts", () => {
    const dir = copyRun("green");

    writeRunSummary(dir);
    const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
    expect(summary.split("\n")[0]).toBe("**E2E run passed.** All 944 tests passed. Product cases: 944 passed.");
    expect(summary).not.toContain("### Failures");
    expect(summary).not.toContain("### Errors");
  });

  test("names each image taken from GHCR by its digest", () => {
    const run = readRun("green");
    const digest = `sha256:${"a".repeat(64)}`;
    run.stack.provision = { published: [
      { name: "runtime-catalog", image: "ghcr.io/netcracker/qubership-integration-runtime-catalog:latest", imageId: `ghcr.io/netcracker/qubership-integration-runtime-catalog@${digest}` },
      { name: "micro-engine", image: "ghcr.io/netcracker/qubership-integration-micro-engine:latest", imageId: null },
    ] };
    const lines = summaryMarkdown(run).split("\n");
    expect(lines).toContain(`From GHCR: runtime-catalog \`${digest}\`, micro-engine \`ghcr.io/netcracker/qubership-integration-micro-engine:latest (no pod yet)\`.`);

    run.stack.provision = { published: [] };
    expect(summaryMarkdown(run)).not.toContain("From GHCR");
  });

  test("a red run lists each failure with its first error line", () => {
    const dir = copyRun("red");

    writeRunSummary(dir);
    const summary = fs.readFileSync(path.join(dir, SUMMARY_FILE), "utf8");
    expect(summary).toContain("**E2E run failed.** 1 test failed in ui, and 48 tests did not run. Product cases: 895 passed, 1 failed, 48 did not run.");
    expect(summary).not.toContain("Pins to remove");
    expect(summary).toContain(
      "#### Failed (1)\n\n<details><summary><b>a live exchange is killed only after its termination is confirmed</b> in <code>ui</code>: " +
        "Error: expect(received).toBe(expected) // Object.is equality</summary>",
    );
    expect(summary).toContain("```bash\nnpm run test:k8s -- --project=ui --no-deps specs/ui/admin-tools.spec.ts:126 -g ");
    expect(summary).not.toContain("\u001b");
    // The red run predates the broker subcharts, so its stack.json still lists brokers-seed as absent.
    expect(summary).toContain("<summary>Left out on this target</summary>\n\n- `brokers-seed`: the brokers have no chart on the k8s target");
  });

  test("counts each project group apart, and the counts add up", () => {
    const { report } = groupedGreen();
    const [spec] = report.suites[0].specs;
    const one = (projectName: string, status: string) => ({ ...spec, title: `${projectName} ${status}`, tests: [{ ...spec.tests[0], projectName, status, results: [] }] });
    report.suites[0].specs.push(one("seed", "unexpected"), one("seed", "expected"), one("schema", "skipped"));
    report.stats.unexpected += 1;
    report.stats.expected += 1;
    report.stats.skipped += 1;

    const { groups } = runOf({ report });
    expect(groups).toEqual({
      product: { passed: 942, failed: 0, pins: 0, flaky: 0, skipped: 0 },
      setup: { passed: 1, failed: 1, pins: 0, flaky: 0, skipped: 0 },
      suite: { passed: 2, failed: 0, pins: 0, flaky: 0, skipped: 1 },
    });
    const total = Object.values(groups!).reduce((sum, each) => sum + each.passed + each.failed + each.pins + each.flaky + each.skipped, 0);
    expect(total).toBe(report.stats.expected + report.stats.unexpected + report.stats.flaky + report.stats.skipped);
    expect(summaryMarkdown({ report })).toContain("Product cases: 942 passed. Setup and teardown: 1 passed, 1 failed. Suite checks: 2 passed, 1 did not run.");
  });

  test("a failed setup project reads as an infrastructure failure", () => {
    const { report } = groupedGreen();
    report.config.projects.push({ name: "api", metadata: { dependencies: ["seed"] } });
    const [spec] = report.suites[0].specs;
    const one = (projectName: string, status: string, title: string) => ({ ...spec, title, tests: [{ ...spec.tests[0], projectName, status, results: [{ status: "failed", errors: [{ message: "Error: no route" }] }] }] });
    report.suites[0].specs.push(one("seed", "unexpected", "seeds"), one("api", "skipped", "a1"), one("api", "skipped", "a2"));
    report.stats.unexpected += 1;
    report.stats.skipped += 2;

    const summary = summaryMarkdown({ report });
    expect(summary).toContain("**E2E run failed.** Infrastructure failure: setup project seed failed, so 2 cases did not run. Product cases: 942 passed, 2 did not run.");
    expect(summary).toContain("<b>seeds</b> in <code>seed</code>: Error: no route");
  });

  test("a failed test under a describe carries the describe path", () => {
    const { report } = readRun("red");
    const misc = report.suites.find((suite: { file: string }) => suite.file === "runtime/misc-elements.spec.ts");
    misc.suites[0].specs[0].tests[0].status = "unexpected";

    expect(runOf({ report }).failed.map((each) => each.title)).toContain("context-storage › operation=SET stores a value that operation=GET reads back into the body");
  });

  test("lists an unexpected test, never a skipped or flaky one", () => {
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    const [failingTest] = failing.tests;
    const failedResult = failingTest.results[0];
    admin.specs.push(
      { ...failing, title: "skipped", tests: [{ ...failingTest, status: "skipped", results: [{ ...failedResult, status: "skipped" }] }] },
      { ...failing, title: "flaky", tests: [{ ...failingTest, status: "flaky", results: [failedResult, { ...failedResult, status: "passed", errors: [] }] }] },
    );

    expect(runOf({ report }).failed.map((each) => each.title)).toEqual([failing.title]);
  });

  test("escapes markup in a title or an error line", () => {
    const { report } = readRun("red");
    const { failing } = failingSpec(report);
    failing.title = "a <b> & c";
    failing.tests[0].results[0].errors = [{ message: "Error: <html> answered" }];

    expect(summaryMarkdown({ report })).toContain("<b>a &lt;b&gt; &amp; c</b> in <code>ui</code>: Error: &lt;html&gt; answered</summary>");
  });

  test("cuts the failed list at its limit and says where the rest is", () => {
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    const extra = 10;
    admin.specs = Array.from({ length: FAILED_TESTS_LIMIT + extra }, (_, i) => ({ ...failing, line: i + 1 }));

    const summary = summaryMarkdown({ report });
    expect(summary.match(/<details><summary><b>/g)).toHaveLength(FAILED_TESTS_LIMIT);
    expect(summary).toContain(`specs/ui/admin-tools.spec.ts:${FAILED_TESTS_LIMIT} `);
    expect(summary).not.toContain(`specs/ui/admin-tools.spec.ts:${FAILED_TESTS_LIMIT + 1} `);
    expect(summary).toContain(`${extra} more failed. The HTML report in the \`e2e-playwright-report\` artifact lists every failure.`);
    expect(summaryMarkdown({ report, env: { RUN_PAGE_URL: `${RUNS}/9/artifacts/2` } })).toContain(`${extra} more failed. The run page lists every failure.`);
  });

  test("links the HTML pages the workflow uploaded, and only those", () => {
    const { report } = readRun("green");
    const env = { GITHUB_REPOSITORY: "Netcracker/qubership-integration-platform", GITHUB_RUN_ID: "9" };
    const bare = summaryMarkdown({ report, env });
    expect(bare).toContain(`- [The run](${RUNS}/9)\n- [Run artifacts](${RUNS}/9#artifacts): `);
    expect(bare).not.toContain("Report with traces");
    expect(bare).not.toContain("Run page");
    expect(bare).not.toContain("HTML report](");

    const linked = summaryMarkdown({ report, env: { ...env, REPORT_HTML_URL: `${RUNS}/9/artifacts/1`, RUN_PAGE_URL: `${RUNS}/9/artifacts/2`, REPORT_URL: `${RUNS}/9/artifacts/3`, PLAYWRIGHT_REPORT_URL: `${RUNS}/9/artifacts/4` } });
    expect(linked).toContain(`- [Run page](${RUNS}/9/artifacts/2): `);
    expect(linked).toContain(`- [HTML report](${RUNS}/9/artifacts/1): `);
    expect(linked).toContain(`- [Run artifacts](${RUNS}/9/artifacts/3): `);
    expect(linked).toContain(`- [Report with traces](${RUNS}/9/artifacts/4): Playwright's report with its traces and attachments. Download it and run \`npx playwright show-report e2e-playwright-report.zip\`.`);
    expect(summaryMarkdown({ report })).not.toContain("[The run]");
  });

  test("lists global errors with their ANSI colors stripped", () => {
    const { report } = readRun("green");
    report.errors = [{ message: "Error: \u001b[31mTimed out waiting 8400s for the test suite to finish\u001b[39m" }];

    expect(summaryMarkdown({ report })).toContain(
      "### Errors outside any test\n\n```text\nError: Timed out waiting 8400s for the test suite to finish\n```",
    );
  });

  test("a missing run file leaves out only its own sections", () => {
    const onlyLastRun = copyRun("red", [".last-run.json"]);
    writeRunSummary(onlyLastRun);
    expect(fs.readFileSync(path.join(onlyLastRun, SUMMARY_FILE), "utf8")).toBe("**E2E run failed.** The run failed before it wrote a report.\n");

    const noStack = copyRun("green", ["report.json"]);
    writeRunSummary(noStack);
    const summary = fs.readFileSync(path.join(noStack, SUMMARY_FILE), "utf8");
    expect(summary).toContain("Product cases: 944 passed.");
    expect(summary).toContain("Took 18.7 min.");
    expect(summary).not.toContain("Left out on this target");
  });

  test("the script writes the summary and prints nothing", () => {
    const red = copyRun("red");
    expect(execFileSync(process.execPath, [SCRIPT, red], { encoding: "utf8" })).toBe("");
    expect(fs.readFileSync(path.join(red, SUMMARY_FILE), "utf8")).toContain("### Failures");
  });

  test("groups a failure as new, or as failing since a linked run", () => {
    const { report } = readRun("red");
    const passedBefore = summaryMarkdown({ report, history: [earlier("2", "2026-10-14T02:00:00Z"), earlier("1", "2026-10-13T02:00:00Z")] });
    expect(passedBefore).toContain("#### New (1)\n\n<details><summary><b>a live exchange");
    expect(passedBefore).toContain(`New since [the last run that ran it](${RUNS}/2).`);
    expect(passedBefore).toContain(`Against the [previous run](${RUNS}/2) of Oct 14: 1 new failure, 0 still failing, 0 fixed.`);

    // A run that skipped the test says nothing either way, and the streak reads through it.
    const history = [earlier("3", "2026-10-15T02:00:00Z", [FAILING_KEY]), earlier("2", "2026-10-14T02:00:00Z", [], [FAILING_KEY]), earlier("1", "2026-10-13T02:00:00Z", [FAILING_KEY]), earlier("0", "2026-10-12T02:00:00Z")];
    const still = summaryMarkdown({ report, history });
    expect(still).toContain("#### Still failing (1)");
    expect(still).toContain(`Object.is equality (3 runs, since <a href="${RUNS}/1">Oct 13</a>)</summary>`);
    expect(still).toContain("0 new failures, 1 still failing, 0 fixed.");

    // Failing in every run kept, the start lies before the history.
    expect(summaryMarkdown({ report, history: history.slice(0, 3) })).toContain(`(3 runs, since <a href="${RUNS}/1">Oct 13</a> or earlier)`);
  });

  test("groups an intermittent failure and counts it in the verdict", () => {
    const { report } = readRun("red");
    const history = [earlier("3", "2026-10-15T02:00:00Z"), earlier("1", "2026-10-13T02:00:00Z", [FAILING_KEY])];
    const summary = summaryMarkdown({ report, history });
    expect(summary).toContain("#### Intermittent (1)");
    expect(summary).toContain(`Intermittent: failed in 1 of the 2 runs before this one, and passed in [the last run that ran it](${RUNS}/3).`);
    expect(summary).toContain(`Against the [previous run](${RUNS}/3) of Oct 15: 0 new failures, 1 intermittent, 0 still failing, 0 fixed.`);
  });

  test("lists the tests fixed since the previous run", () => {
    const { report } = readRun("green");
    const fixed = testKey("runtime", "runtime/misc-elements.spec.ts", "httpMethod=POST: an http sender sends POST");
    const summary = summaryMarkdown({ report, history: [earlier("2", "2026-10-14T02:00:00Z", [fixed])] });
    expect(summary).toContain("0 new failures, 0 still failing, 1 fixed.");
    expect(summary).toContain("#### Fixed since the last run (1)\n\n- httpMethod=POST: an http sender sends POST in `runtime`, `runtime/misc-elements.spec.ts`");
  });

  test("compares the counts and duration with the previous run", () => {
    const { report } = readRun("green");
    const previous = { ...earlier("2", "2026-10-14T02:00:00Z"), status: "failed", durationMs: 1_800_000, counts: { passed: 900, failed: 2, pins: 0, flaky: 0, skipped: 0 } };
    expect(summaryMarkdown({ report, history: [previous] })).toContain("0 fixed. That run failed 2 product cases in 30.0 min.");
    expect(summaryMarkdown({ report, history: [earlier("2", "2026-10-14T02:00:00Z")] })).not.toContain("That run failed");
  });

  test("charts the trend once the history has the counts", () => {
    const { report, lastRun } = readRun("green");
    const run = (runId: string, startedAt: string, failed: number) => ({ ...earlier(runId, startedAt), status: failed > 0 ? "failed" : "passed", durationMs: 1_200_000, counts: { passed: 900, failed, pins: 0, flaky: 0, skipped: 0 } });
    const history = [run("3", "2026-10-12T02:00:00Z", 3), earlier("2", "2026-10-11T02:00:00Z"), run("1", "2026-10-10T02:00:00Z", 0)];

    const summary = summaryMarkdown({ report, lastRun, history });
    expect(summary).toContain("### The last 3 runs");
    expect(summary).toContain('```mermaid\nxychart-beta\n  title "Failed product cases"\n  x-axis ["Oct 10 02:00", "Oct 12 02:00", "Oct 13 06:23"]\n  y-axis "Cases" 0 --> 3\n  bar [0, 3, 0]\n```');
    expect(summary).toContain('  title "Duration in minutes"\n  x-axis ["Oct 10 02:00", "Oct 12 02:00", "Oct 13 06:23"]\n  y-axis "Minutes"\n  line [20.0, 20.0, 18.7]\n```');
    // A history written before the counts existed draws nothing.
    expect(summaryMarkdown({ report, lastRun, history: [earlier("2", "2026-10-11T02:00:00Z")] })).not.toContain("mermaid");
  });

  test("writes the outcomes the next run reads as history", () => {
    const dir = copyRun("red");
    const { report } = readRun("red");
    const { admin, failing } = failingSpec(report);
    admin.specs.push({ ...failing, title: "skipped", tests: [{ ...failing.tests[0], status: "skipped" }] });
    fs.writeFileSync(path.join(dir, "report.json"), JSON.stringify(report));

    writeRunSummary(dir, { GITHUB_REPOSITORY: "Netcracker/qubership-integration-platform", GITHUB_RUN_ID: "38916652673", GITHUB_SHA: "8b5ae2c" });
    const outcomes = JSON.parse(fs.readFileSync(path.join(dir, OUTCOMES_FILE), "utf8"));
    expect(outcomes).toEqual({
      runId: "38916652673",
      url: `${RUNS}/38916652673`,
      sha: "8b5ae2c",
      startedAt: report.stats.startTime,
      durationMs: 1086668,
      finishedAt: "2026-10-13T02:36:50.943Z",
      status: "failed",
      counts: { passed: 895, failed: 1, pins: 0, flaky: 0, skipped: 48 },
      failed: [FAILING_KEY],
      skipped: [testKey("ui", "ui/admin-tools.spec.ts", "skipped")],
    });
  });

  test("shows the coverage reconcile wrote and its verdict", () => {
    const coverage = {
      target: "k8s",
      elements: { covered: 204, inScope: 300, outOfScope: 136, shared: 3 },
      operations: { total: 303, reached: 301, covered: 301 },
      messages: { covered: 61, handled: 67, unhandled: 5 },
      problems: 0,
    };
    expect(summaryMarkdown({ coverage })).toContain(
      "<details><summary>Coverage</summary>\n\nElements: 204 of 300 registry rows in scope covered on k8s (136 out of scope, 3 shared). " +
        "API operations: 301 of 303 covered, 301 reached. VS Code messages: 61 of 67 handled message types covered, 5 unhandled. Reconciliation passed.",
    );
    expect(summaryMarkdown({ coverage: { ...coverage, problems: 2 } })).toContain("Reconciliation found 2 problems; the reconcile step lists them.");
  });

  test("shows the peaks and Metaspace the envelope spec attached", () => {
    const { report } = readRun("green");
    const body = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64");
    const MIB = 1024 ** 2;
    report.suites[0].specs[0].tests[0].results[0].attachments = [
      { name: PEAKS_ATTACHMENT, contentType: "application/json", body: body([{ role: "engine", memoryBytes: 1079 * MIB, cpuPercent: 324.4, samples: 300, band: { memoryBytes: 3072 * MIB, cpuPercent: 900 } }]) },
      { name: METASPACE_ATTACHMENT, contentType: "application/json", body: body([{ role: "engine", usedBytes: 151 * MIB, maxBytes: 512 * MIB, band: 0.95 }]) },
    ];
    expect(summaryMarkdown({ report })).toContain("| engine | 1079 MiB of 3072 MiB | 324% of 900% | 151 MiB of 512 MiB, 29% of 95% |");
    expect(summaryMarkdown(readRun("green"))).not.toContain("<summary>Resources</summary>");
  });

  test("times the projects of each group and lists the slowest tests", () => {
    const summary = summaryMarkdown(groupedGreen());
    expect(summary).toContain("Took 18.7 min.");
    expect(summary).toContain("<summary>Time by project</summary>\n\n#### Product projects\n\n| Project | Tests | Started | Took |\n| --- | --- | --- | --- |\n| `runtime` | 3 |");
    expect(summary).toMatch(/#### Suite checks\n\n\| Project \| Tests \| Started \| Took \|\n\| --- \| --- \| --- \| --- \|\n\| `schema` \| 2 \| \+6:14 \| 0:00 \|/);
    // A group that ran nothing gets no table.
    expect(summary).not.toContain("#### Setup and teardown");
    expect(summary).toContain("<summary>Slowest 5 tests</summary>");
  });

  test("drops the tables, then the failure bodies, to fit its limit", () => {
    const { report } = readRun("red");
    const full = summaryMarkdown({ report });
    const size = (text: string) => Buffer.byteLength(text);
    expect(full).toContain("<summary>Slowest");

    const noSlowest = summaryMarkdown({ report }, size(full) - 1);
    expect(noSlowest).not.toContain("<summary>Slowest");
    expect(noSlowest).toContain("<summary>Time by project</summary>");

    const noTables = summaryMarkdown({ report }, size(noSlowest) - 1);
    expect(noTables).not.toContain("<summary>Time by project</summary>");
    expect(noTables).toContain("```bash\nnpm run test:k8s");

    const bare = summaryMarkdown({ report }, size(noTables) - 1);
    expect(bare).not.toContain("```bash");
    expect(bare).toContain("- <b>a live exchange is killed only after its termination is confirmed</b> in <code>ui</code>: Error:");
  });

  // A passed pin in a suite check stays a failure, and one in a product project that needs no stack is a pin.

  test("lists a passed test.fail() case as a pin to remove", () => {
    const { report } = readRun("green");
    const [spec] = report.suites[0].specs;
    const passedPin = (projectName: string, description?: string) => ({ ...spec.tests[0], projectName, status: "unexpected", annotations: [{ type: "fail", description }], results: [{ status: "passed", errors: [] }] });
    const struckPin = { ...spec.tests[0], projectName: "api", status: "expected", annotations: [{ type: "fail" }], results: [{ status: "failed", errors: [] }] };
    // notTheKnownDefect fails a pinned case for real.
    const realFailure = { ...struckPin, status: "unexpected", results: [{ status: "failed", errors: [{ message: "Error: a new failure" }] }] };
    report.config.projects = [{ name: "schema", metadata: { stack: false, group: "suite" } }, { name: "vscode", metadata: { stack: false } }];
    report.stats.unexpected = 5;
    report.suites[0].specs.push(
      { ...spec, title: "a", line: 8, tests: [passedPin("runtime-micro", "the micro engine drops it")] },
      { ...spec, title: "b", line: 9, tests: [passedPin("api")] },
      { ...spec, title: "c", line: 10, tests: [passedPin("schema")] },
      { ...spec, title: "d", line: 11, tests: [struckPin] },
      { ...spec, title: "e", line: 12, tests: [realFailure] },
      { ...spec, title: "f", line: 13, tests: [passedPin("vscode", "#1225 the labels are not saved")] },
    );
    const summary = summaryMarkdown({ report });
    expect(summary).toContain("Product cases: 942 passed, 1 failed, 3 pins to remove. Suite checks: 2 passed, 1 failed.");
    expect(summary).toContain("### Pins to remove\n\nEach `test.fail()` case below passed");
    expect(summary).toContain(`| runtime-micro | \`${spec.file}:8\` | a | the micro engine drops it |`);
    expect(summary).toContain(`| api | \`${spec.file}:9\` | b |  |`);
    expect(summary).toContain(`| vscode | \`${spec.file}:13\` | f | #1225 the labels are not saved |`);
    expect(runOf({ report }).failed.map((each) => each.title)).toEqual(["c", "e"]);
    expect(summary).not.toContain("Known defects");
  });
});
