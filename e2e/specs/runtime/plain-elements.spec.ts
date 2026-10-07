/**
 * The elements with no axis that tier 1 did not already run: file write and read, XSLT, and split
 * async. `header-modification`, `mapper-2`, `reuse`, `reuse-reference`, `chain-trigger-2` and
 * `chain-call-2` are covered by the tier-1 specs, and `checkpoint` by `checkpoint-retry.spec.ts`.
 *
 * - `file-write` and `file-read` touch the engine container's own file system, which no spec may
 *   reach, so they are a pair: one chain writes a file named after the call, the other reads it back.
 * - `xslt` reads its stylesheet off the same directory, so its chain writes one with a `file-write`
 *   step first. The XSLT step is traced after the steps that prepare it.
 * - `split-async-2` answers without waiting for its branches, which run in parallel, so the branches
 *   are compared as a set. One branch sleeps, and the answer has to arrive before that branch ends.
 *   A micro domain can record a branch step under the wrong parent: pinned.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { MICRO_XSLT, MISPLACED_BRANCH_STEPS } from "../../support/known-defect.js";
import { readUntil } from "../../support/poll.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS, SESSION_TIMEOUT, trace, type RecordedSession, type Sessions, type TracedElement } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

/** The header both file fixtures name the file after. */
const FILE_HEADER = "e2e-file";

test("file-write then file-read: a file one chain writes, the other reads back into its response", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("file-write");
  covers("file-read");

  const corpus = readCorpusState();
  const file = callToken("file");
  const content = { written: file };

  const write = await callChain(request, env.chainUrl(seedChain(corpus, "file-write").contextPath), {
    headers: { [FILE_HEADER]: file },
    data: content,
  });
  expect(write.response.status()).toBe(200);
  const written = await sessions.byExternalId(write.token, { elements: 3 });
  expect(elementNames(written)).toEqual([...HTTP_TRIGGER_STEPS, "File Write"]);
  expect(element(written, "File Write")?.executionStatus).toBe("COMPLETED_NORMALLY");

  const read = await callChain(request, env.chainUrl(seedChain(corpus, "file-read").contextPath), {
    headers: { [FILE_HEADER]: file },
    data: {},
  });
  expect(read.response.status()).toBe(200);
  expect(JSON.parse(await read.response.text())).toEqual(content);
  const reading = await sessions.byExternalId(read.token, { elements: 3 });
  expect(elementNames(reading)).toEqual([...HTTP_TRIGGER_STEPS, "File Read"]);
  // The step hands on the file itself, and its name is the one the writer chose.
  expect(element(reading, "File Read")?.bodyAfter).toMatch(new RegExp(`GenericFile\\[/tmp/chain_tmp/e2e-[a-z0-9]+-${file}\\]`));
});

const BEFORE_XSLT = [...HTTP_TRIGGER_STEPS, "Keep Request", "Write Stylesheet", "Restore Request"];

/** One call through the XSLT fixture, with an order id only this call sends. */
async function transform(request: APIRequestContext, env: Env, sessions: Sessions): Promise<RecordedSession> {
  const id = callToken("order");
  const since = new Date(Date.now() - 1_000).toISOString();
  const call = await callChain(request, env.chainUrl(seedChain(readCorpusState(), "xslt").contextPath), {
    headers: { "Content-Type": "application/xml" },
    data: `<order id="${id}"/>`,
  });
  // A failed call names the endpoint the engine could not resolve, so the micro pin can tell the
  // missing component's 500 from another one. The response body is the request echoed back.
  const unresolved =
    call.response.status() === 200
      ? ""
      : ((await env.engineLogs(since))
          .split("\n")
          .find((line) => line.includes("No endpoint could be found for: xslt:")) ?? "no unresolved xslt endpoint in the engine log");
  expect(call.response.status(), `the xslt chain failed: ${unresolved}`).toBe(200);
  expect(await call.response.text()).toBe(`transformed ${id}`);
  return sessions.byExternalId(call.token, { elements: 5 });
}

test("xslt transforms the body with a stylesheet read off the engine's file system", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_XSLT.title);
  covers("xslt");

  const session = await transform(request, env, sessions);
  // Filtered: the XSLT step's own entry belongs to the case below.
  expect(elementNames(session).filter((name) => name !== "XSLT")).toEqual(BEFORE_XSLT);
});

test("xslt: the transformation step is recorded in the trace", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions, engineKind }) => {
  test.fail(engineKind === "micro", MICRO_XSLT.title);
  const session = await transform(request, env, sessions);
  // Read until the step arrives, so a step indexed after the others is not taken for a missing one.
  const names = await readUntil(
    async () => elementNames(await sessions.session(session.id)),
    (read) => read.includes("XSLT"),
    SESSION_TIMEOUT,
    1_000,
  );
  expect(names).toEqual([...BEFORE_XSLT, "XSLT"]);
});

/** How long the fixture's second branch sleeps. */
const SLEEP_MS = 3_000;

const BRANCHES: Record<string, { script: string; body: unknown }> = {
  "First Async Branch": { script: "First Async Script", body: { branch: "first" } },
  "Second Async Branch": { script: "Second Async Script", body: { branch: "second" } },
};

test("split-async-2 answers from the step after it, and each async branch runs its own step", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("split-async-2");
  covers("async-split-element-2");

  const sent = Date.now();
  const call = await callChain(request, env.chainUrl(seedChain(readCorpusState(), "split-async").contextPath), { data: {} });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({ split: "answered" });
  expect(Date.now() - sent, "the answer waited for the sleeping branch").toBeLessThan(SLEEP_MS);

  const session = await sessions.byExternalId(call.token, { elements: 8 });
  // Checked first, because a misplaced step also breaks the tree assertions below it.
  const steps = trace(session);
  const misplaced = Object.entries(BRANCHES).flatMap(([branch, { script }]) => {
    const step = element(session, script);
    const parent = step && (steps.find((each) => each.elementId === step.parentElement)?.elementName ?? "no parent");
    return parent === undefined || parent === branch ? [] : [`"${script}" under ${parent}`];
  });
  expect(misplaced, `${MISPLACED_BRANCH_STEPS} ${misplaced.join(", ")}`).toEqual([]);
  expect((session.sessionElements ?? []).map((each) => each.elementName)).toEqual(["HTTP Trigger", "Split Async", "After Split"]);

  const split = element(session, "Split Async") as TracedElement;
  const branches = split.children ?? [];
  expect(branches.map((each) => each.elementName).sort()).toEqual(Object.keys(BRANCHES));
  for (const branch of branches) {
    const expected = BRANCHES[branch.elementName];
    const steps = branch.children ?? [];
    expect(steps.map((each) => each.elementName), branch.elementName).toEqual([expected.script]);
    expect(steps[0].executionStatus).toBe("COMPLETED_NORMALLY");
    expect(JSON.parse(steps[0].bodyAfter ?? "null")).toEqual(expected.body);
  }
  expect(trace(session).every((each) => each.executionStatus === "COMPLETED_NORMALLY")).toBe(true);
  const finished = (name: string): number => Date.parse(element(session, name)?.finished ?? "");
  expect(finished("After Split"), "the step after the split ended after the sleeping branch").toBeLessThan(finished("Second Async Script"));
});
