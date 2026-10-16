/**
 * The failure email the nightly workflow sends, rendered from reports built here and from the red
 * run the run-summary fixtures were trimmed from.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import {
  CARDS_LIMIT,
  ERROR_LINES,
  HTML_FILE,
  LINE_CHARS,
  TEXT_FILE,
  emailHtml,
  emailOf,
  emailSubject,
  emailText,
  escapeHtml,
  preheader,
} from "../../support/run-email.mjs";
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

type Test = { projectName: string; status: string; annotations?: { type: string }[]; results: { errors?: { message: string; location?: { file: string; line: number } }[] }[] };
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

test("a title or an error carrying markup is escaped in the HTML part and kept as written in the text part", { tag: ["@infra", "@tier2"] }, () => {
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
  expect(email.failed[0].retry).toBe(`npm run test:k8s -- --project=api --no-deps -g "refuses <admin> & .root. when it.s off"`);
  expect(new RegExp(`refuses <admin> & .root. when it.s off`).test(title)).toBe(true);
});

test("an error keeps its first lines, each cut at a length, with its ANSI colors and repeated message dropped", { tag: ["@infra", "@tier2"] }, () => {
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

test("the cards stop at their limit and the rest are counted", { tag: ["@infra", "@tier2"] }, () => {
  const extra = 5;
  const specs = Array.from({ length: CARDS_LIMIT + extra }, (_, i) => failing("api", `case ${i}`, "Error: no", i + 1));
  const email = emailOf({ report: reportOf({ api: [] }, [file("api/a.spec.ts", specs)]), env: ENV });

  const html = emailHtml(email);
  expect(html.match(/Retry locally/g)).toHaveLength(CARDS_LIMIT);
  expect(html).toContain(`case ${CARDS_LIMIT - 1}<`);
  expect(html).not.toContain(`case ${CARDS_LIMIT}<`);
  expect(html).toContain(`And ${extra} more, listed in the HTML report`);
  expect(html).toContain(">What failed<");
  expect(html).toContain(`>api</span>&nbsp; ${CARDS_LIMIT + extra} failed<`);
  const text = emailText(email);
  expect(text.match(/Retry locally/g)).toHaveLength(CARDS_LIMIT);
  expect(text).toContain(`And ${extra} more, listed in the HTML report: ${RUN}#artifacts`);
});

test("failed tests are grouped by project in the order the config declares the projects", { tag: ["@infra", "@tier2"] }, () => {
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

test("a project that ran none of its tests names the failed project it depends on", { tag: ["@infra", "@tier2"] }, () => {
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

test("an error raised outside any test gets its own block and names the subject", { tag: ["@infra", "@tier2"] }, () => {
  const report = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])], [
    { message: "\u001b[31mError: Command failed: helm upgrade --install qip\u001b[39m\n    at provision" },
  ]);
  const email = emailOf({ report, lastRun: { status: "failed" }, env: { ...ENV, FAILED_STEP: "Run the suite" } });

  expect(email.tone).toBe("failed");
  expect(email.failedStep).toBeUndefined();
  expect(emailSubject(email)).toBe("E2E nightly failed: Error: Command failed: helm upgrade --install qip (main @ 7a393a8)");
  const html = emailHtml(email);
  expect(html).toContain("Errors outside any test");
  expect(html).toContain("Error: Command failed: helm upgrade --install qip<br>&nbsp;&nbsp;&nbsp;&nbsp;at provision");
  expect(html).not.toContain("\u001b");
  expect(html).not.toContain("What failed");
  expect(email.verdict).toBe("An error outside any test failed the run: Command failed: helm upgrade --install qip");

  const timedOut = emailOf({ report, lastRun: { status: "timedout" }, env: ENV });
  expect(timedOut.tone).toBe("warning");
  expect(timedOut.title).toBe("E2E nightly timed out");
  expect(timedOut.verdict).toBe("The run timed out after 37 min.");
});

test("with no failed test and no error, the email names the failed step, and a missing report still yields one", { tag: ["@infra", "@tier2"] }, () => {
  const green = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api")])]);
  const afterSuite = emailOf({ report: green, lastRun: { status: "passed" }, env: { ...ENV, FAILED_STEP: "Reconcile the coverage registry" } });
  expect(afterSuite.tone).toBe("failed");
  expect(emailSubject(afterSuite)).toBe("E2E nightly failed at Reconcile the coverage registry (main @ 7a393a8)");
  expect(afterSuite.verdict).toBe(`The run failed at the step "Reconcile the coverage registry".`);
  expect(emailHtml(afterSuite)).toContain("The run failed at the step &quot;Reconcile the coverage registry&quot;.");

  const noReport = emailOf({ env: { ...ENV, GITHUB_EVENT_NAME: "workflow_dispatch", FAILED_STEP: "Bootstrap the cluster" } });
  expect(noReport.counts).toBeUndefined();
  expect(emailSubject(noReport)).toBe("E2E run failed at Bootstrap the cluster (main @ 7a393a8)");
  expect(preheader(noReport)).toBe(`The run failed at the step "Bootstrap the cluster". On k8s.`);
  expect(emailHtml(noReport)).not.toContain("did not run");
  expect(emailHtml(noReport)).not.toContain("Passed</div>");
  expect(emailOf({ env: { ...ENV, FAILED_STEP: "a step after the suite" } }).verdict).toBe("The run failed at a step after the suite.");
  expect(emailOf({ env: ENV }).verdict).toBe("The run failed before it wrote a report.");

  const passed = emailOf({ report: green, lastRun: { status: "passed" }, env: ENV });
  expect([passed.tone, passed.title, passed.verdict]).toEqual(["passed", "E2E nightly passed", "All 1 test passed."]);
});

test("every link is built from the workflow's variables, and none without them", { tag: ["@infra", "@tier2"] }, () => {
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

test("a known defect that struck is counted apart from the passed tests", { tag: ["@infra", "@tier2"] }, () => {
  const pinned: Spec = { title: "pinned", file: "api/a.spec.ts", line: 3, tests: [{ projectName: "api", status: "expected", annotations: [{ type: "fail" }], results: [{}] }] };
  const report = reportOf({ api: [] }, [file("api/a.spec.ts", [passing("api"), passing("api", "two"), pinned, failing("api", "red", "Error: no"), skipped("api")])]);
  const email = emailOf({ report, env: ENV });

  expect(email.counts).toEqual({ passed: 2, failed: 1, flaky: 0, skipped: 1, known: 1, total: 5 });
  expect(preheader(email)).toBe("1 test failed in api, and 1 test did not run. 2 passed, 1 known defect. 37 min on k8s.");
  expect(emailText(email)).toContain("\n2 passed, 1 failed, 0 flaky, 1 skipped, 1 known defect (5 tests)\n");
  const html = emailHtml(email);
  expect(html).toMatch(/>2<\/div><div [^>]*>Passed<\/div>/);
  expect(html).toMatch(/>1<\/div><div [^>]*>Known defects<\/div>/);
});

test("the script writes both parts and prints the subject as a step output", { tag: ["@infra", "@tier2"] }, () => {
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

test("the verdict names the failed projects and the tests that depend on them", { tag: ["@infra", "@tier2"] }, () => {
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

test("an error raised before any test ran stops the run in the verdict", { tag: ["@infra", "@tier2"] }, () => {
  const report = reportOf({ api: [] }, [], [{ message: "Error: kube-context kind-qip is not reachable" }]);
  const email = emailOf({ report, lastRun: { status: "failed" }, env: ENV });
  expect(email.verdict).toBe("The run stopped before its tests finished: kube-context kind-qip is not reachable");
  expect(preheader(email)).toBe("The run stopped before its tests finished: kube-context kind-qip is not reachable. 37 min on k8s.");
});

test("the HTML heads its sections in sentence case and separates its items without a middle dot", { tag: ["@infra", "@tier2"] }, () => {
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

test("a project name in prose keeps its hyphens from breaking, and a command, a link, and the text part keep them as typed", { tag: ["@infra", "@tier2"] }, () => {
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
  expect(html).toContain(`--project=brokers-micro-seed --no-deps -g`);
  expect(html).toContain(`>brokers-micro-seed/case.spec.ts:7</a>`);
  expect(html).toContain("Error: chains on e2e-r9u3x9-brokers-micro never answered");
  expect(html).toContain(`${escapeHtml(preheader(email))}&#8199;`);
  expect(emailText(email)).not.toMatch(/\u2011|&#8209;/);
});

const RUNS = `${REPO}/actions/runs`;
const earlier = (runId: string, startedAt: string, failed: string[] = [], sha = "0123456") => ({ runId, url: `${RUNS}/${runId}`, sha, startedAt, failed, skipped: [] });

test("each failed test says whether the run before failed it too, and the title says when every one did", { tag: ["@infra", "@tier2"] }, () => {
  const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "old", "Error: no"), failing("api", "fresh", "Error: no", 9)])]);
  const old = testKey("api", "api/case.spec.ts", "old");
  const history = [earlier("2", "2026-10-15T02:00:00Z", [old], SHA), earlier("1", "2026-10-14T02:00:00Z", [old, "api › gone.spec.ts › fixed"])];
  const mixed = emailOf({ report, history, env: ENV });

  expect(mixed.title).toBe("E2E nightly failed");
  expect(mixed.trend).toBe("1 of them is new since the previous run.");
  expect(preheader(mixed)).toBe("2 tests failed in api. 1 of them is new since the previous run. 0 passed. 37 min on k8s.");
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

test("a test the previous run failed and this one passed is counted in the trend", { tag: ["@infra", "@tier2"] }, () => {
  const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "red", "Error: no"), passing("api", "green")])]);
  const history = [earlier("2", "2026-10-15T02:00:00Z", [testKey("api", "api/a.spec.ts", "green")])];
  expect(emailOf({ report, history, env: ENV }).trend).toBe("It is new since the previous run. 1 test that the previous run failed passes now.");
});

test("the report and the cluster dump link to their artifacts when the workflow passes them", { tag: ["@infra", "@tier2"] }, () => {
  const report = reportOf({ api: [] }, [file("api/a.spec.ts", [failing("api", "case", "Error: no")])]);
  const email = emailOf({ report, env: { ...ENV, REPORT_URL: `${RUN}/artifacts/1`, CLUSTER_URL: `${RUN}/artifacts/2` } });
  expect(email.artifactsUrl).toBe(`${RUN}/artifacts/1`);
  expect(emailHtml(email)).toContain(`<a href="${RUN}/artifacts/2" style="color:#175cd3;text-decoration:none;">Cluster dump</a>`);
  expect(emailText(email)).toContain(`Cluster dump: ${RUN}/artifacts/2\n`);
  expect(emailHtml(emailOf({ report, env: ENV }))).not.toContain("Cluster dump");
});

test("a pin in a project that needs no stack is not a known defect", { tag: ["@infra", "@tier2"] }, () => {
  const pinned: Spec = { title: "pinned", file: "schema/a.spec.ts", line: 3, tests: [{ projectName: "schema", status: "expected", annotations: [{ type: "fail" }], results: [{}] }] };
  const report = reportOf({ schema: [] }, [file("schema/a.spec.ts", [pinned, failing("schema", "red", "Error: no")])]);
  Object.assign(report.config.projects[0].metadata, { stack: false });
  expect(emailOf({ report, env: ENV }).counts).toMatchObject({ passed: 1, known: 0 });
});

test("the script reads the earlier runs from the directory E2E_HISTORY_DIR names", { tag: ["@infra", "@tier2"] }, () => {
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
