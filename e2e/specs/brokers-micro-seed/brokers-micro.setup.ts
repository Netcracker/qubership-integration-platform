/**
 * Imports the micro copy of `fixtures/brokers/` and deploys it to a micro domain of its own, before
 * the micro broker cases read it.
 *
 * A project apart from `brokers-seed` and `seed-micro`: the copy's addresses are its own, so the
 * two broker copies never consume each other's messages, and a broker chain that fails to load takes
 * this domain down and no other. Collected by `testMatch: /.*\.setup\.ts$/` on the
 * `brokers-micro-seed` project, for the reason `specs/seed/seed.setup.ts` gives.
 */
import { test } from "../../support/fixtures.js";
import { KAFKA_CONSUMER_ATTACH_TIMEOUT, seedMicroBrokers } from "../../support/brokers.js";
import { MICRO_READY_TIMEOUT } from "../../support/corpus.js";
import { OVERLAY_NAMES } from "../../env/containers.js";
import { OVERLAY_READY_TIMEOUT } from "../../env/overlays.js";

/** `brokers-seed`'s budget, with the micro domain's readiness in place of the classic deploy wait. */
const BROKERS_MICRO_SEED_TIMEOUT =
  OVERLAY_NAMES.length * OVERLAY_READY_TIMEOUT + MICRO_READY_TIMEOUT + KAFKA_CONSUMER_ATTACH_TIMEOUT;

test(
  "the micro copy of the broker corpus is imported, deployed to a micro domain, and answering",
  { tag: ["@catalog", "@engine", "@tier1"] },
  async ({ catalog, env, run }) => {
    test.setTimeout(BROKERS_MICRO_SEED_TIMEOUT);
    const corpus = await seedMicroBrokers(catalog, env, run);
    console.log(
      `[brokers-micro-seed] ${corpus.chains.length} chains answering on micro domain ${corpus.domain} for run ${run}`,
    );
  },
);
