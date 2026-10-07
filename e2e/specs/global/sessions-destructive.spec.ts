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
 * this endpoint from its three siblings is that the platform holds no sessions at all afterwards,
 * so that is the assertion, and it is only available to a spec that owns the stack.
 */
import fs from "node:fs";
import { test, expect } from "../../support/fixtures.js";
import {
  callChain,
  type RecordedSession,
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
import { BROKERS_CORPUS_STATE_FILE, readBrokersCorpusState, type BrokerChain } from "../../support/brokers.js";
import { keepEntities } from "../../support/fixtures.js";
import { SESSION_SEARCH_HEADER_LIMIT, strikesAsKnown } from "../../support/known-defect.js";
import type { Env } from "../../env/index.js";
import type { Catalog } from "../../support/catalog.js";
import type { APIRequestContext } from "@playwright/test";

/**
 * Undeploys `fixtures/brokers/scheduler-basic`, the one chain in the whole run that keeps writing
 * sessions on its own, with nothing driving it, and answers the chain so the caller can redeploy it.
 *
 * `global` declares `brokers-seed-teardown` as its own `teardown` project in `playwright.config.ts`,
 * so that teardown runs strictly after this file, not before, and the scheduler fires every 3 s for
 * the whole `global` run. A tick landing between `deleteEverySession()` and the immediate "the index
 * holds nothing" read below would write a session into the exact window this case asserts is empty.
 *
 * `brokers-seed-teardown` deletes the whole chain afterward when the corpus is this run's own, so
 * leaving it undeployed here costs nothing downstream in the ordinary run. Under `E2E_KEEP=1` that
 * teardown returns before deleting anything, so an undeploy here would otherwise outlive the run and
 * break scheduler.spec.ts's own rerun against the kept corpus — `restoreBrokerScheduler` below is
 * what puts it back, but only for this run's own scheduler.
 *
 * `global` can also run with `--project=global` alone, with no `brokers-seed` in the same process,
 * the way `brokers.teardown.ts`'s own comment describes. The state file on disk then names an
 * earlier run — kept on purpose with `E2E_KEEP=1`, or left by one that died — and undeploying its
 * scheduler here would strand it with nothing to redeploy it, since `restoreBrokerScheduler` only
 * fires under this run's own `E2E_KEEP=1`. So this function skips a corpus that is not this run's,
 * the same guard `brokers.teardown.ts` applies before it deletes anything.
 *
 * A state file that exists but does not parse is a truncated write from an earlier, interrupted run
 * (the failure mode `readBrokersCorpusState`'s own doc comment names), not something this run's
 * sessions wipe should fail over — so that case is treated the same as the file being absent.
 */
async function quiesceBrokerScheduler(catalog: Catalog, run: string): Promise<BrokerChain | undefined> {
  if (!fs.existsSync(BROKERS_CORPUS_STATE_FILE)) return undefined;
  let corpus;
  try {
    corpus = readBrokersCorpusState();
  } catch {
    return undefined;
  }
  if (corpus.run !== run) return undefined;
  const scheduler = corpus.chains.find((each) => each.fixture === "scheduler-basic");
  if (scheduler) await catalog.undeployAll(scheduler.id).catch(() => {});
  return scheduler;
}

/**
 * Redeploys `scheduler-basic` from its last snapshot, undoing `quiesceBrokerScheduler` under
 * `E2E_KEEP=1` — the one mode where nothing else redeploys it afterward. `LAST_CREATED` reuses the
 * snapshot the brokers seed already built rather than compiling a new one.
 */
async function restoreBrokerScheduler(catalog: Catalog, scheduler: BrokerChain | undefined): Promise<void> {
  if (!scheduler || !keepEntities()) return;
  await catalog.bulkDeploy([scheduler.id], { snapshotAction: "LAST_CREATED" }).catch((cause: unknown) => {
    console.error(`[keep] scheduler-basic (${scheduler.id}) was not redeployed after the sessions wipe: ${String(cause)}`);
  });
}

/** Enough to read the whole index in one page. A run leaves of the order of a hundred sessions. */
const EVERY_SESSION = 1000;

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
    const scheduler = await quiesceBrokerScheduler(catalog, run);
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

      // Behind an Istio sidecar a search this wide fails once the stack holds enough sessions (#985).
      const before = await strikesAsKnown(SESSION_SEARCH_HEADER_LIMIT, () =>
        sessions.search({}, { count: EVERY_SESSION }),
      );
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
      expect(wiped.status()).toBe(200);
      expect(await wiped.text(), "a delete with nothing to report answers no body").toBe("");

      // `deleteAllSessions` passes `refresh=true`, so this is a read of the settled index rather
      // than a poll — and a poll would be the wrong instrument anyway: it would accept an index
      // that emptied for some other reason a second later.
      const after = await sessions.search({}, { count: EVERY_SESSION });
      expect(after.sessions, "the platform holds no session at all afterwards").toEqual([]);
      expect(after.offset).toBe(0);
      for (const row of before.sessions.slice(0, 20)) {
        expect(await sessions.exists(row.id), `session ${row.id} survived the wipe`).toBe(404);
      }

      // It matches by query rather than by enumeration, so an empty index is not a special case.
      expect(
        (await sessions.deleteEverySession()).status(),
        "a second wipe over an empty index answers the same",
      ).toBe(200);

      // The blast radius stops at the traces. The chain is still deployed and the route is still
      // serving, so the next call records a new session — which is what says the delete removed
      // documents rather than the index or the alias behind them.
      const afterwards = await drive(request, sessions, env, chain);
      expect(afterwards.id).not.toBe(mine.id);
      expect(await sessions.exists(afterwards.id)).toBe(200);
      expect(
        (await sessions.search({}, { chainId: chain.id })).sessions.map((each) => each.id),
        "and the chain holds exactly the one session recorded since the wipe",
      ).toEqual([afterwards.id]);

      bodyFailed = false;
    } finally {
      await restoreBrokerScheduler(catalog, scheduler);
      await releaseChains(catalog, built, bodyFailed);
    }
  },
);
