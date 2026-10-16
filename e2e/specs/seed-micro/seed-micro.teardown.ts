/**
 * Removes the micro copy of the corpus and its domain once `runtime-micro` has finished.
 *
 * A project of its own, named by `seed-micro`'s `teardown` property, for the reason
 * `specs/seed/seed.teardown.ts` is one. `global` names it as its `teardown` too, so
 * `--project=global` alone runs this with no `seed-micro` in the run; a corpus another run wrote is
 * then left to that run, as `specs/brokers-seed/brokers.teardown.ts` leaves a broker corpus.
 */
import fs from "node:fs";
import { test } from "../../support/fixtures.js";
import { readMicroCorpusState, teardownMicroCorpus } from "../../support/corpus.js";
import { MICRO_CORPUS_STATE_FILE } from "../../env/k8s.js";

test("the micro copy of the corpus is removed", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
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
