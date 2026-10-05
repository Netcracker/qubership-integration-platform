/**
 * Sessions-management, everything a spec can scope to the chains it created itself.
 *
 * Thirteen of the module's fourteen operations are asserted here. The fourteenth,
 * `DELETE /v1/sessions` — the bare form — wipes every session in OpenSearch, so it is global by
 * construction and lives in `specs/global/sessions-destructive.spec.ts`.
 *
 * `GET /v1/sessions/external-id/{id}` is the correlation lookup `support/sessions.ts` was built
 * around, and every case below uses it to find the session it just produced. The last case asserts
 * its own contract on a call to the seeded `http-echo` chain.
 *
 * The eight shapes measured against the stack, each one an asymmetry a caller has to know:
 *
 * - **The two search mappings are one method.** `findAllByFilter` delegates to
 *   `findByFilter(null, …)`, so `POST /v1/sessions` and `POST /v1/sessions/chains/{id}` differ in
 *   exactly two things: the chain predicate, and what the bare form does afterwards — it asks the
 *   catalog for the *current* names of the chains it found and overwrites `chainName` with them.
 *   Rename a chain and the two answers disagree, which is what the second case pins.
 * - **A filter clause spells its column `feature`.** The catalog's own `FilterRequestDTO` spells
 *   the same idea `column` (`@JsonProperty("column")`), and a sessions body written that way is
 *   accepted by Jackson and dereferenced as null one layer down — a **500**, not a 400.
 * - **`CONTAINS` matches a whole hyphenated name, in any case.** `chainName` is a `keyword` field
 *   on the index — measured on the live mapping, not inferred — so the unanalyzed `*value*`
 *   wildcard sees the whole name. This is the regression test for PR #764 (issue #762), where the
 *   field was `text` and the same query matched nothing.
 * - **`offset` in the answer is the marker for the next request**, `offset + rows`, never a total
 *   and never an echo. A full page and a last page are indistinguishable without asking again.
 * - **Four deletes refresh the index and one does not.** `deleteBySessionId` passes
 *   `refresh=false` (`SessionService.java:151`); the chain deletes, the bulk delete and the
 *   wholesale delete all pass `true`. So a delete by id is visible when OpenSearch gets to it and
 *   every other delete is visible on the very next read, and the cases assert exactly that
 *   difference rather than polling everywhere.
 * - **An import clears `chainId` and sets `importedSession`.** `ImportService.importSessions` does
 *   both unconditionally, so a re-imported session is no longer reachable by chain — which is also
 *   why this file deletes its imported session by id rather than leaving it to the run-token sweep,
 *   whose only handle is the chain.
 * - **An import over a live id is refused, not merged.** `checkExisting` answers 409 naming the
 *   duplicate, so a round trip has to delete before it imports. That is rule 5 of the suite arriving
 *   from the other direction.
 * - **The external-id lookup is light unless asked.** `sessionElements` is null without
 *   `includeDetails=true`, and a token no session carries answers 404 with an error body naming it.
 *
 * One platform defect is pinned here as `test.fail()`, filed in `docs/product-defects.md`: a filter
 * clause omitting `feature` or `condition` answers 500 where it should answer 400.
 */
import { test, expect } from "../../support/fixtures.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { onlyTheKnownStatus } from "../../support/known-defect.js";
import {
  callChain,
  element,
  elementNames,
  type RecordedSession,
  type SessionPage,
  type Sessions,
  type TracedElement,
} from "../../support/sessions.js";
import {
  ENGINE_CASE_TIMEOUT,
  readCorpusState,
  SEED_LOGGING,
  type SeedChain,
  seedChain,
  waitForDeployed,
  waitForRoutes,
} from "../../support/corpus.js";
import { createScriptChain, SCRIPT_CHAIN_STEPS } from "../../support/deployable.js";
import { releaseChains } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";
import type { Env } from "../../env/index.js";
import type { APIRequestContext } from "@playwright/test";
import fs from "node:fs";
import { randomUUID } from "node:crypto";
import { RECORDED_SESSION_FIXTURE } from "../../fixtures/templating.js";
import { callToken, tokenized } from "../../support/run.js";

