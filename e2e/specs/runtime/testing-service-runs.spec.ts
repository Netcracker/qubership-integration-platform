/**
 * The testing service as a target: test runs, the case runs inside them, and what a run reports.
 *
 * Two controllers serve runs, and the spec keeps them apart. `/tests-runs` is the run as a whole:
 * its listing, its read, its cancel and bulk cancel, its delete and bulk delete, its exports, and
 * `create`, which starts one. `/test-case-runs` is one case inside a run: its listing, its read, its
 * cancel and bulk cancel, its exports, and its validation errors. Only `/tests-runs` deletes; a
 * `DELETE` on either `/test-case-runs` path answers 405, and a case run goes with its test run.
 *
 * The executor runs the cases of one test run one at a time, in the order they were selected, and
 * a cancel reaches only the case runs still `pending`. A cancel case therefore puts a slow case
 * first: `long-running` holds its exchange for as long as the `e2e-hold-ms` header says, so the
 * cases behind it stay pending while the spec cancels them.
 *
 * A mock answers only the outbound calls of a test case run. The engine marks an exchange as one
 * when the trigger call carries `external-session-cip-id` (`HttpTriggerProcessor.java:85`), which a
 * run sends and a plain call does not, so a plain call to the same chain reaches the real endpoint
 * while the mock is registered. `http-out` is the one seed chain with an outbound call, and this is
 * the only runtime file that registers a mock on it; `specs/tooling/mock-smoke.spec.ts` does so
 * before `runtime` starts.
 */
import { test, expect } from "../../support/fixtures.js";
import { UUID } from "../../support/absent.js";
import { readCorpusState, seedChain, triggerOf } from "../../support/corpus.js";
import { tokenized } from "../../support/run.js";
import type { TestCase, TestCaseRequest, TestCaseRun, TestingService } from "../../support/testing-service.js";

/** How the exports write a timestamp: Go's `time.Time.String()`, kept for the ported service's readers. */
const GO_TIME = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)? \+0000 UTC$/;
const EXPORT_HEADER = [
  "Tests Run ID", "Test Case Run ID", "Chain ID", "Test Case ID", "Test Case Name", "Test Case Description",
  "Start", "Finish", "Status", "Session ID", "Errors", "Rule ID", "Rule Name", "Rule Description", "Message",
];
/** How long the slow case holds its exchange: long enough to cancel what waits behind it. */
const HOLD_MS = "6000";
/** What an `equal` rule on `status` writes against `http-echo`, which answers 200. */
function statusMismatch(status: string): string {
  return `expected: '${status}', got: '200'`;
}
const FAILED_STATUS = statusMismatch("201");
/** A second failing status, so the errors of two failing cases tell apart. */
const FAILED_ACCEPTED = statusMismatch("202");

function passing(name: string): TestCaseRequest {
  return {
    name,
    enabled: true,
    trigger: triggerOf("http-echo"),
    method: "POST",
    body: "{}",
    rules: [{ name: "answers ok", type: "equal", entityType: "status", value: "200" }],
  };
}

function failing(name: string, status = "201"): TestCaseRequest {
  return { ...passing(name), rules: [{ name: `answers ${status}`, type: "equal", entityType: "status", value: status }] };
}

function slow(name: string): TestCaseRequest {
  return {
    name,
    enabled: true,
    trigger: triggerOf("long-running"),
    method: "POST",
    body: "{}",
    headers: [{ name: "e2e-hold-ms", value: HOLD_MS }],
  };
}

function byOrdinal(runs: TestCaseRun[]): TestCaseRun[] {
  return [...runs].sort((a, b) => a.ordinal - b.ordinal);
}

/** Waits until the first case of the run is claimed, which leaves the ones behind it pending. */
async function awaitFirstRunning(testingService: TestingService, runId: string, cases: number): Promise<TestCaseRun[]> {
  const firstRunning = (runs: TestCaseRun[]) =>
    runs.length === cases && runs.find((each) => each.ordinal === 1)?.status === "running";
  return byOrdinal(await testingService.awaitCaseRuns(runId, cases, firstRunning));
}

