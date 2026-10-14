/**
 * Imports and deploys the fixtures under `fixtures/brokers/`, once per run, before the `brokers`
 * project reads any of them.
 *
 * A project of its own, never a step hung off `seed`: `support/brokers.ts` explains
 * why the broker fixtures live outside `fixtures/chains/`. Collected by `testMatch:
 * /.*\.setup\.ts$/` on the `brokers-seed` project and by nothing else, for the reason
 * `specs/seed/seed.setup.ts` gives — Playwright's default `testMatch` collects `*.setup.ts` not at
 * all, and a setup project that runs zero tests reports success and satisfies its dependents, which
 * would put the failure on every broker spec instead of on the seed.
 */
import { test, expect } from "../../support/fixtures.js";
import { corpusFixtureNames } from "../../fixtures/templating.js";
import {
  BROKERS_FIXTURE_DIR,
  KAFKA_CONSUMER_ATTACH_TIMEOUT,
  seedBrokers,
  writeBrokersCorpusState,
} from "../../support/brokers.js";
import { DEPLOY_TIMEOUT } from "../../support/corpus.js";
import { overlayNames, OVERLAY_READY_TIMEOUT } from "../../env/compose.js";

/**
 * The worst case `seedBrokers` can take: every overlay it needs starts cold and each uses its whole
 * `ensureOverlay` budget, one after another, ahead of the deploy wait and the Kafka consumer-attach
 * wait that follow them.
 *
 * Sized on the same numbers `seedBrokers` actually calls with, not guessed: up to `overlayNames()`
 * overlays (four today), `OVERLAY_READY_TIMEOUT` each (`env/compose.ts`), plus `DEPLOY_TIMEOUT`
 * (`support/corpus.ts`) and `KAFKA_CONSUMER_ATTACH_TIMEOUT` (`support/brokers.ts`) for the two waits
 * after the last overlay is up. Left at the suite's 120 s default, this test's own budget is smaller
 * than what `Env.ensureOverlay` alone can legitimately take, so a broker that is genuinely slow to
 * start fails on a bare Playwright timeout instead of on `ensureOverlay`'s own "did not start
 * serving within 180s" message — the message this seed exists to make reachable.
 */
const BROKERS_SEED_TIMEOUT = overlayNames().length * OVERLAY_READY_TIMEOUT + DEPLOY_TIMEOUT + KAFKA_CONSUMER_ATTACH_TIMEOUT;

test(
  "the broker fixture corpus is imported, deployed, and answering",
  { tag: ["@engine", "@tier1"] },
  async ({ catalog, env, run }) => {
    test.setTimeout(BROKERS_SEED_TIMEOUT);
    const fixtures = corpusFixtureNames([BROKERS_FIXTURE_DIR]);
    // Asserted before anything is built, the way `seed` asserts it: "every route is
    // live" is satisfied by zero routes, and an empty fixture directory produces an archive the
    // importer accepts with an empty result.
    expect(
      fixtures.length,
      "e2e/fixtures/brokers/ is empty, so the brokers seed would deploy nothing",
    ).toBeGreaterThan(0);

    const corpus = await seedBrokers(catalog, env, run, fixtures);
    // `seedBrokers` already wrote this corpus once, before it created a single topic — the reason is
    // on that write, in `support/brokers.ts`. This second write is the confirmed, final state, over
    // the same object.
    writeBrokersCorpusState(corpus);

    console.log(`[brokers-seed] ${corpus.chains.length} chains deployed and answering for run ${run}`);
  },
);
