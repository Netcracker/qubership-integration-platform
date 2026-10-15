/**
 * `DELETE /v1/sessions` — the bare form, which removes every session in OpenSearch.
 *
 * One operation, one file, and `e2e/AGENTS.md` rule 2 is what puts it here rather than beside the
 * rest of the sessions surface in `specs/api/sessions-api.spec.ts`. `SessionService.deleteAllSessions`
 * is a delete by query with `MatchAllQuery` and no predicate at all — not a chain, not a caller, not
 * a run token — so it takes every worker's traces and every previous run's with them. A parallel
 * project cannot hold it, and neither can a project that runs before one: `global` declares
 * `api`, `runtime` and `env`, so by the time this file runs, every spec that asserts over a session
 * has finished asserting.
 *
 * The three sibling deletes are **not** here. `POST /v1/sessions/bulk-delete` takes an explicit id
 * list and `DELETE /v1/sessions/chains/{chainId}` and `DELETE /v1/sessions/chains?chainIds=…` take
 * chains, so all three are parallel-safe **against a chain the spec created itself** — aimed at a
 * seed chain the chain-scoped pair would destroy exactly the traces the runtime specs assert over,
 * and that, rather than the explicitness of their argument, is what restricts them.
 *
 * Within the project this file sorts last, and nothing after it reads a session. That
 * is ordering by name rather than a guarantee Playwright offers, so the case is written to survive
 * being run first as well: it produces the session it asserts over, and it proves the index still
 * accepts writes afterwards.
 *
 * **The wipe is asserted on the whole index, deliberately.** "My session is gone" would hold for a
 * delete scoped to one chain, and would say nothing about the operation under test. What separates
 * this endpoint from its three siblings is that the platform holds no sessions afterwards, apart
 * from what the scheduler chain fires on its own, so that is the assertion, and it is only available
 * to a spec that owns the stack.
 *
 * The delete aborts with 400 when a chain rewrites a session element while it runs
 * (`WIPE_ABORTED_ON_CONFLICT` in `sessions-defects.ts`). Its status is therefore asserted last, so a
 * case that meets the defect still shows that the index goes on recording.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  callChain,
  type RecordedSession,
  type SessionSearch,
  type Sessions,
} from "../../support/sessions.js";
import {
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  type SeedChain,
  waitForDeployed,
  waitForRoutes,
} from "../../support/corpus.js";
import { createScriptChain, SCRIPT_CHAIN_STEPS } from "../../support/deployable.js";
import { releaseChains } from "../../support/cleanup.js";
import { strikesAsKnown } from "../../support/known-defect.js";
import { WIPE_ABORTED_ON_CONFLICT, WIPE_ANSWERED } from "./sessions-defects.js";
import type { Env } from "../../env/index.js";
import type { APIRequestContext } from "@playwright/test";

/**
 * Every session but those of `fixtures/chains/scheduler-basic`, which fires every 3 s on both
 * engines with nothing driving it: its sessions would fill the page this case reads, and one can
 * land between the wipe and the read after it. Matched by name, so the copies of every run, kept
 * corpora included, are left out.
 */
const NOT_FIRED_ON_ITS_OWN: SessionSearch = {
  filterRequestList: [{ feature: "CHAIN_NAME", condition: "DOES_NOT_CONTAIN", value: "-scheduler-basic" }],
};

/** One page, newest first: it holds this case's own session and the traces the wipe is measured against. */
const ONE_PAGE = 1000;

/** How much of a failed delete's body the assertion quotes: the first conflict sits in its first 1000 characters. */
const WIPE_EXCERPT = 1500;

/** The script element's name, which is what this spec's chain carries into the trace. */
const PROBE_ELEMENT = "Wipe Probe";

/** What the probe writes into the body, so a payload read says which step it came from. */
const PROBE_SCRIPT = `exchange.getMessage().setBody('{"probe":"wipe"}')`;

/** Calls the chain once and answers the settled session, correlated on the call's own token. */
async function drive(
  request: APIRequestContext,
  sessions: Sessions,
  env: Env,
  chain: SeedChain,
): Promise<RecordedSession> {
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { probe: "wipe" } });
  expect(call.response.status(), `calling ${chain.name}`).toBe(200);
  return await sessions.byExternalId(call.token, { elements: SCRIPT_CHAIN_STEPS });
}

test("the bare delete removes every session on the platform, and the index goes on recording", { tag: ["@sessions", "@engine", "@tier1"] }, async ({ catalog, env, request, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    const built: SeedChain[] = [];
    let bodyFailed = true;
    try {
      // A chain of this spec's own rather than a corpus one, for the same reason
      // `specs/global/events.spec.ts` builds one: the corpus is deployed for the length of the run
      // and read by every project, and this case has to call something and then destroy what the
      // call recorded.
      const chain = await createScriptChain(
        catalog,
        run,
        {
          what: "wipe",
          parentId: folder.id,
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

      const mine = await drive(request, sessions, env, chain);

      const before = await sessions.search(NOT_FIRED_ON_ITS_OWN, { count: ONE_PAGE });
      expect(
        before.sessions.map((each) => each.id),
        "the session this case just recorded is in the platform-wide listing",
      ).toContain(mine.id);
      // The other traces on the stack are what the wipe has to be measured against: a delete that
      // took only this chain's sessions would satisfy every assertion below except this count.
      expect(
        before.sessions.length,
        "the seeded corpus has already run, so the index holds more than this case's own session",
      ).toBeGreaterThan(1);
      expect(before.offset, "the page marker is what the page held").toBe(before.sessions.length);

      const wiped = await sessions.deleteEverySession();
      const answer = await wiped.text();
      const cleared = wiped.status() === 200;
      if (cleared) {
        expect(answer, "a delete with nothing to report answers no body").toBe("");

        // `deleteAllSessions` passes `refresh=true`, so this is a read of the settled index rather
        // than a poll — and a poll would be the wrong instrument anyway: it would accept an index
        // that emptied for some other reason a second later.
        const after = await sessions.search(NOT_FIRED_ON_ITS_OWN, { count: ONE_PAGE });
        expect(
          after.sessions,
          "the platform holds no session afterwards, the scheduler's own left out",
        ).toEqual([]);
        for (const row of before.sessions.slice(0, 20)) {
          expect(await sessions.exists(row.id), `session ${row.id} survived the wipe`).toBe(404);
        }

        // It matches by query rather than by enumeration, so an empty index is not a special case.
        expect(
          (await sessions.deleteEverySession()).status(),
          "a second wipe answers the same",
        ).toBe(200);
      }

      // The blast radius stops at the traces. The chain is still deployed and the route is still
      // serving, so the next call records a new session — which is what says the delete removed
      // documents rather than the index or the alias behind them. That holds for an aborted delete
      // too, which leaves some sessions behind, possibly this chain's first one.
      const afterwards = await drive(request, sessions, env, chain);
      expect(afterwards.id).not.toBe(mine.id);
      expect(await sessions.exists(afterwards.id)).toBe(200);
      expect(
        (await sessions.search({}, { chainId: chain.id })).sessions.map((each) => each.id),
        "the chain holds the session recorded since the wipe, and only that one once the wipe cleared",
      ).toEqual(cleared ? [afterwards.id] : expect.arrayContaining([afterwards.id]));

      // Asserted last, so a wipe that met the known defect still shows the index recording.
      await strikesAsKnown(WIPE_ABORTED_ON_CONFLICT, async () =>
        expect(wiped.status(), `${WIPE_ANSWERED} ${answer.slice(0, WIPE_EXCERPT)}`).toBe(200),
      );

      bodyFailed = false;
    } finally {
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);
