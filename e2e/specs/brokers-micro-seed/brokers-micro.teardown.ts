/**
 * Removes the micro copy of the broker corpus, its domain, and its topology once the micro broker
 * cases have finished.
 *
 * It runs in `suite-teardown`, whose `env` is classic, so it reads only the catalog and the state
 * file. A run without `brokers-micro-seed` runs this file as well; a copy another run wrote is then
 * left to that run, as `specs/brokers-seed/brokers.teardown.ts` leaves a broker corpus.
 */
import fs from "node:fs";
import { test } from "../../support/fixtures.js";
import { MICRO_GROUP_LEAVE_TIMEOUT, readMicroBrokersCorpusState, teardownMicroBrokers } from "../../support/brokers.js";
import { BROKERS_MICRO_CORPUS_STATE_FILE } from "../../env/k8s.js";

test("the micro copy of the broker corpus is removed", { tag: ["@engine"] }, async ({ catalog, run }) => {
  // The consumer groups empty only after the domain's pod has gone; the rest takes seconds.
  test.setTimeout(MICRO_GROUP_LEAVE_TIMEOUT + 60_000);
  if (!fs.existsSync(BROKERS_MICRO_CORPUS_STATE_FILE)) {
    console.log("[brokers-micro-seed] no micro broker corpus state to tear down");
    return;
  }
  const corpus = readMicroBrokersCorpusState();
  if (corpus.run !== run) {
    console.log(
      `[brokers-micro-seed] ${BROKERS_MICRO_CORPUS_STATE_FILE} names run ${corpus.run}, not this run (${run}): ` +
        `brokers-micro-seed did not run in this selection, so its copy is left for its own run to clear`,
    );
    return;
  }
  await teardownMicroBrokers(catalog, corpus);
});
