/**
 * The testing service as a target: test cases, and the response rules a run checks.
 *
 * The routes do not follow the usual verbs. `POST /test-cases` is the listing, `POST /test-cases/create`
 * creates, `POST /test-cases/{id}` updates, and `DELETE /test-cases` deletes the ids in its body.
 *
 * The file sits in `runtime` because a run calls the deployed chain, and a response rule is read only
 * at run time. Every case runs against the seeded `http-echo` chain, which answers 200 with the body
 * it was sent and copies the request headers and query parameters onto the response.
 *
 * A response rule reads one of three parts: `body`, `header` or `status`. The two other entity types
 * read the `Testing-Service-Context` header, which a response to a test case never carries, so over a
 * response they find nothing. The predicates are the nine of
 * `testing-service/internal/matching/matcher_predicate_factory.go`, not the list-filter conditions in
 * `internal/model/select.go`. Each predicate case carries one rule that holds and one that fails, and
 * the run has to record an error on the second alone, with the message that predicate writes.
 */
import { test, expect } from "../../support/fixtures.js";
import { UUID } from "../../support/absent.js";
import { triggerOf } from "../../support/corpus.js";
import { tokenized } from "../../support/run.js";
import { element } from "../../support/sessions.js";
import { leftBehind } from "../../support/teardown.js";
import type { ResponseRule, TestCaseRequest } from "../../support/testing-service.js";

test("a test case is created, read, listed, updated, and deleted against a seeded trigger", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const trigger = triggerOf("http-echo");
  const name = tokenized(run, "case-crud");
  const request: TestCaseRequest = {
    name,
    enabled: true,
    trigger,
    method: "POST",
    body: '{"ping":1}',
    headers: [{ name: "Content-Type", value: "application/json" }],
    queryParameters: [{ name: "channel", value: "web" }],
    rules: [{ name: "answers ok", type: "equal", entityType: "status", value: "200" }],
  };
  const created = await testingService.createTestCase(request);
  let deleted = false;
  try {
    const stored = {
      name,
      enabled: true,
      triggerReference: trigger,
      requestSettings: {
        method: "POST",
        queryParameters: [{ name: "channel", value: "web" }],
        message: { body: '{"ping":1}', headers: [{ name: "Content-Type", value: "application/json" }] },
      },
      responseValidationRules: [
        { name: "answers ok", enabled: true, type: "equal", entityType: "status", entityName: null, parameters: [{ name: "value", value: "200" }] },
      ],
    };
    expect(created.id).toMatch(UUID);
    expect(created).toMatchObject(stored);
    const ruleId = created.responseValidationRules?.[0].id;
    expect(ruleId).toMatch(UUID);
    // A read answers the listing's view of the case, which adds the rule counts.
    const read = await testingService.getTestCase(created.id);
    expect(read).toMatchObject({ ...stored, id: created.id });
    expect(read.responseValidationRules?.[0].id).toBe(ruleId);

    const listed = await testingService.testCasesNamed(name);
    expect(listed.map((each) => [each.id, each.validationRuleCount, each.enabledRuleCount])).toEqual([[created.id, 1, 1]]);

    const renamed = tokenized(run, "case-crud-renamed");
    const updated = await testingService.updateTestCase(created.id, {
      ...request,
      name: renamed,
      method: "PUT",
      body: '{"ping":2}',
      headers: [{ name: "X-E2e-Updated", value: "yes" }],
      queryParameters: [{ name: "channel", value: "batch" }],
      rules: [
        { name: "echoes the ping", type: "contain", entityType: "body", value: '"ping":2' },
        { name: "answers created", type: "equal", entityType: "status", value: "201", enabled: false },
      ],
    });
    expect(updated.id).toBe(created.id);
    const reread = await testingService.getTestCase(created.id);
    expect(reread).toMatchObject({
      name: renamed,
      requestSettings: {
        method: "PUT",
        queryParameters: [{ name: "channel", value: "batch" }],
        message: { body: '{"ping":2}', headers: [{ name: "X-E2e-Updated", value: "yes" }] },
      },
    });
    expect(reread.responseValidationRules?.map((rule) => [rule.name, rule.enabled, rule.type])).toEqual([
      ["echoes the ping", true, "contain"],
      ["answers created", false, "equal"],
    ]);
    expect(await testingService.testCasesNamed(name).then((rows) => rows.map((each) => each.name))).toEqual([renamed]);
    expect(
      (await testingService.testCasesNamed(renamed)).map((each) => [each.validationRuleCount, each.enabledRuleCount]),
    ).toEqual([[2, 1]]);

    const removal = await testingService.raw("delete", `/api/v1/test-cases/${created.id}`);
    expect(removal.status()).toBe(204);
    deleted = true;
    const gone = await testingService.raw("get", `/api/v1/test-cases/${created.id}`);
    expect(gone.status()).toBe(404);
    expect((await gone.json()).errorMessage).toBe(`Test case ${created.id} not found.`);
  } finally {
    if (!deleted) await testingService.deleteTestCase(created.id).catch(leftBehind(`test case ${name}`));
  }
});

