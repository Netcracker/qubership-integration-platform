/**
 * Imports the micro copy of the corpus and deploys it to a micro domain of its own, before
 * `runtime-micro` reads it.
 *
 * A project apart from `seed`: every micro chain loads into one Integration, and one chain that fails
 * to load takes the pod down, which here fails `runtime-micro` and nothing else. Collected by
 * `testMatch: /.*\.setup\.ts$/` on the `seed-micro` project, for the reason `seed.setup.ts` gives.
 */
import { test } from "../../support/fixtures.js";
import { MICRO_READY_TIMEOUT, seedMicroCorpus } from "../../support/corpus.js";

/** The route poll, plus the import, the logging properties, and the deploy before it. */
const SEED_MICRO_TIMEOUT = MICRO_READY_TIMEOUT + 120_000;

test(
  "the micro copy of the corpus is imported, deployed to a micro domain, and answering",
  { tag: ["@catalog", "@engine", "@tier1"] },
  async ({ catalog, env, run }) => {
    test.setTimeout(SEED_MICRO_TIMEOUT);
    const { corpus, readyMs } = await seedMicroCorpus(catalog, env, run);
    console.log(
      `[seed-micro] ${corpus.chains.length} chains answering on micro domain ${corpus.domain} for ` +
        `run ${run}, ${(readyMs / 1000).toFixed(1)} s after the deploy`,
    );
  },
);
