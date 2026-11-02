/**
 * The run email the nightly workflow sends, rendered from reports built here and from the red
 * run the run-summary fixtures were trimmed from.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { CARDS_LIMIT, HTML_BYTES, HTML_FILE, PREHEADER_CHARS, TEXT_FILE, emailHtml, emailSubject, emailText, preheader } from "../../support/run-email.mjs";
import { ERROR_LINES, LINE_CHARS, escapeHtml } from "../../support/run-report.mjs";
import { pointLabel, runOf as emailOf } from "../../support/run-model.mjs";
import { OUTCOMES_FILE, testKey } from "../../support/run-history.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RED_RUN = path.resolve(HERE, "../../fixtures/run-summary/red");
const SCRIPT = path.resolve(HERE, "../../support/run-email.mjs");
const ROOT = "/home/runner/work/qip/qip/e2e";
const SHA = "7a393a86505c8e12d9b29a7161e70b061ad4a968";
const ENV = {
  GITHUB_SERVER_URL: "https://github.com",
  GITHUB_REPOSITORY: "Netcracker/qubership-integration-platform",
  GITHUB_SHA: SHA,
  GITHUB_REF_NAME: "main",
  GITHUB_RUN_ID: "38898485986",
  GITHUB_EVENT_NAME: "schedule",
};
const REPO = "https://github.com/Netcracker/qubership-integration-platform";
const RUN = `${REPO}/actions/runs/38898485986`;

type Test = { projectName: string; status: string; annotations?: { type: string; description?: string }[]; results: { status?: string; errors?: { message: string; location?: { file: string; line: number } }[] }[] };
type Spec = { title: string; file: string; line: number; tests: Test[] };
type Suite = { title: string; specs: Spec[]; suites?: Suite[] };

/** A report over `projects`, each name mapped to the projects it depends on. */
function reportOf(projects: Record<string, string[]>, suites: Suite[], errors: { message: string }[] = []) {
  const stats = { startTime: "2026-10-16T02:00:41.000Z", duration: 2_201_673, expected: 0, unexpected: 0, flaky: 0, skipped: 0 };
  const count = (suite: Suite) => {
    for (const spec of suite.specs) for (const each of spec.tests) stats[each.status as "expected"]++;
    suite.suites?.forEach(count);
  };
  suites.forEach(count);
  return {
    config: {
      rootDir: `${ROOT}/specs`,
      metadata: { target: "k8s" },
      projects: Object.entries(projects).map(([name, dependencies]) => ({ name, metadata: { dependencies } })),
    },
    suites,
    errors,
    stats,
  };
}

const passing = (project: string, title = "passes"): Spec => ({ title, file: "api/a.spec.ts", line: 1, tests: [{ projectName: project, status: "expected", results: [{}] }] });
const skipped = (project: string, line = 1): Spec => ({ title: `skipped ${line}`, file: "brokers/b.spec.ts", line, tests: [{ projectName: project, status: "skipped", results: [] }] });
const failing = (project: string, title: string, message: string, line = 7): Spec => ({
  title,
  file: `${project}/case.spec.ts`,
  line,
  tests: [{ projectName: project, status: "unexpected", results: [{ errors: [{ message, location: { file: `${ROOT}/support/corpus.ts`, line: 506 } }] }] }],
});
const file = (title: string, specs: Spec[], suites?: Suite[]): Suite => ({ title, specs, suites });