test("a run queues the selected test cases in order and reports a passing and a failing case", { tag: ["@testing-service", "@engine", "@tier1"] }, async ({ testingService, run }) => {
  await testingService.withTestCases([passing(tokenized(run, "runs-pass")), failing(tokenized(run, "runs-fail"))], async ([pass, fail], start) => {
    const runId = await start([pass.id, fail.id]);
    expect(runId).toMatch(UUID);
    const [passRun, failRun] = byOrdinal(await testingService.awaitCaseRuns(runId, 2));

    const chainId = pass.triggerReference.chainId;
    expect(passRun).toMatchObject({ testsRunId: runId, testCaseId: pass.id, testCaseName: pass.name, chainId, ordinal: 1, status: "finished", errors: 0 });
    expect(failRun).toMatchObject({ testsRunId: runId, testCaseId: fail.id, testCaseName: fail.name, chainId, ordinal: 2, status: "finished", errors: 1 });
    for (const each of [passRun, failRun]) {
      expect(each.sessionId).toMatch(UUID);
      expect(await testingService.getCaseRun(each.id)).toEqual(each);
    }

    const testsRun = await testingService.getTestsRun(runId);
    expect(testsRun).toMatchObject({ id: runId, status: "finished", testCases: 2, errors: 1, start: passRun.start, finish: failRun.finish });
    expect(await testingService.testsRunsWithIds([runId])).toEqual([testsRun]);

    expect(await testingService.errorsOf(passRun.id)).toEqual([]);
    const [error] = await testingService.errorsOf(failRun.id);
    expect(error).toMatchObject({ testCaseRunId: failRun.id, matcherId: fail.responseValidationRules?.[0].id, message: FAILED_STATUS });
    expect(error.matcher).toMatchObject({ name: "answers 201", type: "equal", entityType: "status" });
  });
});

test("a test case run mocks the chain's outbound call, and a plain call to the same chain reaches the real endpoint", { tag: ["@testing-service", "@engine", "@tier1"] }, async ({ testingService, request, env, run }) => {
  const chain = seedChain(readCorpusState(), "http-out");
  const sender = { chainId: chain.id, elementId: chain.elements["HTTP Sender"] };
  const trigger = { chainId: chain.id, elementId: chain.elements["HTTP Trigger"] };
  const marker = tokenized(run, "runs-mocked");
  await testingService.withMock({ name: marker, reference: sender, response: { status: 200, body: JSON.stringify({ mocked: marker }) } }, async () => {
    await testingService.withTestCases([{
      name: marker,
      enabled: true,
      trigger,
      method: "POST",
      body: "{}",
      headers: [{ name: "Content-Type", value: "application/json" }],
      rules: [{ name: "answered by the mock", type: "contain", entityType: "body", value: `"mocked":"${marker}"` }],
    }], async ([testCase], start) => {
      const { caseRun, errors } = await testingService.runOnce(start, testCase.id);
      expect(caseRun.status).toBe("finished");
      expect(errors, "the case run's outbound call was not mocked").toEqual([]);
    });

    // The same chain, with the mock still registered, called with no `external-session-cip-id`.
    const live = await request.post(env.chainUrl(chain.contextPath), {
      headers: { "Content-Type": "application/json" },
      data: {},
    });
    const answered = await live.text();
    expect(answered, "a plain call was mocked").not.toContain(marker);
    // The real endpoint is the catalog's health document, whatever status it reports.
    expect(answered, "a plain call did not reach the real endpoint").toMatch(/"status":"[A-Z_]+"/);
  });
});