/** The step whose payload the element case reads. Unique in the chain, so the trace names it once. */
const PROBE_ELEMENT = "Probe Script";

/** What the probe writes into the body, so a payload read is unambiguous about which step it is. */
const PROBE_MARKER = "sessions-api-probe";

const PROBE_SCRIPT = `exchange.getMessage().setBody('{"probe":"${PROBE_MARKER}"}')`;

/** A chain of this spec's own, deployed, plus the sessions the case drove through it. */
interface Recorded {
  chain: SeedChain;
  sessions: RecordedSession[];
}

/** What a `record()` call is for, folded into one object so no caller transposes two strings. */
interface Recording {
  run: string;
  folderId: string;
  /** The suffix of the chain's name, which is also what a failure message names it by. */
  what: string;
  calls: number;
}

/**
 * Builds a chain, deploys it, calls it `calls` times, and answers the settled sessions.
 *
 * A chain per case rather than one shared one: every case here deletes sessions, and two cases
 * sharing a chain would be two cases deleting each other's evidence. The chain is appended to
 * `built` inside `createScriptChain`, before anything else can throw, so the caller's `finally`
 * releases its Consul key whether or not this function finished.
 */
async function record(
  catalog: Catalog,
  env: Env,
  request: APIRequestContext,
  sessions: Sessions,
  options: Recording,
  built: SeedChain[],
): Promise<Recorded> {
  const { run, folderId, what, calls } = options;
  const chain = await createScriptChain(
    catalog,
    run,
    {
      what: `sess-${what}`,
      parentId: folderId,
      script: PROBE_SCRIPT,
      scriptName: PROBE_ELEMENT,
      logging: SEED_LOGGING,
    },
    built,
  );
  const snapshot = await catalog.createSnapshot(chain.id);
  await catalog.deploy(chain.id, snapshot.id);
  await waitForDeployed(catalog, [chain]);
  await waitForRoutes(env, [chain]);

  const recorded: RecordedSession[] = [];
  for (let index = 0; index < calls; index++) {
    const call = await callChain(request, env.chainUrl(chain.contextPath), {
      data: { call: index },
    });
    expect(call.response.status(), `call ${index} of ${chain.name}`).toBe(200);
    recorded.push(await sessions.byExternalId(call.token, { elements: SCRIPT_CHAIN_STEPS }));
  }
  return { chain, sessions: recorded };
}

/** One step of a settled trace by its name, with a failure naming every step that was recorded. */
function stepNamed(session: RecordedSession, elementName: string): TracedElement {
  const found = element(session, elementName);
  expect(found, `${elementName} in a trace of ${elementNames(session).join(", ")}`).toBeDefined();
  return found!;
}

/** The ids of a page, sorted, which is how two id sets are compared without pinning an order. */
function idsOf(page: SessionPage): string[] {
  return page.sessions.map((each) => each.id).sort();
}

/**
 * Puts one session into the index without a chain, a deploy, or an engine.
 *
 * The document is `fixtures/sessions/recorded-session.json` — the verbatim answer to one external-id
 * lookup, which `specs/schema/sessions-helper.spec.ts` already reads — re-identified so the import
 * is a create rather than the 409 `checkExisting` answers for a live id. Every id has to move: the
 * elements are written under their own ids, so reusing them would overwrite the fixture's original
 * documents if they were ever on the stack.
 *
 * The caller deletes it. `ImportService` clears `chainId`, so the run-token sweep cannot.
 */
async function importOneSession(sessions: Sessions, run: string, what: string): Promise<string> {
  const document = JSON.parse(
    fs.readFileSync(RECORDED_SESSION_FIXTURE, "utf-8"),
  ) as RecordedSession;
  const id = randomUUID();
  const reidentify = (steps: readonly TracedElement[]): void => {
    for (const step of steps) {
      step.elementId = randomUUID();
      step.sessionId = id;
      if (step.children) reidentify(step.children);
    }
  };
  reidentify(document.sessionElements ?? []);
  document.id = id;
  document.externalSessionCipId = callToken();
  document.chainName = tokenized(run, `sess-${what}`);

  const response = await sessions.importSessions(
    `${what}.json`,
    Buffer.from(JSON.stringify([document]), "utf-8"),
  );
  expect(response.status(), "the fixture session was not imported").toBe(200);
  await expect
    .poll(() => sessions.exists(id), { message: "the imported fixture session never landed" })
    .toBe(200);
  return id;
}