test.describe("run email", () => {
  test("escapes markup in the HTML part and keeps it in the text", () => {
    const title = `refuses <admin> & "root" when it's off`;
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", title, "Error: expected <b>403</b> & got 'it'")])]);
    const email = emailOf({ report, env: ENV });

    const html = emailHtml(email);
    expect(html).toContain("refuses &lt;admin&gt; &amp; &quot;root&quot; when it&#39;s off");
    expect(html).toContain("Error: expected &lt;b&gt;403&lt;/b&gt; &amp; got &#39;it&#39;");
    expect(html).not.toContain("<admin>");
    expect(html).not.toContain("<b>403");
    expect(emailText(email)).toContain(`* ${title}`);
    // The pattern matches the title, and inside double quotes the shell reads none of its characters.
    expect(email.failed[0].retry).toBe(`npm run test:k8s -- --project=api --no-deps specs/api/case.spec.ts:7 -g "refuses <admin> & .root. when it.s off"`);
    expect(new RegExp(`refuses <admin> & .root. when it.s off`).test(title)).toBe(true);
  });

  /** The string Playwright matches `-g` against: each suite's title, then its tags (`_grepTitleWithTags`). */
  function grepTitle(file: string, describes: [string, string[]][], title: string): string {
    return ["", "api", file, ...describes.flatMap(([name, tags]) => [name, ...tags]), title].join(" ");
  }

  const retryOf = (command: string) => new RegExp(command.match(/-g "(.*)"$/)![1]);

  test.describe("retry command", () => {
    // The health form: one case title on one line, under a describe per role.
    const roles = (title: string, line: number) => [
      file("engine", [failing("api", title, "Error: down", line)]),
      file("runtime-catalog", [failing("api", title, "Error: down", line)]),
    ];

    test("names the file, the line, and the describe path", () => {
      const report = reportOf({ api: [] }, [file("api/case.spec.ts", [], [file("actuator health", [], roles("reports UP", 12))])]);
      const [engine] = emailOf({ report, env: ENV }).failed;

      expect(engine.title).toBe("actuator health › engine › reports UP");
      expect(engine.retry).toBe(`npm run test:k8s -- --project=api --no-deps specs/api/case.spec.ts:12 -g "actuator health.*engine.*reports UP"`);
    });

    test("differs for loop cases that share one line", () => {
      const loop = [failing("api", "ignore mode", "Error: no", 20), failing("api", "throw mode", "Error: no", 20)];
      const report = reportOf({ api: [] }, [file("api/case.spec.ts", [], [file("idempotency", loop)])]);
      const [ignore, throwing] = emailOf({ report, env: ENV }).failed;

      expect(ignore.retry).not.toBe(throwing.retry);
      const pattern = retryOf(ignore.retry);
      expect(pattern.test(grepTitle("api/case.spec.ts", [["idempotency", []]], "ignore mode"))).toBe(true);
      expect(pattern.test(grepTitle("api/case.spec.ts", [["idempotency", []]], "throw mode"))).toBe(false);
    });

    test("differs for one title under two describes", () => {
      const report = reportOf({ api: [] }, [file("api/case.spec.ts", [], [file("actuator health", [], roles("reports UP", 12))])]);
      const [engine, catalog] = emailOf({ report, env: ENV }).failed;

      expect(engine.retry).not.toBe(catalog.retry);
      expect(retryOf(catalog.retry).test(grepTitle("api/case.spec.ts", [["actuator health", []], ["engine", ["@engine"]]], "reports UP"))).toBe(false);
    });

    test("matches past the tags of each describe", () => {
      const report = reportOf({ api: [] }, [file("api/case.spec.ts", [], [file("actuator health", [], roles("reports UP", 12))])]);
      const [engine, catalog] = emailOf({ report, env: ENV }).failed;
      const tagged = (role: string, tag: string) => grepTitle("api/case.spec.ts", [["actuator health", ["@infra"]], [role, [tag]]], "reports UP @slow");

      expect(retryOf(engine.retry).test(tagged("engine", "@engine"))).toBe(true);
      expect(retryOf(catalog.retry).test(tagged("runtime-catalog", "@catalog"))).toBe(true);
    });
  });

  // The message Playwright repeats after the first line is dropped too.

  test("cuts an error to its first lines and drops ANSI colors", () => {
    const long = "x".repeat(LINE_CHARS + 50);
    const message = [
      "Error: \u001b[31mthe route never answered\u001b[39m",
      "",
      "the route never answered",
      long,
      ...Array.from({ length: ERROR_LINES + 3 }, (_, i) => `  line ${i}`),
    ].join("\n");
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "case", message)])]);
    const { error } = emailOf({ report, env: ENV }).failed[0];

    expect(error.lines).toHaveLength(ERROR_LINES);
    expect(error.lines[0]).toBe("Error: the route never answered");
    expect(error.lines[1]).toBe(`${"x".repeat(LINE_CHARS - 1)}…`);
    expect(error.lines[2]).toBe("  line 0");
    expect(error.cut).toBe(true);
    expect(emailHtml(emailOf({ report, env: ENV }))).toContain("&nbsp;&nbsp;line 0");
  });

  test("stops the cards at their limit and counts the rest", () => {
    const extra = 5;
    const specs = Array.from({ length: CARDS_LIMIT + extra }, (_, i) => failing("api", `case ${i}`, "Error: no", i + 1));
    const email = emailOf({ report: reportOf({ api: [] }, [file("api/a.spec.ts", specs)]), env: ENV });

    const html = emailHtml(email);
    expect(html.match(/Retry locally/g)).toHaveLength(CARDS_LIMIT);
    expect(html).toContain(`case ${CARDS_LIMIT - 1}<`);
    expect(html).not.toContain(`case ${CARDS_LIMIT}<`);
    expect(html).toContain(`And ${extra} more, listed in <a href="${RUN}#artifacts" style="color:#175cd3;text-decoration:none;">the HTML report</a>.`);
    expect(html).toContain(">What failed<");
    expect(html).toContain(`>api</span>&nbsp; ${CARDS_LIMIT + extra} failed<`);
    const text = emailText(email);
    expect(text.match(/Retry locally/g)).toHaveLength(CARDS_LIMIT);
    expect(text).toContain(`And ${extra} more, listed in the HTML report: ${RUN}#artifacts`);
  });

  test("groups failed tests by project in config order", () => {
    const report = reportOf({ api: [], runtime: [] }, [
      file("runtime/r.spec.ts", [failing("runtime", "runtime one", "Error: r1")], [file("describe", [failing("runtime", "runtime two", "Error: r2")])]),
      file("api/a.spec.ts", [failing("api", "api one", "Error: a1"), passing("api")]),
    ]);
    const email = emailOf({ report, env: ENV });

    expect(email.failedProjects).toEqual(["api", "runtime"]);
    expect(email.failed.map((each) => each.title)).toEqual(["api one", "runtime one", "describe › runtime two"]);
    const html = emailHtml(email);
    expect(html.indexOf(">api</span>")).toBeLessThan(html.indexOf(">runtime</span>"));
    expect(html).toContain(">runtime</span>&nbsp; 2 failed<");
    expect(emailText(email)).toContain("\n[api] 1 failed\n");
    expect(emailSubject(email)).toBe("E2E nightly failed: 3 tests in api and runtime (main @ 7a393a8)");
  });

  test("names the failed dependency of a project that ran no test", () => {
    const projects = {
      "brokers-micro-seed": [],
      global: [],
      "brokers-micro": ["brokers-micro-seed"],
      "brokers-restart": ["brokers-micro", "global"],
      "env-micro": ["brokers-restart"],
      lonely: [],
    };
    const report = reportOf(projects, [
      file("seed.setup.ts", [failing("brokers-micro-seed", "seeds", "Error: no route")]),
      file("g.spec.ts", [passing("global")]),
      file("b.spec.ts", [skipped("brokers-micro", 1), skipped("brokers-micro", 2), skipped("brokers-restart"), skipped("env-micro"), skipped("lonely")]),
    ]);
    const email = emailOf({ report, env: ENV });

    expect(email.notRun).toEqual([
      {
        cause: ["brokers-micro-seed"],
        projects: [
          { name: "brokers-micro", skipped: 2 },
          { name: "brokers-restart", skipped: 1 },
          { name: "env-micro", skipped: 1 },
        ],
      },
      { cause: [], projects: [{ name: "lonely", skipped: 1 }] },
    ]);
    expect(email.verdict).toBe("1 test failed in brokers-micro-seed, and 5 tests did not run.");
    expect(emailText(email)).toContain("brokers-micro-seed failed, so these projects did not run:\n  brokers-micro      2 tests\n");
    expect(emailHtml(email)).toContain("<strong>brokers&#8209;micro&#8209;seed</strong> failed, so these projects did not run:<");
    expect(emailHtml(email)).not.toContain("Playwright skips a project");

    // A report written before the config copied the dependencies into the metadata.
    for (const project of report.config.projects) delete (project as { metadata?: unknown }).metadata;
    const fallback = emailOf({ report, env: ENV });
    expect(fallback.notRun).toHaveLength(1);
    expect(fallback.notRun[0].cause).toEqual([]);
    expect(emailText(fallback)).toContain("These projects ran none of their tests:");
    expect(emailText(fallback)).toContain("Playwright skips a project when a project it depends on fails.");
  });

  test("gives an error outside any test its own block and subject", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])], [
      { message: "\u001b[31mError: Command failed: helm upgrade --install cip\u001b[39m\n    at provision" },
    ]);
    const email = emailOf({ report, lastRun: { status: "failed" }, env: { ...ENV, FAILED_STEP: "Run the suite" } });

    expect(email.tone).toBe("failed");
    expect(email.failedStep).toBeUndefined();
    expect(emailSubject(email)).toBe("E2E nightly failed: Error: Command failed: helm upgrade --install cip (main @ 7a393a8)");
    const html = emailHtml(email);
    expect(html).toContain("Errors outside any test");
    expect(html).toContain("Error: Command failed: helm upgrade --install cip<br>&nbsp;&nbsp;&nbsp;&nbsp;at provision");
    expect(html).not.toContain("\u001b");
    expect(html).not.toContain("What failed");
    expect(email.verdict).toBe("An error outside any test failed the run: Command failed: helm upgrade --install cip");

    const timedOut = emailOf({ report, lastRun: { status: "timedout" }, env: ENV });
    expect(timedOut.tone).toBe("warning");
    expect(timedOut.title).toBe("E2E nightly timed out");
    expect(timedOut.verdict).toBe("The run timed out after 37 min.");
  });

  // A missing report still yields an email.

  test("names the failed step when no test or error failed the run", () => {
    const green = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])]);
    const afterSuite = emailOf({ report: green, lastRun: { status: "passed" }, env: { ...ENV, FAILED_STEP: "Reconcile the coverage registry" } });
    expect(afterSuite.tone).toBe("failed");
    expect(emailSubject(afterSuite)).toBe("E2E nightly failed at Reconcile the coverage registry (main @ 7a393a8)");
    expect(afterSuite.verdict).toBe(`The run failed at the step "Reconcile the coverage registry".`);
    expect(emailHtml(afterSuite)).toContain("The run failed at the step &quot;Reconcile the coverage registry&quot;.");

    const noReport = emailOf({ env: { ...ENV, GITHUB_EVENT_NAME: "workflow_dispatch", FAILED_STEP: "Bootstrap the cluster" } });
    expect(noReport.counts).toBeUndefined();
    expect(emailSubject(noReport)).toBe("E2E run failed at Bootstrap the cluster (main @ 7a393a8)");
    expect(preheader(noReport)).toBe(`The run failed at the step "Bootstrap the cluster".`);
    expect(emailHtml(noReport)).not.toContain("did not run");
    expect(emailHtml(noReport)).not.toContain("Passed</div>");
    expect(emailOf({ env: { ...ENV, FAILED_STEP: "a step after the suite" } }).verdict).toBe("The run failed at a step after the suite.");
    expect(emailOf({ env: ENV }).verdict).toBe("The run failed before it wrote a report.");

    const passed = emailOf({ report: green, lastRun: { status: "passed" }, env: ENV });
    expect([passed.tone, passed.title, passed.verdict]).toEqual(["passed", "E2E nightly passed", "All 1 test passed."]);
  });

  // A job canceled in teardown, after every test ran and passed; only the email gets FAILED_STEP.
  test("reports an interrupted run whose tests passed", () => {
    const green = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])]);
    for (const env of [ENV, { ...ENV, FAILED_STEP: "a step the run page names" }]) {
      const run = emailOf({ report: green, lastRun: { status: "interrupted" }, env });
      expect([run.tone, run.title, run.verdict]).toEqual(["warning", "E2E nightly interrupted", "The run was interrupted after 37 min."]);
      expect(emailSubject(run)).toBe("E2E nightly interrupted (main @ 7a393a8)");
      expect(pointLabel(run.points.at(-1)!)).toBe("interrupted");
    }
  });

  // The run page and the step summary run without FAILED_STEP, so the coverage file alone decides.
  test("fails a passed run when reconciliation found a problem", () => {
    const green = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])]);
    const coverage = { target: "k8s", elements: { covered: 204, inScope: 300, outOfScope: 136, shared: 3 }, operations: { total: 303, reached: 301, covered: 301 }, problems: 2 };
    for (const env of [ENV, { ...ENV, FAILED_STEP: "Reconcile the coverage registry" }]) {
      const run = emailOf({ report: green, lastRun: { status: "passed" }, coverage, env });
      expect([run.tone, run.title]).toEqual(["failed", "E2E nightly failed"]);
      expect(run.verdict).toBe("Coverage reconciliation found 2 problems; the reconcile step lists them.");
      expect(emailSubject(run)).toBe("E2E nightly failed: 2 coverage problems (main @ 7a393a8)");
      expect(run.points.at(-1)).toMatchObject({ status: "failed", failed: 0 });
    }
    expect(emailOf({ report: green, lastRun: { status: "passed" }, coverage: { ...coverage, problems: 0 }, env: ENV }).tone).toBe("passed");
  });

  test("builds every link from the workflow variables, or none", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "case", "Error: no", 42)])]);
    const email = emailOf({ report, env: ENV });

    expect(email.runUrl).toBe(RUN);
    expect(email.artifactsUrl).toBe(`${RUN}#artifacts`);
    expect(email.commit).toEqual({ text: "7a393a8", href: `${REPO}/commit/${SHA}` });
    expect(email.readmeUrl).toBe(`${REPO}/blob/${SHA}/e2e/README.md#reproducing-one-case`);
    expect(email.failed[0].location).toEqual({ text: "api/case.spec.ts:42", href: `${REPO}/blob/${SHA}/e2e/specs/api/case.spec.ts#L42` });
    expect(email.failed[0].failedAt).toEqual({ text: "support/corpus.ts:506", href: `${REPO}/blob/${SHA}/e2e/support/corpus.ts#L506` });
    const html = emailHtml(email);
    expect(html).toContain(`<a href="${RUN}" style="display:inline-block;`);
    expect(html).toContain(`href="${REPO}/blob/${SHA}/e2e/specs/api/case.spec.ts#L42"`);

    const local = emailOf({ report, stack: { commit: { sha: SHA, branch: "ci/e2e-nightly" } }, env: {} });
    expect(local.runUrl).toBeUndefined();
    expect(local.failed[0].location.href).toBeUndefined();
    expect(emailSubject(local)).toBe("E2E run failed: 1 test in api (ci/e2e-nightly @ 7a393a8)");
    expect(emailHtml(local)).not.toContain("href=");
  });

  /** A `test.fail()` case that passed, so the defect it pins no longer reproduces. */
  const passedPin = (project: string, title: string, description?: string): Spec => ({
    title,
    file: `${project}/pin.spec.ts`,
    line: 3,
    tests: [{ projectName: project, status: "unexpected", annotations: [{ type: "fail", description }], results: [{ status: "passed", errors: [] }] }],
  });

  test("lists and counts a pin to remove apart from the failures", () => {
    const struck: Spec = { title: "struck", file: "api/a.spec.ts", line: 2, tests: [{ projectName: "api", status: "expected", annotations: [{ type: "fail" }], results: [{ status: "failed" }] }] };
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api"), struck, passedPin("api", "pinned", "#1086 the sender drops it"), failing("api", "red", "Error: no"), skipped("api")])]);
    const email = emailOf({ report, env: ENV });

    expect(email.counts).toEqual({ passed: 2, failed: 1, pins: 1, flaky: 0, skipped: 1 });
    expect(email.failed.map((each) => each.title)).toEqual(["red"]);
    expect(email.pins).toEqual([{ project: "api", title: "pinned", location: { text: "api/pin.spec.ts:3", href: `${REPO}/blob/${SHA}/e2e/specs/api/pin.spec.ts#L3` }, defect: "#1086 the sender drops it" }]);
    expect(email.verdict).toBe("1 test failed in api, and 1 test did not run.");
    expect(email.trend).toBe("1 pinned defect no longer reproduces.");
    expect(emailSubject(email)).toBe("E2E nightly failed: 1 test in api, 1 skipped, 1 pin to remove (main @ 7a393a8)");
    expect(preheader(email)).toBe("1 test failed in api, and 1 test did not run. 1 pinned defect no longer reproduces.");
    const text = emailText(email);
    expect(text).toContain("\n2 passed, 1 failed, 1 skipped, 1 pin to remove (5 product cases)\n");
    expect(text).toContain(`Pins to remove\n--------------\n\nEach test.fail() case below passed`);
    expect(text).toContain(`  * pinned\n    [api] api/pin.spec.ts:3  ${REPO}/blob/${SHA}/e2e/specs/api/pin.spec.ts#L3\n    #1086 the sender drops it\n`);
    const html = emailHtml(email);
    expect(html).toMatch(/>2<\/div><div [^>]*>Passed<\/div>/);
    expect(html).toMatch(/>1<\/div><div [^>]*>Pins to remove<\/div>/);
    expect(html).toContain(`;">Pins to remove</td>`);
    expect(html.match(/Retry locally/g)).toHaveLength(1);
    expect(html).not.toContain("Flaky");
    expect(html).not.toContain("Known defects");
  });

  // Whatever step the workflow names as failed.

  test("reports fixed defects when pins are the only failures", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api"), passedPin("api", "pinned")])]);
    const email = emailOf({ report, lastRun: { status: "failed" }, env: { ...ENV, FAILED_STEP: "Run the suite" } });

    expect([email.tone, email.title, email.failedStep]).toEqual(["warning", "E2E nightly found fixed defects", undefined]);
    expect(email.verdict).toBe("1 pinned defect no longer reproduces. Every other test passed.");
    expect(emailSubject(email)).toBe("E2E nightly found fixed defects: 1 pin to remove (main @ 7a393a8)");
    expect(emailHtml(email)).not.toContain("What failed");
  });

  test("the script writes both parts and prints the subject", () => {
    const dir = test.info().outputPath("red");
    fs.mkdirSync(dir, { recursive: true });
    for (const each of ["report.json", ".last-run.json", "stack.json"]) fs.copyFileSync(path.join(RED_RUN, each), path.join(dir, each));

    const output = execFileSync(process.execPath, [SCRIPT, dir], { encoding: "utf8", env: { PATH: process.env.PATH, ...ENV } });
    expect(output).toBe("subject=E2E nightly failed: 1 test in ui, 48 skipped (main @ 7a393a8)\n");
    expect(fs.readFileSync(path.join(dir, HTML_FILE), "utf8")).toContain("a live exchange is killed only after its termination is confirmed");
    expect(fs.readFileSync(path.join(dir, TEXT_FILE), "utf8")).toContain("brokers-seed: the brokers have no chart on the k8s target");

    const empty = test.info().outputPath("never-ran");
    const step = execFileSync(process.execPath, [SCRIPT, empty], { encoding: "utf8", env: { PATH: process.env.PATH, ...ENV, FAILED_STEP: "Install kind and istioctl" } });
    expect(step).toBe("subject=E2E nightly failed at Install kind and istioctl (main @ 7a393a8)\n");
    expect(fs.existsSync(path.join(empty, HTML_FILE))).toBe(true);
  });

  test("counts setup and suite checks apart from product cases", () => {
    const report = reportOf({ harness: [], seed: [], api: [] }, [
      file("harness/h.spec.ts", [passing("harness", "h1"), passing("harness", "h2"), failing("harness", "h3", "Error: no")]),
      file("seed/s.setup.ts", [passing("seed", "s1")]),
      file("api/a.spec.ts", [passing("api", "a1"), passing("api", "a2")]),
    ]);
    report.config.projects[0].metadata = { ...report.config.projects[0].metadata, group: "suite" } as never;
    report.config.projects[1].metadata = { ...report.config.projects[1].metadata, group: "setup" } as never;
    const email = emailOf({ report, env: ENV });

    expect(email.counts).toEqual({ passed: 2, failed: 0, pins: 0, flaky: 0, skipped: 0 });
    expect(email.groups?.suite).toEqual({ passed: 2, failed: 1, pins: 0, flaky: 0, skipped: 0 });
    // A failed suite check still fails the run.
    expect([email.tone, email.verdict]).toEqual(["failed", "1 test failed in harness."]);
    expect(emailSubject(email)).toBe("E2E nightly failed: 1 test in harness (main @ 7a393a8)");
    expect(emailText(email)).toContain("\n2 passed, 0 failed, 0 skipped (2 product cases)\nSetup and teardown: 1 passed\nSuite checks: 2 passed, 1 failed\n");
    expect(emailHtml(email)).toContain(">Suite checks: 2 passed, 1 failed</div>");

    const product = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])]);
    const green = emailOf({ report: product, env: ENV });
    expect(emailText(green)).not.toContain("Suite checks:");
  });

  test("the verdict names the failed projects and their dependents", () => {
    const seed = reportOf({ "brokers-micro-seed": [], "brokers-micro": ["brokers-micro-seed"] }, [
      file("seed.setup.ts", [failing("brokers-micro-seed", "seeds", "Error: no route")]),
      file("b.spec.ts", [skipped("brokers-micro", 1), skipped("brokers-micro", 2), passing("brokers-micro")]),
    ]);
    // brokers-micro ran one test, so it is not a project that ran none of them.
    expect(emailOf({ report: seed, env: ENV }).verdict).toBe("1 test failed in brokers-micro-seed, and 2 tests did not run.");
    seed.suites[1].specs.pop();
    seed.stats.expected--;
    expect(emailOf({ report: seed, env: ENV }).verdict).toBe("1 test failed in brokers-micro-seed, and 2 tests that depend on it did not run.");

    const three = reportOf({ api: [], runtime: [], "brokers-micro-seed": [], env: ["api"] }, [
      file("a.spec.ts", [failing("api", "a1", "Error: a"), failing("api", "a2", "Error: a", 8)]),
      file("r.spec.ts", [failing("runtime", "r1", "Error: r")]),
      file("s.setup.ts", [failing("brokers-micro-seed", "s1", "Error: s")]),
      file("e.spec.ts", [skipped("env")]),
    ]);
    expect(emailOf({ report: three, env: ENV }).verdict).toBe("4 tests failed in api, runtime, and brokers-micro-seed. 1 test that depends on them did not run.");

    const four = reportOf({ api: [], runtime: [], ui: [], global: [] }, ["api", "runtime", "ui", "global"].map((name) => file(`${name}.spec.ts`, [failing(name, name, "Error: no")])));
    expect(emailOf({ report: four, env: ENV }).verdict).toBe("4 tests failed in api, runtime, and 2 other projects.");
  });

  test("the verdict reports an error raised before any test ran", () => {
    const report = reportOf({ api: [] }, [], [{ message: "Error: kube-context kind-qip is not reachable" }]);
    const email = emailOf({ report, lastRun: { status: "failed" }, env: ENV });
    expect(email.verdict).toBe("The run stopped before its tests finished: kube-context kind-qip is not reachable");
    expect(preheader(email)).toBe("The run stopped before its tests finished: kube-context kind-qip is not reachable.");
  });

  test("the HTML uses sentence-case headings and no middle dot", () => {
    const report = reportOf({ "brokers-micro-seed": [], "brokers-micro": ["brokers-micro-seed"] }, [
      file("seed.setup.ts", [failing("brokers-micro-seed", "seeds", "Error: no route")], [file("describe", [failing("brokers-micro-seed", "nested", "Error: no")])]),
      file("b.spec.ts", [skipped("brokers-micro")]),
    ], [{ message: "Error: globalTeardown: 1 entity survived the sweep" }]);
    const html = emailHtml(emailOf({ report, stack: { absent: [{ name: "brokers-seed-teardown", reason: "suite-teardown runs it" }] }, env: ENV }));

    for (const heading of ["Errors outside any test", "What failed", "What did not run"]) expect(html).toContain(`;">${heading}</td>`);
    expect(html).not.toContain("text-transform");
    expect(html).not.toContain("·");
    expect(html).not.toContain("&middot;");
  });

  // A command, a link, and the text part keep the hyphens as typed.

  test("keeps project-name hyphens unbroken in HTML prose only", () => {
    const report = reportOf({ "brokers-micro-seed": [], "brokers-micro": ["brokers-micro-seed"] }, [
      file("seed.setup.ts", [failing("brokers-micro-seed", "seeds", "Error: chains on e2e-r9u3x9-brokers-micro never answered")]),
      file("b.spec.ts", [skipped("brokers-micro")]),
    ]);
    const email = emailOf({ report, env: ENV });
    const html = emailHtml(email);
    const nb = (name: string) => name.replace(/-/g, "&#8209;");

    expect(html).toContain(`>1 test failed in ${nb("brokers-micro-seed")}, and 1 test that depends on it did not run.<`);
    expect(html).toContain(`>${nb("brokers-micro-seed")}</span>&nbsp; 1 failed<`);
    expect(html).toContain(`<strong>${nb("brokers-micro-seed")}</strong> failed, so this project did not run:`);
    expect(html).toContain(`>${nb("brokers-micro")}</td>`);
    expect(html).toContain(`--project=brokers-micro-seed --no-deps specs/brokers-micro-seed/case.spec.ts:7 -g`);
    expect(html).toContain(`>brokers-micro-seed/case.spec.ts:7</a>`);
    expect(html).toContain("Error: chains on e2e-r9u3x9-brokers-micro never answered");
    expect(html).toContain(`${escapeHtml(preheader(email))}&#8199;`);
    expect(emailText(email)).not.toMatch(/\u2011|&#8209;/);
  });

  const RUNS = `${REPO}/actions/runs`;
  const earlier = (runId: string, startedAt: string, failed: string[] = [], sha = "0123456") => ({ runId, url: `${RUNS}/${runId}`, sha, startedAt, failed, skipped: [] });

  // The title reads "still failing" when every failure is.

  test("marks each failure as new or still failing", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "old", "Error: no"), failing("api", "fresh", "Error: no", 9)])]);
    const old = testKey("api", "api/case.spec.ts", "old");
    const history = [earlier("2", "2026-10-15T02:00:00Z", [old], SHA), earlier("1", "2026-10-14T02:00:00Z", [old, "api › gone.spec.ts › fixed"])];
    const mixed = emailOf({ report, history, env: ENV });

    expect(mixed.title).toBe("E2E nightly failed");
    expect(mixed.trend).toBe("1 of them is new since the previous run.");
    expect(preheader(mixed)).toBe("2 tests failed in api. 1 of them is new since the previous run.");
    const html = emailHtml(mixed);
    // Both runs kept failed it, so it began in one the history no longer holds.
    expect(html).toContain(`Failing for 3 runs, since <a href="${RUNS}/1" style="color:#175cd3;text-decoration:none;">Oct 14</a> or earlier`);
    // The run that passed it ran this commit, which points at a flaky test rather than a change.
    expect(html).toContain(`New since <a href="${RUNS}/2" style="color:#175cd3;text-decoration:none;">the last run that ran it</a>, which ran the same commit`);
    expect(emailText(mixed)).toContain(`    Failing for 3 runs, since Oct 14 or earlier  ${RUNS}/1\n`);

    const still = emailOf({ report: reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "old", "Error: no")])]), history, env: ENV });
    expect(emailSubject(still)).toBe("E2E nightly still failing: 1 test in api (main @ 7a393a8)");
    expect(still.trend).toBe("It failed in the previous run too.");
    expect(emailText(still).split("\n")[0]).toBe("1 test failed in api. It failed in the previous run too.");

    expect(emailOf({ report, env: ENV }).trend).toBeUndefined();
  });

  test("marks an intermittent failure in the card and the trend", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "flip", "Error: no"), failing("api", "fresh", "Error: no", 9)])]);
    const flip = testKey("api", "api/case.spec.ts", "flip");
    const history = [earlier("3", "2026-10-15T02:00:00Z", [], SHA), earlier("2", "2026-10-14T02:00:00Z", [flip]), earlier("1", "2026-10-13T02:00:00Z")];
    const email = emailOf({ report, history, env: ENV });

    expect(email.trend).toBe("1 of them is new since the previous run. 1 of them fails intermittently.");
    expect(emailHtml(email)).toContain(
      `Intermittent: failed in 1 of the 3 runs before this one, and passed in <a href="${RUNS}/3" style="color:#175cd3;text-decoration:none;">the last run that ran it</a>, which ran the same commit`,
    );
    expect(emailText(email)).toContain(`    Intermittent: failed in 1 of the 3 runs before this one, and passed in the last run that ran it, which ran the same commit  ${RUNS}/3\n`);

    const alone = emailOf({ report: reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "flip", "Error: no")])]), history, env: ENV });
    expect(alone.trend).toBe("It fails intermittently.");
    expect(alone.title).toBe("E2E nightly failed");
  });

  test("counts the fixed tests in the trend", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "red", "Error: no"), passing("api", "green")])]);
    const history = [earlier("2", "2026-10-15T02:00:00Z", [testKey("api", "api/a.spec.ts", "green")])];
    expect(emailOf({ report, history, env: ENV }).trend).toBe("It is new since the previous run. 1 test that the previous run failed passes now.");
  });

  test("links the report and cluster dump the workflow passes", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "case", "Error: no")])]);
    const email = emailOf({ report, env: { ...ENV, REPORT_URL: `${RUN}/artifacts/1`, CLUSTER_URL: `${RUN}/artifacts/2` } });
    expect(email.artifactsUrl).toBe(`${RUN}/artifacts/1`);
    expect(emailHtml(email)).toContain(`<a href="${RUN}/artifacts/2" style="color:#175cd3;text-decoration:none;">Cluster dump</a>`);
    expect(emailText(email)).toContain(`Cluster dump: ${RUN}/artifacts/2\n`);
    expect(emailHtml(emailOf({ report, env: ENV }))).not.toContain("Cluster dump");
  });

  test("the script reads the earlier runs from E2E_HISTORY_DIR", () => {
    const dir = test.info().outputPath("red");
    fs.mkdirSync(dir, { recursive: true });
    for (const each of ["report.json", ".last-run.json", "stack.json"]) fs.copyFileSync(path.join(RED_RUN, each), path.join(dir, each));
    const history = test.info().outputPath("history");
    const failed = [testKey("ui", "ui/admin-tools.spec.ts", "a live exchange is killed only after its termination is confirmed")];
    fs.mkdirSync(path.join(history, "1"), { recursive: true });
    fs.writeFileSync(path.join(history, "1", OUTCOMES_FILE), JSON.stringify(earlier("1", "2026-10-15T02:00:00Z", failed)));

    const output = execFileSync(process.execPath, [SCRIPT, dir], { encoding: "utf8", env: { PATH: process.env.PATH, ...ENV, E2E_HISTORY_DIR: history } });
    expect(output).toBe("subject=E2E nightly still failing: 1 test in ui, 48 skipped (main @ 7a393a8)\n");
  });

  test("the subject leads with how long the failures have lasted", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "old", "Error: no"), failing("api", "fresh", "Error: no", 9)])]);
    const old = testKey("api", "api/case.spec.ts", "old");
    const history = [earlier("2", "2026-10-15T02:00:00Z", [old])];
    expect(emailSubject(emailOf({ report, history, env: ENV }))).toBe("E2E nightly failed: 1 new, 1 still failing in api (main @ 7a393a8)");
    // Without an earlier run, nothing says which is new.
    expect(emailSubject(emailOf({ report, env: ENV }))).toBe("E2E nightly failed: 2 tests in api (main @ 7a393a8)");
  });

  test("a failed setup project leads the verdict and the subject", () => {
    const report = reportOf({ seed: [], api: ["seed"], runtime: ["seed"] }, [
      file("seed/seed.setup.ts", [failing("seed", "seeds", "Error: no route")]),
      file("api/a.spec.ts", [skipped("api", 1), skipped("api", 2)]),
      file("runtime/r.spec.ts", [failing("runtime", "r1", "Error: r")]),
    ]);
    report.config.projects[0].metadata = { ...report.config.projects[0].metadata, group: "setup" } as never;
    const email = emailOf({ report, env: ENV });

    expect(email.infra).toEqual({ projects: ["seed"], didNotRun: 2 });
    expect(email.verdict).toBe("Infrastructure failure: setup project seed failed, so 2 cases did not run. 1 other test failed in runtime.");
    expect(emailSubject(email)).toBe("E2E nightly failed: seed failed in setup, 2 cases did not run (main @ 7a393a8)");
    expect(preheader(email)).toMatch(/^Infrastructure failure: setup project seed failed, so 2 cases did not run\. /);
  });

  test("the preview line holds the verdict within its length", () => {
    const names = Array.from({ length: 3 }, (_, i) => `brokers-micro-restart-${i}`);
    const report = reportOf(Object.fromEntries(names.map((name) => [name, []])), names.map((name) => file(`${name}.spec.ts`, [failing(name, name, "Error: no")])));
    const email = emailOf({ report, env: ENV });

    expect(email.verdict.length).toBeGreaterThan(PREHEADER_CHARS);
    expect(preheader(email).length).toBeLessThanOrEqual(PREHEADER_CHARS);
    expect(preheader(email)).toMatch(/^3 tests failed in brokers-micro-restart-0, .*[a-z0-9,]…$/);
    expect(emailHtml(email)).toContain(`${escapeHtml(preheader(email))}&#8199;`);
  });

  test("draws the trend as cells that name each run's result", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "red", "Error: no")])]);
    const counted = (runId: string, startedAt: string, failed: number, status = failed > 0 ? "failed" : "passed") => ({
      ...earlier(runId, startedAt),
      status,
      durationMs: 1_500_000,
      counts: { passed: 10, failed, pins: 0, flaky: 0, skipped: 0 },
    });
    const history = [counted("3", "2026-10-15T02:00:00Z", 0, "timedout"), earlier("2", "2026-10-14T02:00:00Z"), counted("1", "2026-10-13T02:00:00Z", 2)];
    const email = emailOf({ report, history, env: ENV });

    expect(email.points.map((each) => [each.runId, each.status, each.failed])).toEqual([["1", "failed", 2], ["3", "timedout", 0], ["38898485986", "failed", 1]]);
    const html = emailHtml(email);
    expect(html).toContain(">The last 3 runs</td>");
    expect(html).toContain(`<a href="${RUNS}/1" style="color:#b42318;text-decoration:none;">2 failed</a>`);
    expect(html).toContain(`<a href="${RUNS}/3" style="color:#b54708;text-decoration:none;">timed out</a>`);
    // This run is the outlined cell.
    expect(html).toMatch(new RegExp(`border:2px solid #b42318;[^>]*><div [^>]*><a href="${RUN}" style="color:#b42318;text-decoration:none;">1 failed</a>`));
    expect(html).not.toMatch(/<img|<svg|data:/);
    expect(emailText(email)).toContain("The last 3 runs, the oldest first: Oct 13: 2 failed; Oct 15: timed out; Oct 16: 1 failed (this run)\n");
    // One run is no trend.
    expect(emailHtml(emailOf({ report, history: [earlier("2", "2026-10-14T02:00:00Z")], env: ENV }))).not.toContain("runs</td>");
  });

  test("a green run lists its projects by group", () => {
    const report = reportOf({ harness: [], seed: [], api: [] }, [file("api/a.spec.ts", [passing("api"), passing("seed"), passing("harness")])]);
    report.config.projects[0].metadata = { group: "suite" } as never;
    report.config.projects[1].metadata = { group: "setup" } as never;
    const html = emailHtml(emailOf({ report, env: ENV }));

    expect(html).toContain(">Projects</td>");
    const order = ["Product projects", "Setup and teardown", "Suite checks"].map((title) => html.indexOf(`">${title}</td>`));
    expect(order.every((at, i) => at > 0 && (i === 0 || at > order[i - 1]))).toBe(true);
    expect(html).toContain(">api</td>");

    const red = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "red", "Error: no")])]);
    expect(emailHtml(emailOf({ report: red, env: ENV }))).not.toContain(">Projects</td>");
  });

  test("links the run page and the HTML report the workflow uploaded", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "case", "Error: no")])]);
    const linked = emailOf({ report, env: { ...ENV, REPORT_HTML_URL: `${RUN}/artifacts/7`, RUN_PAGE_URL: `${RUN}/artifacts/8` } });
    expect(emailHtml(linked)).toContain(`<a href="${RUN}/artifacts/8" style="color:#175cd3;text-decoration:none;">Run page</a>`);
    expect(emailHtml(linked)).toContain(`<a href="${RUN}/artifacts/7" style="color:#175cd3;text-decoration:none;">HTML report</a>`);
    expect(emailText(linked)).toContain(`Run page: ${RUN}/artifacts/8\nHTML report: ${RUN}/artifacts/7\n`);
    const bare = emailHtml(emailOf({ report, env: ENV }));
    expect(bare).not.toContain(">Run page<");
    expect(bare).not.toContain(">HTML report<");
  });

  test("lays out 600 px, with a word for each status", () => {
    const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "old", "Error: no"), failing("api", "fresh", "Error: no", 9)])]);
    const history = [earlier("2", "2026-10-15T02:00:00Z", [testKey("api", "api/case.spec.ts", "old")])];
    const html = emailHtml(emailOf({ report, history, env: ENV }));

    expect(html).toContain('<body bgcolor="#f2f4f7" style="margin:0;padding:0;background-color:#f2f4f7;">');
    expect(html).toContain('<table role="presentation" width="600" align="center"');
    expect(html).toContain("max-width:600px;");
    expect(html).toMatch(/font-weight:700;color:#475467;">Still failing<\/div>/);
    expect(html).toMatch(/font-weight:700;color:#b42318;">New<\/div>/);
    // Every background-color sits on an element that also carries bgcolor, except the inline badges.
    const cells = html.match(/<(?:td|table|body)[^>]*background-color:[^>]*>/g) ?? [];
    expect(cells.length).toBeGreaterThan(5);
    for (const each of cells) expect(each).toContain("bgcolor=");
  });

  test("keeps the HTML part under its size limit", () => {
    const dir = test.info().outputPath("red");
    fs.mkdirSync(dir, { recursive: true });
    for (const each of ["report.json", ".last-run.json", "stack.json"]) fs.copyFileSync(path.join(RED_RUN, each), path.join(dir, each));
    execFileSync(process.execPath, [SCRIPT, dir], { encoding: "utf8", env: { PATH: process.env.PATH, ...ENV } });
    expect(fs.statSync(path.join(dir, HTML_FILE)).size).toBeLessThan(HTML_BYTES);

    // As many cards as a long error and a long title allow.
    const long = Array.from({ length: 12 }, (_, i) => `${i} ${"x".repeat(250)}`).join("\n");
    const specs = Array.from({ length: 40 }, (_, i) => failing("runtime-micro", `${"a long case title ".repeat(8)}${i}`, long, i + 1));
    const html = emailHtml(emailOf({ report: reportOf({ "runtime-micro": [] }, [file("runtime-micro/case.spec.ts", specs)], [{ message: long }]), env: ENV }));
    expect(Buffer.byteLength(html)).toBeLessThanOrEqual(HTML_BYTES);
    expect(html.match(/Retry locally/g)!.length).toBeLessThan(CARDS_LIMIT);
    expect(html).toMatch(/And \d+ more, listed in/);
  });
});