test("a bulk delete removes the test cases it lists and keeps the rest", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  const trigger = triggerOf("http-echo");
  const requests = ["bulk-a", "bulk-b", "bulk-kept"].map((what) => ({ name: tokenized(run, what), enabled: true, trigger, method: "POST" }));
  await testingService.withTestCases(requests, async ([first, second, kept]) => {
    await testingService.deleteTestCases([first.id, second.id]);
    for (const { id } of [first, second]) {
      expect((await testingService.raw("get", `/api/v1/test-cases/${id}`)).status(), `test case ${id}`).toBe(404);
    }
    expect((await testingService.getTestCase(kept.id)).name).toBe(kept.name);
  });
});

test("a run sends the body, headers, and query parameters the test case defines", { tag: ["@testing-service", "@engine", "@sessions", "@tier1"] }, async ({ testingService, sessions, run }) => {
  const trigger = triggerOf("http-echo");
  const name = tokenized(run, "case-request");
  const body = JSON.stringify({ ping: name });
  const request: TestCaseRequest = {
    name,
    enabled: true,
    trigger,
    method: "POST",
    body,
    headers: [
      { name: "Content-Type", value: "application/json" },
      { name: "X-E2e-Probe", value: name },
    ],
    queryParameters: [{ name: "channel", value: "web" }],
  };
  await testingService.withTestCases([request], async ([testCase], start) => {
    const { caseRun, errors } = await testingService.runOnce(start, testCase.id);
    expect(caseRun.status).toBe("finished");
    expect(errors).toEqual([]);

    // The session is recorded under the id the run sent as `external-session-cip-id`.
    const session = await sessions.byExternalId(caseRun.sessionId ?? "", { elements: 3 });
    expect(session.chainId).toBe(trigger.chainId);
    const received = element(session, "HTTP Trigger");
    expect(received?.bodyBefore).toBe(body);
    expect(received?.headersBefore).toMatchObject({
      "x-e2e-probe": name,
      "Content-Type": "application/json",
      CamelHttpMethod: "POST",
      CamelHttpQuery: "channel=web",
    });
  });
});

// ---------------------------------------------------------------------------
// Response rules, one case per predicate
// ---------------------------------------------------------------------------

/** The body every predicate case sends, and `http-echo` answers with. */
const ECHOED = { case: "e2e-matchers", count: 2 };
const ECHOED_BODY = JSON.stringify(ECHOED);
/** A request header `http-echo` copies onto its response. */
const ECHOED_HEADER = { name: "x-e2e-echo", value: "e2e-header" };
/** A response header `http-echo` never writes. */
const ABSENT = "x-e2e-absent";

