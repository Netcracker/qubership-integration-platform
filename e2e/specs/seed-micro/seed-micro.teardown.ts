/**
 * Removes the micro copy of the corpus and its domain once `runtime-micro` has finished.
 *
 * It runs in `suite-teardown`, which `seed-micro`, `global`, and the broker seeds name as their
 * `teardown`, and on Compose too. So a run without `seed-micro`, such as `--project=global` alone,
 * runs this file as well; a corpus another run wrote is then left to that run, as
 * `specs/brokers-seed/brokers.teardown.ts` leaves a broker corpus.
 */
import fs from "node:fs";
import { test } from "../../support/fixtures.js";
import { readMicroCorpusState, teardownMicroCorpus } from "../../support/corpus.js";
import { MICRO_CORPUS_STATE_FILE } from "../../env/k8s.js";

test("the micro copy of the corpus is removed", { tag: ["@catalog"] }, async ({ catalog, run }) => {
  if (!fs.existsSync(MICRO_CORPUS_STATE_FILE)) {
    console.log("[seed-micro] no micro corpus state to tear down");
    return;
  }
  const corpus = readMicroCorpusState();
  if (corpus.run !== run) {
    console.log(
      `[seed-micro] ${MICRO_CORPUS_STATE_FILE} names run ${corpus.run}, not this run (${run}): ` +
        `seed-micro did not run in this selection, so its corpus is left for its own run to clear`,
    );
    return;
  }
  await teardownMicroCorpus(catalog, corpus);
});