/** The rows of a page that belong to one chain. Every global answer is narrowed through this. */
function ours(page: SessionPage, ids: readonly string[]): string[] {
  return page.sessions
    .map((each) => each.id)
    .filter((id) => ids.includes(id))
    .sort();
}

test("a chain's sessions are listed by chain, light, paged, and scoped to that chain", { tag: ["@sessions", "@catalog", "@engine", "@tier1"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      const mine = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "list", calls: 3 }, built);
      const ids = mine.sessions.map((each) => each.id).sort();

      const page = await sessions.search({}, { chainId: mine.chain.id });
      expect(idsOf(page), "the chain's own three sessions and nothing else").toEqual(ids);

      // The listing is light by construction: `EXCLUDE_FIELD_IN_SESSIONS` drops the eight payload
      // fields and `toPreview` carries no elements at all. A caller that wants a body asks the
      // element endpoint, which the payload case below covers.
      for (const row of page.sessions) {
        expect(row.sessionElements, "the listing carries no trace").toBeNull();
        expect(row.chainId).toBe(mine.chain.id);
        expect(row.chainName).toBe(mine.chain.name);
        expect(row.executionStatus).toBe("COMPLETED_NORMALLY");
        expect(row.loggingLevel).toBe(SEED_LOGGING.sessionsLoggingLevel);
        expect(row.duration).toBeGreaterThanOrEqual(0);
        expect(row.importedSession, "recorded by the engine, not imported").toBe(false);
        expect(Date.parse(row.started)).not.toBeNaN();
        expect(Date.parse(row.finished)).not.toBeNaN();
      }

      // `offset` is the marker for the next request rather than a total: it comes back as
      // `offset + rows`, so a page that filled and a page that ended read alike.
      expect(page.offset, "the marker is what the page held, from an offset of zero").toBe(3);

      const first = await sessions.search({}, { chainId: mine.chain.id, count: 2 });
      expect(first.sessions).toHaveLength(2);
      expect(first.offset).toBe(2);
      const second = await sessions.search({}, { chainId: mine.chain.id, offset: 2, count: 2 });
      expect(second.sessions).toHaveLength(1);
      expect(second.offset, "one row read from an offset of two").toBe(3);
      expect([...idsOf(first), ...idsOf(second)].sort(), "the two pages partition the three").toEqual(ids);

      const past = await sessions.search({}, { chainId: mine.chain.id, offset: 3, count: 2 });
      expect(past.sessions, "an offset past the end is an empty page").toEqual([]);
      expect(past.offset, "and the marker does not advance past nothing").toBe(3);

      // `getSessions` guards both bounds before it queries, and answers an empty page rather than
      // a refusal — so a caller cannot tell a rejected page size from a chain with no sessions.
      expect(
        (await sessions.search({}, { chainId: mine.chain.id, count: 0 })).sessions,
        "a page of zero rows is served as an empty page",
      ).toEqual([]);
      expect(
        (await sessions.search({}, { chainId: mine.chain.id, offset: -1 })).sessions,
        "a negative offset is served as an empty page",
      ).toEqual([]);

      const nobody = await sessions.search({}, { chainId: ABSENT_UUID });
      expect(nobody.sessions, "a chain id nothing carries is an empty page, not a 404").toEqual([]);
      expect(nobody.offset).toBe(0);

      // The sort column is validated against a list the refusal itself names, which is the one
      // 400 in this module that carries the module's own `ExceptionDTO` rather than RFC 9457.
      const refused = await sessions.rawSearch({}, { chainId: mine.chain.id, sortColumn: "nope" });
      expect(refused.status(), "an unsortable column is refused").toBe(400);
      expect(((await refused.json()) as { errorMessage: string }).errorMessage).toContain(
        "Can't sort results on this column",
      );

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("the chain name filter matches a full hyphenated name in any case, and only the bare search re-resolves the name", { tag: ["@sessions", "@catalog", "@engine", "@tier1"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      const mine = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "name", calls: 2 }, built);
      const ids = mine.sessions.map((each) => each.id).sort();
      const name = mine.chain.name;

      // The regression test for PR #764 (issue #762). The name is hyphenated four times over —
      // `e2e-{run}-sess-name` — and before the fix `chainName` was an analyzed `text` field, so the
      // unanalyzed `*name*` wildcard matched none of its tokens and the search answered nothing.
      // A spec reads "nothing" as "isolated", which is why this assertion has to name the ids.
      const byFullName = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: name }],
      }, { count: 100 });
      expect(ours(byFullName, ids), "the full hyphenated name matches").toEqual(ids);

      // Case folding was the second half of the same fix: the analyzer used to lower-case for free,
      // and the keyword field does not, so `caseInsensitive(true)` had to be added to the wildcard.
      const upper = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: name.toUpperCase() }],
      }, { count: 100 });
      expect(ours(upper, ids), "the same name upper-cased matches the same rows").toEqual(ids);

      // A fragment spanning a hyphen, which is the case the UI produces when a user pastes part of
      // a name into the Chain filter.
      const fragment = name.slice("e2e-".length);
      const bySpan = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: fragment }],
      }, { count: 100 });
      expect(ours(bySpan, ids), `a fragment spanning a hyphen (${fragment}) matches`).toEqual(ids);

      const byPrefix = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "STARTS_WITH", value: name }],
      }, { count: 100 });
      expect(ours(byPrefix, ids), "STARTS_WITH takes the whole name too").toEqual(ids);

      // The negation is asserted because it used to return the wrong answer rather than an empty
      // one: excluding nothing, it handed back the very chain it was asked to exclude.
      //
      // Narrowed by a second clause on the same column, because a bare negation reads one page of
      // the whole index: past a page of non-matching rows this case's own would fall off it, and
      // the assertion would hold by truncation rather than by exclusion. Both clauses are `must`,
      // so the page is this run's rows minus this case's chain.
      const excluded = await sessions.search({
        filterRequestList: [
          { feature: "CHAIN_NAME", condition: "CONTAINS", value: `e2e-${run}` },
          { feature: "CHAIN_NAME", condition: "DOES_NOT_CONTAIN", value: name },
        ],
      }, { count: 1000 });
      expect(ours(excluded, ids), "DOES_NOT_CONTAIN excludes the name it is given").toEqual([]);

      // `STATUS` is a second column over the same rows, and it is what says the switch reaches more
      // than `CHAIN_NAME`: both clauses are `must`, so the pair narrows rather than widens.
      const wrongStatus = await sessions.search({
        filterRequestList: [
          { feature: "CHAIN_NAME", condition: "CONTAINS", value: name },
          { feature: "STATUS", condition: "IN", value: "COMPLETED_WITH_ERRORS" },
        ],
      }, { count: 100 });
      expect(ours(wrongStatus, ids), "two clauses narrow: no probe session failed").toEqual([]);

      // The two mappings disagree the moment the chain is renamed, and that is the whole of what
      // separates them: the bare form asks the catalog for the current names of the chains it found
      // and overwrites what the index recorded, while the chain-scoped form serves the index.
      const renamed = `${name}-renamed`;
      await catalog.updateChain(mine.chain.id, renamed);
      const scoped = await sessions.search({}, { chainId: mine.chain.id });
      expect(idsOf(scoped)).toEqual(ids);
      expect(
        scoped.sessions.map((each) => each.chainName),
        "the chain-scoped search serves the name the engine recorded",
      ).toEqual([name, name]);

      const bare = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: name }],
      }, { count: 100 });
      expect(
        bare.sessions.filter((each) => ids.includes(each.id)).map((each) => each.chainName),
        "the bare search re-resolves the name from the catalog",
      ).toEqual([renamed, renamed]);
      // And the filter still matched, which says the predicate runs against the recorded name
      // rather than against the one the answer carries.
      expect(ours(bare, ids)).toEqual(ids);

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("a session deletes by id and by id list, and HEAD reports which are gone", { tag: ["@sessions", "@engine", "@tier2"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      const mine = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "delete", calls: 3 }, built);
      const [first, second, third] = mine.sessions.map((each) => each.id);

      for (const id of [first, second, third]) {
        expect(await sessions.exists(id), "every session answers HEAD before anything is deleted").toBe(200);
      }
      expect(
        await sessions.exists(ABSENT_UUID),
        "and an id nothing carries answers 404 — which is what makes the 200s mean something",
      ).toBe(404);

      const single = await sessions.deleteSession(first);
      expect(single.status(), "the single delete answers 200").toBe(200);
      // Polled, and only here: `deleteBySessionId` is the one delete that passes `refresh=false`,
      // so the document is gone from the index when OpenSearch next refreshes rather than by the
      // time the call answers. The poll can only end one way — nothing re-creates a session.
      await expect
        .poll(() => sessions.exists(first), {
          message: `the session deleted by id never disappeared from the index`,
        })
        .toBe(404);
      expect(await sessions.exists(second), "the siblings are untouched").toBe(200);
      expect(await sessions.exists(third)).toBe(200);

      // Delete by query over an id nothing carries: nothing matches, and nothing is reported.
      expect(
        (await sessions.deleteSession(ABSENT_UUID)).status(),
        "deleting a session that does not exist is a 200, never a 404",
      ).toBe(200);

      expect((await sessions.bulkDelete([])).status(), "an empty bulk delete is accepted").toBe(200);
      expect(await sessions.exists(second), "and removes nothing").toBe(200);

      const bulk = await sessions.bulkDelete([second, ABSENT_UUID]);
      expect(bulk.status()).toBe(200);
      // Not polled, and that is the assertion: `deleteBySessionIds` passes `refresh=true`, so the
      // very next read already sees it. A poll here would pass whether or not the refresh happened.
      expect(await sessions.exists(second), "the bulk delete is visible at once").toBe(404);
      expect(await sessions.exists(third), "and takes only the ids it names").toBe(200);

      expect(
        idsOf(await sessions.search({}, { chainId: mine.chain.id })),
        "the chain is left with the one session nothing deleted",
      ).toEqual([third]);

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("a chain's sessions delete together, alone or in a named set", { tag: ["@sessions", "@engine", "@tier2"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      // Two chains of this spec's own. Aimed at a seed chain either delete would destroy the traces
      // the runtime specs assert over, so they target a chain the spec created; the explicit
      // argument is not what makes them safe.
      const left = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "chaindel-a", calls: 2 }, built);
      const right = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "chaindel-b", calls: 2 }, built);
      const leftIds = left.sessions.map((each) => each.id).sort();
      const rightIds = right.sessions.map((each) => each.id).sort();

      const one = await sessions.deleteSessionsOfChain(left.chain.id);
      expect(one.status()).toBe(200);
      // `deleteByChainId` passes `refresh=true`, so this is a read of the settled index rather than
      // a race, and the sibling chain is what says the predicate was the chain and not the index.
      expect(idsOf(await sessions.search({}, { chainId: left.chain.id }))).toEqual([]);
      expect(idsOf(await sessions.search({}, { chainId: right.chain.id }))).toEqual(rightIds);
      for (const id of leftIds) expect(await sessions.exists(id)).toBe(404);

      // The plural form takes a comma-separated query parameter, and it is the call the run-token
      // sweep in `support/fixtures.ts` makes once for a whole run.
      const both = await sessions.deleteSessionsOfChains([left.chain.id, right.chain.id]);
      expect(both.status()).toBe(200);
      expect(idsOf(await sessions.search({}, { chainId: right.chain.id }))).toEqual([]);
      for (const id of rightIds) expect(await sessions.exists(id)).toBe(404);

      // `chainIds` is `@RequestParam` with no default, so the parameterless form is refused by
      // Spring before the service sees it — an RFC 9457 body, not the module's `ExceptionDTO`.
      const bare = await sessions.raw("delete", "/v1/sessions/chains");
      expect(bare.status(), "the plural delete refuses a request naming no chain").toBe(400);
      expect(((await bare.json()) as { detail: string }).detail).toContain("chainIds");

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("the element payload endpoint serves the body the listing withholds", { tag: ["@sessions", "@engine", "@tier2"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      const mine = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "payload", calls: 1 }, built);
      const session = mine.sessions[0];
      const step = stepNamed(session, PROBE_ELEMENT);

      // `GET /v1/sessions/{sessionId}` is the complement of the light listing beside it: the search
      // answers `sessionElements: null` whatever the body asked for, and this form carries the tree
      // the engine recorded. The external-id lookup this fixture used to find the session reads the
      // same document under a different key, so the two are asserted to agree.
      const whole = await sessions.session(session.id);
      expect(whole.id).toBe(session.id);
      expect(whole.chainId, "the session by its own id, still keyed to the chain").toBe(mine.chain.id);
      expect(whole.executionStatus).toBe("COMPLETED_NORMALLY");
      expect(
        elementNames(whole).sort(),
        "the trace, nested as the engine recorded it",
      ).toEqual(elementNames(session).sort());

      // A session id nothing answers to is the module's own envelope rather than RFC 9457, which is
      // the shape `specs/api/error-contract.spec.ts` reads the other half of.
      const noSession = await sessions.rawSession(ABSENT_UUID);
      expect(noSession.status()).toBe(404);
      expect(((await noSession.json()) as { errorMessage: string }).errorMessage).toContain(ABSENT_UUID);

      const payload = await sessions.elementPayload(session.id, step.elementId);
      expect(payload.elementId).toBe(step.elementId);
      expect(payload.elementName).toBe(PROBE_ELEMENT);
      expect(payload.camelName, "the element type, which repeats across a trace").toBe("script");
      expect(payload.executionStatus).toBe("COMPLETED_NORMALLY");
      expect(payload.sessionId).toBe(session.id);
      // The payload itself, which is the whole point of the endpoint: the light listing drops
      // `bodyBefore`, `bodyAfter` and the six other payload fields, so this is the only read that
      // answers what the step did without asking for the whole trace.
      expect(payload.bodyAfter, "the body the probe script wrote").toContain(PROBE_MARKER);
      expect(payload.bodyBefore, "and the body it was handed").not.toContain(PROBE_MARKER);
      expect(payload.headersAfter, "headers travel with the payload").toBeTruthy();

      const missing = await sessions.rawElementPayload(session.id, ABSENT_UUID);
      expect(missing.status(), "an element id nothing carries is a 404").toBe(404);
      const body = (await missing.json()) as { serviceName: string; errorMessage: string; errorDate: string };
      expect(body.errorMessage).toBe(`Can't find element with id ${ABSENT_UUID}`);
      expect(body.serviceName, "the module's own error envelope, not RFC 9457").toBe("Session Management");
      expect(body.errorDate).toBeTruthy();

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("sessions export and import back, and an import over a live id is refused", { tag: ["@sessions", "@engine", "@tier2"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    let imported: string | undefined;
    try {
      const mine = await record(catalog, env, request, sessions, { run, folderId: folder.id, what: "export", calls: 1 }, built);
      const session = mine.sessions[0];

      // One mapping, two methods, one body. The `GET` form carries its id list in the request body
      // rather than in a query string, which is unusual enough that a client written against the
      // path alone cannot call it.
      const viaPost = await sessions.exportSessions([session.id], "post");
      expect(viaPost.status()).toBe(200);
      const viaGet = await sessions.exportSessions([session.id], "get");
      expect(viaGet.status()).toBe(200);
      expect(await viaGet.text(), "the two methods answer the same document").toBe(await viaPost.text());

      // The file name is built from the **first** session's chain id, so it names the chain rather
      // than the export, and the header is exposed to a browser explicitly.
      expect(viaPost.headers()["content-disposition"]).toContain(
        `filename=chain-sessions-${mine.chain.id}-(`,
      );
      expect(viaPost.headers()["access-control-expose-headers"]).toContain("Content-Disposition");

      const document = Buffer.from(await viaPost.body());
      const exported = JSON.parse(document.toString("utf-8")) as RecordedSession[];
      expect(exported).toHaveLength(1);
      expect(exported[0].id).toBe(session.id);
      expect(exported[0].chainId).toBe(mine.chain.id);
      expect(exported[0].importedSession).toBe(false);
      // Flattened, because the document nests the way every trace does: the top level holds the
      // trigger and the script, and `Validate Request` sits under the trigger. A reader that counted
      // `sessionElements.length` would see two steps and conclude the export lost one.
      expect(
        elementNames(exported[0]).sort(),
        "the export carries the trace the light listing drops, nested as the engine recorded it",
      ).toEqual(["HTTP Trigger", PROBE_ELEMENT, "Validate Request"].sort());

      // Suite rule 5 from the other direction: the import refuses rather than updating, so a round
      // trip has to delete first and an assertion that the id is present cannot pass by accident.
      const conflict = await sessions.importSessions("sessions.json", document);
      expect(conflict.status(), "an import over a session that still exists is refused").toBe(409);
      expect(((await conflict.json()) as { errorMessage: string }).errorMessage).toContain(session.id);

      expect((await sessions.bulkDelete([session.id])).status()).toBe(200);
      expect(await sessions.exists(session.id)).toBe(404);

      const restored = await sessions.importSessions("sessions.json", document);
      expect(restored.status()).toBe(200);
      // Recorded before the body is read: the row exists the moment the import answered, and a
      // throw while parsing the answer would otherwise leave a chain-less session behind — one the
      // run-token sweep cannot reach, because it finds sessions by chain name and deletes them by
      // chain id.
      imported = session.id;
      const rows = (await restored.json()) as RecordedSession[];
      expect(rows.map((each) => each.id)).toEqual([session.id]);
      expect(rows[0].importedSession, "the importer marks what it wrote").toBe(true);
      expect(rows[0].chainId, "and clears the chain, so the session is no longer chain-scoped").toBeNull();
      expect(rows[0].chainName, "the recorded name survives").toBe(mine.chain.name);
      expect(rows[0].sessionElements, "the answer never carries the trace it just wrote").toBeNull();

      // The document lands through a bulk index with no refresh of its own, so the read polls.
      await expect
        .poll(() => sessions.exists(session.id), { message: "the re-imported session never landed" })
        .toBe(200);
      expect(
        idsOf(await sessions.search({}, { chainId: mine.chain.id })),
        "and it is unreachable by chain, because the importer cleared the chain id",
      ).toEqual([]);
      const byName = await sessions.search({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: mine.chain.name }],
      }, { count: 100 });
      expect(ours(byName, [session.id]), "the recorded name is the handle that is left").toEqual([session.id]);

      // A validation refusal rather than an empty answer: the id list is `@NotEmpty`.
      const empty = await sessions.exportSessions([], "post");
      expect(empty.status(), "an export naming no session is refused").toBe(400);
      expect(((await empty.json()) as { detail: string }).detail).toBe("Validation failure");

      bodyFailed = false;
    } finally {
      // The re-imported session carries no chain id, so the run-token sweep — which finds sessions
      // by chain name and deletes them by chain id — cannot reach it. Removed here or it outlives
      // every later run of the suite.
      if (imported !== undefined) await sessions.bulkDelete([imported]);
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);

test("the search takes an empty body and refuses a request carrying none", { tag: ["@sessions", "@tier1"] }, async ({ sessions, run }) => {
    // One session of this case's own first, so the index is never empty while the page below is
    // read. It is not decoration: the assertions say a page of one row comes back, and under eight
    // workers the sibling cases here delete every session they created — measured, this case went
    // red four times over a mutation sweep because it happened to read between two of those deletes.
    // Imported rather than recorded, because the case is about the request body rather than about a
    // chain, and an import costs no deploy.
    const own = await importOneSession(sessions, run, "empty-body");
    try {
      // Re-measured on this stack after PR #769 (issue #768). Both of these answered 500 before it,
      // one on a null `filterRequestList` and one through a catch-all advice that intercepted
      // Spring's own exceptions.
      const unfiltered = await sessions.rawSearch({}, { count: 1 });
      expect(unfiltered.status(), "`{}` is an unfiltered page").toBe(200);
      const page = (await unfiltered.json()) as SessionPage;
      expect(page.sessions.length, "one row, because one was asked for").toBe(1);
      expect(page.offset, "and the marker is what the page held").toBe(1);
      expect(page.sessions[0].sessionElements, "the listing is light whatever the body said").toBeNull();

      // The two bodies the UI can produce are one answer: `SessionService` reads an absent list the
      // same way it already read an absent `searchString`.
      const explicit = await sessions.rawSearch({ filterRequestList: [] }, { count: 1 });
      expect(explicit.status()).toBe(200);
      expect(((await explicit.json()) as SessionPage).offset).toBe(1);

      // A request with no body at all is a different failure and answers differently: Spring cannot
      // read the `@RequestBody`, and since PR #769 the handler no longer swallows that into a 500.
      const none = await sessions.searchWithoutBody();
      expect(none.status(), "a zero-byte body is refused").toBe(400);
      const problem = (await none.json()) as { type: string; status: number; detail: string; instance: string };
      expect(problem.type, "RFC 9457, which is what Spring's own handler writes").toBe("about:blank");
      expect(problem.status).toBe(400);
      expect(problem.detail).toBe("Failed to read request");
      expect(problem.instance).toBe("/v1/sessions");
    } finally {
      // The importer clears `chainId`, so the run-token sweep — which finds a run's sessions by
      // chain name and deletes them by chain id — cannot reach this one.
      await sessions.bulkDelete([own]);
    }
  },
);

test("a filter clause that names no column is refused", { tag: ["@sessions", "@tier2"] }, async ({ sessions }) => {
    // A platform defect, filed in `docs/product-defects.md` as "a malformed session filter clause
    // answers 500". PR #769 guarded an absent `filterRequestList`; a clause *inside* the list that
    // omits `feature` or `condition` still reaches the switch and is dereferenced there. It is the
    // easy mistake to make from the catalog side, where the same idea is spelled `column`:
    // `FilterRequestDTO.feature` carries `@JsonProperty("column")` there and nothing does here, so
    // a body copied across services deserializes with a null feature and answers 500.
    test.fail();
    expect(
      await onlyTheKnownStatus(
        sessions.rawSearch({
          filterRequestList: [{ column: "CHAIN_NAME", condition: "CONTAINS", value: "e2e" }],
        }),
        {
          defect: 500,
          fixed: 400,
          what: "a filter clause whose `feature` deserialized as null",
        },
      ),
    ).toBe(400);
  },
);

test("the external-id lookup answers the session a token produced, with its trace only on request", { tag: ["@sessions", "@engine", "@tier1"] }, async ({ env, request, sessions }) => {
  // A seeded chain, because the case is about the lookup and not about a chain of its own.
  const chain = seedChain(readCorpusState(), "http-echo");
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "external-id" } });
  expect(call.response.status()).toBe(200);

  const detailed = await sessions.byExternalId(call.token, { elements: 3 });
  expect(detailed).toMatchObject({
    chainId: chain.id,
    chainName: chain.name,
    externalSessionCipId: call.token,
    executionStatus: "COMPLETED_NORMALLY",
  });
  expect(detailed.sessionElements, "includeDetails=true carries the trace").not.toBeNull();

  const light = await sessions.attemptByExternalId(call.token, false);
  expect(light.status).toBe(200);
  expect(light.session).toMatchObject({ id: detailed.id, chainId: chain.id, externalSessionCipId: call.token });
  expect(light.session?.sessionElements, "the trace is left out unless includeDetails asks for it").toBeNull();

  const never = callToken("never-sent");
  const missing = await sessions.raw("get", `/v1/sessions/external-id/${never}`);
  expect(missing.status()).toBe(404);
  expect(((await missing.json()) as { errorMessage: string }).errorMessage).toBe(
    `Can't find session by external id ${never}`,
  );
});