interface PredicateCase {
  predicate: ResponseRule["type"];
  holds: Omit<ResponseRule, "name" | "type">;
  fails: Omit<ResponseRule, "name" | "type">;
  /** What the predicate writes when the failing rule does not hold. */
  message: string | RegExp;
}

// Each predicate reads the part that shows it best, so the three parts a response rule can reach are
// each read by a rule that holds and by a rule that fails.
const PREDICATE_CASES: PredicateCase[] = [
  { predicate: "empty", holds: { entityType: "header", entityName: ABSENT }, fails: { entityType: "body" }, message: "not empty" },
  { predicate: "exist", holds: { entityType: "header", entityName: ECHOED_HEADER.name }, fails: { entityType: "header", entityName: ABSENT }, message: "does not exist" },
  { predicate: "equal", holds: { entityType: "status", value: "200" }, fails: { entityType: "status", value: "201" }, message: "expected: '201', got: '200'" },
  { predicate: "contain", holds: { entityType: "body", value: '"count":2' }, fails: { entityType: "body", value: '"count":3' }, message: `'${ECHOED_BODY}' doesn't contain '"count":3'` },
  { predicate: "match", holds: { entityType: "body", parameters: { pattern: '^\\{"case":"[^"]+","count":\\d+\\}$' } }, fails: { entityType: "body", parameters: { pattern: "^\\[" } }, message: `'${ECHOED_BODY}' doesn't match '^\\['` },
  { predicate: "start_with", holds: { entityType: "header", entityName: ECHOED_HEADER.name, value: "e2e-" }, fails: { entityType: "header", entityName: ECHOED_HEADER.name, value: "header" }, message: "'e2e-header' doesn't start with 'header'" },
  { predicate: "end_with", holds: { entityType: "body", value: '"count":2}' }, fails: { entityType: "body", value: '"count":3}' }, message: `'${ECHOED_BODY}' doesn't end with '"count":3}'` },
  {
    predicate: "match_json_schema",
    holds: { entityType: "body", parameters: { schema: JSON.stringify({ type: "object", required: ["case", "count"], properties: { count: { type: "integer" } } }) } },
    fails: { entityType: "body", parameters: { path: "$.count", schema: JSON.stringify({ type: "string" }) } },
    message: /^jsonschema validation failed[\s\S]*got number, want string/,
  },
  {
    predicate: "match_json",
    holds: { entityType: "body", parameters: { sample: ECHOED_BODY } },
    fails: { entityType: "body", parameters: { sample: JSON.stringify({ ...ECHOED, count: 3 }) } },
    message: 'value does not match the sample, patch: [{"value":2,"op":"replace","path":"/count"}]',
  },
];

for (const each of PREDICATE_CASES) {
  test(`a ${each.predicate} response rule holds for a matching response and fails for another`, { tag: ["@testing-service", "@engine", "@tier2"] }, async ({ testingService, run }) => {
    const request: TestCaseRequest = {
      name: tokenized(run, `rule-${each.predicate}`),
      enabled: true,
      trigger: triggerOf("http-echo"),
      method: "POST",
      body: ECHOED_BODY,
      headers: [{ name: "Content-Type", value: "application/json" }, ECHOED_HEADER],
      rules: [
        { name: "holds", type: each.predicate, ...each.holds },
        { name: "fails", type: each.predicate, ...each.fails },
      ],
    };
    await testingService.withTestCases([request], async ([testCase], start) => {
      const { caseRun, errors } = await testingService.runOnce(start, testCase.id);
      expect(caseRun.status).toBe("finished");
      // A trigger that could not be called records an error with no matcher, which this also catches.
      expect(errors.map((error) => error.matcher?.name ?? null), JSON.stringify(errors)).toEqual(["fails"]);
      if (typeof each.message === "string") expect(errors[0].message).toBe(each.message);
      else expect(errors[0].message).toMatch(each.message);
    });
  });
}