test("a case run is canceled alone or in bulk while pending, and a claimed one runs to the end", { tag: ["@testing-service", "@engine", "@tier2"] }, async ({ testingService, run }) => {
  const requests = [slow(tokenized(run, "runs-cr-slow")), ...["a", "b", "c", "d"].map((what) => passing(tokenized(run, `runs-cr-${what}`)))];
  await testingService.withTestCases(requests, async (cases, start) => {
    const runId = await start(cases.map((each) => each.id));
    const [held, first, second, third, kept] = await awaitFirstRunning(testingService, runId, 5);
    expect([first, second, third, kept].map((each) => each.status)).toEqual(["pending", "pending", "pending", "pending"]);

    await testingService.cancelCaseRun(first.id);
    await testingService.cancelCaseRuns([second.id, third.id, held.id]);

    const settled = byOrdinal(await testingService.awaitCaseRuns(runId, 5));
    expect(settled.map((each) => [each.id, each.status])).toEqual([
      [held.id, "finished"],
      [first.id, "canceled"],
      [second.id, "canceled"],
      [third.id, "canceled"],
      [kept.id, "finished"],
    ]);
    expect(settled[1]).toMatchObject({ start: null, finish: null, sessionId: null });
    expect((await testingService.getTestsRun(runId)).status).toBe("canceled");

    for (const path of [`/api/v1/test-case-runs/${first.id}`, "/api/v1/test-case-runs"]) {
      const refused = await testingService.raw("delete", path, { data: [first.id] });
      expect(refused.status(), `DELETE ${path}`).toBe(405);
    }
    expect((await testingService.getCaseRun(first.id)).status).toBe("canceled");
  });
});

test("a test run is canceled alone or in bulk, and deleted alone or in bulk with its case runs", { tag: ["@testing-service", "@engine", "@tier2"] }, async ({ testingService, run }) => {
  const requests = [slow(tokenized(run, "runs-tr-slow")), passing(tokenized(run, "runs-tr-queued"))];
  await testingService.withTestCases(requests, async (cases, start) => {
    const ids = cases.map((each) => each.id);
    // Every test run this case starts, with its case runs.
    const caseRuns = new Map<string, TestCaseRun[]>();
    const alone = await start(ids);
    caseRuns.set(alone, await awaitFirstRunning(testingService, alone, 2));
    await testingService.cancelTestsRun(alone);

    const together = [await start(ids), await start(ids)];
    for (const runId of together) caseRuns.set(runId, await awaitFirstRunning(testingService, runId, 2));
    await testingService.cancelTestsRuns(together);

    const started = [...caseRuns.keys()];

    for (const runId of started) {
      const settled = byOrdinal(await testingService.awaitCaseRuns(runId, 2));
      expect(settled.map((each) => each.status), `test run ${runId}`).toEqual(["finished", "canceled"]);
      expect(await testingService.getTestsRun(runId), `test run ${runId}`).toMatchObject({ status: "canceled", testCases: 2, errors: 0 });
    }

    await testingService.deleteTestsRun(alone);
    await testingService.deleteTestsRuns(together);
    for (const [runId, runsOfIt] of caseRuns) {
      expect((await testingService.raw("get", `/api/v1/tests-runs/${runId}`)).status(), `test run ${runId}`).toBe(404);
      for (const caseRun of runsOfIt) {
        const gone = await testingService.raw("get", `/api/v1/test-case-runs/${caseRun.id}`);
        expect(gone.status(), `case run ${caseRun.id}`).toBe(404);
      }
    }
    expect(await testingService.testsRunsWithIds(started)).toEqual([]);
  });
});

test("a run is started again whole from a test run, or in part from selected case runs", { tag: ["@testing-service", "@engine", "@tier2"] }, async ({ testingService, run }) => {
  const requests = ["a", "b", "c"].map((what) => passing(tokenized(run, `runs-again-${what}`)));
  await testingService.withTestCases(requests, async (cases, start) => {
    const [a, b, c] = cases;
    const original = await start([a.id, b.id, c.id]);
    const [runA, , runC] = byOrdinal(await testingService.awaitCaseRuns(original, 3));

    const whole = await start([original], "tests_runs");
    expect(whole).not.toBe(original);
    const repeated = byOrdinal(await testingService.awaitCaseRuns(whole, 3));
    expect(repeated.map((each) => [each.testCaseId, each.ordinal, each.status])).toEqual([
      [a.id, 1, "finished"],
      [b.id, 2, "finished"],
      [c.id, 3, "finished"],
    ]);

    // The selection is re-run in the order it was listed, not in the order it first ran.
    const part = await start([runC.id, runA.id], "test_case_runs");
    const selected = byOrdinal(await testingService.awaitCaseRuns(part, 2));
    expect(selected.map((each) => [each.testCaseId, each.ordinal, each.status])).toEqual([
      [c.id, 1, "finished"],
      [a.id, 2, "finished"],
    ]);

    const unknown = await testingService.raw("post", "/api/v1/tests-runs/create?from=test_suites", { data: [original] });
    expect(unknown.status()).toBe(400);
    expect((await unknown.json()).errorMessage).toBe("unknown entity type: test_suites");
  });
});

