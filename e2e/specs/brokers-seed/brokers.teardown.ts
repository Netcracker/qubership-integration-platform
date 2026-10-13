/**
 * Removes the broker corpus, and the topics and queues the seed created for it, once every project
 * that depends on `brokers-seed` has finished.
 *
 * It runs in `suite-teardown`, a project of its own, for the same reason
 * `specs/seed/seed.teardown.ts` runs in `seed-teardown`: Playwright's `teardown` names **another
 * project**, and both files behind one `testMatch` would make this an ordinary test of the setup
 * project, running before a single broker spec had used the corpus.
 *
 * `global` and `seed-micro` also name `suite-teardown` (`playwright.config.ts`), and neither edge
 * goes through `brokers-seed`'s `dependencies`, so one failing broker case cannot skip every
 * `global` case. The cost is that `--project=global` alone runs this file without `brokers-seed`
 * ever running in the same process: `npx playwright test --list --project=global` resolves to
 * `suite-teardown` with no `brokers-seed` and no `brokers` beside it. Left ungated, this test would
 * then read whatever `.e2e-brokers-corpus.json` a *previous* run left on disk — including one kept deliberately with
 * `E2E_KEEP=1` — and tear it down: undeploy and delete its chains, and delete its Kafka topics,
 * consumer groups, RabbitMQ topology, pub/sub topology and `/upload/<run>` tree. That is exactly
 * the residue the "Broker residue" entry of `e2e/README.md` says only this test may clear, and
 * only for the run that created it. The guard below is the same fact stated in code: a corpus
 * this run's own `brokers-seed` did not write is not this run's to clear.
 */
import fs from "node:fs";
import { overlayNames } from "../../env/compose.js";
import type { Overlay } from "../../env/index.js";
import { keepEntities, test } from "../../support/fixtures.js";
import { BROKERS_CORPUS_STATE_FILE, readBrokersCorpusState, teardownBrokers } from "../../support/brokers.js";

test("the broker fixture corpus is removed", { tag: ["@engine", "@tier1"] }, async ({ catalog, env, run }) => {
  if (!fs.existsSync(BROKERS_CORPUS_STATE_FILE)) {
    console.log("[brokers-seed] no broker corpus state to tear down");
    return;
  }
  const corpus = readBrokersCorpusState();
  if (corpus.run !== run) {
    console.log(
      `[brokers-seed] ${BROKERS_CORPUS_STATE_FILE} names run ${corpus.run}, not this run (${run}): ` +
        `brokers-seed did not run in this selection, so its corpus is left for its own run to clear`,
    );
    return;
  }
  await teardownBrokers(catalog, corpus);
  // A kept corpus still needs its brokers, and a failed teardown above leaves them up to inspect.
  if (keepEntities()) return;
  for (const overlay of overlayNames()) await env.removeOverlay(overlay as Overlay);
});