test("the errors of a case run and the run exports carry the rule that failed", { tag: ["@testing-service", "@engine", "@tier2"] }, async ({ testingService, run }) => {
  const requests = [
    passing(tokenized(run, "runs-csv-pass")),
    failing(tokenized(run, "runs-csv-fail")),
    failing(tokenized(run, "runs-csv-accepted"), "202"),
  ];
  await testingService.withTestCases(requests, async ([pass, fail, accepted], start) => {
    const first = await start([pass.id, fail.id]);
    const second = await start([accepted.id]);
    const [passRun, failRun] = byOrdinal(await testingService.awaitCaseRuns(first, 2));
    const [againRun] = await testingService.awaitCaseRuns(second, 1);
    const ruleId = fail.responseValidationRules?.[0].id ?? "";
    const acceptedRuleId = accepted.responseValidationRules?.[0].id ?? "";

    // Without `withMatchers` the error names its rule by id alone.
    const [bare] = await testingService.errorsOf(failRun.id, false);
    expect(bare).toMatchObject({ testCaseRunId: failRun.id, matcherId: ruleId, matcher: null, message: FAILED_STATUS });
    const [withRule] = await testingService.errorsOf(failRun.id);
    expect(withRule.id).toBe(bare.id);
    expect(withRule.matcher).toMatchObject({ id: ruleId, name: "answers 201" });
    const [again] = await testingService.errorsOf(againRun.id);
    // Two errors with different rules, so each row names the error it was exported for. The query
    // behind the export has no ORDER BY, so the rows are compared as a set.
    const [header, ...errorRows] = await testingService.exportErrors([bare.id, again.id]);
    expect(header).toEqual(["Rule", "Message"]);
    expect(errorRows.sort()).toEqual([
      ["answers 201", FAILED_STATUS],
      ["answers 202", FAILED_ACCEPTED],
    ]);

    const chainId = pass.triggerReference.chainId;
    const failures = new Map([
      [fail.id, [ruleId, "answers 201", FAILED_STATUS]],
      [accepted.id, [acceptedRuleId, "answers 202", FAILED_ACCEPTED]],
    ]);
    const row = (caseRun: TestCaseRun, testCase: TestCase) => {
      const [rule, ruleName, message] = failures.get(testCase.id) ?? ["", "", ""];
      return [
        caseRun.testsRunId, caseRun.id, chainId, testCase.id, testCase.name, "",
        expect.stringMatching(GO_TIME), expect.stringMatching(GO_TIME), "finished", caseRun.sessionId,
        rule ? "1" : "0", rule, ruleName, "", message,
      ];
    };
    // The export query has no ORDER BY (`exportToCsv` passes no sorting field), and the row order
    // changed between runs, so both sides are put in case run id order here.
    const sorted = (...rows: [TestCaseRun, TestCase][]) =>
      [...rows].sort(([a], [b]) => a.id.localeCompare(b.id)).map(([caseRun, testCase]) => row(caseRun, testCase));
    const byCaseRunId = ([header, ...rows]: string[][]) => [header, ...rows.sort((a, b) => a[1].localeCompare(b[1]))];

    expect(byCaseRunId(await testingService.exportTestsRun(first))).toEqual([EXPORT_HEADER, ...sorted([passRun, pass], [failRun, fail])]);
    expect(byCaseRunId(await testingService.exportTestsRuns([first, second]))).toEqual([
      EXPORT_HEADER,
      ...sorted([passRun, pass], [failRun, fail], [againRun, accepted]),
    ]);
    expect(await testingService.exportCaseRun(failRun.id)).toEqual([EXPORT_HEADER, row(failRun, fail)]);
    expect(byCaseRunId(await testingService.exportCaseRuns([passRun.id, againRun.id]))).toEqual([
      EXPORT_HEADER,
      ...sorted([passRun, pass], [againRun, accepted]),
    ]);
  });
});
