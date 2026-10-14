/**
 * Imports and deploys the shared fixture corpus, once per run, before anything reads it.
 *
 * This file is collected by `testMatch: /.*\.setup\.ts$/` on the `seed` project and by nothing
 * else. Playwright's default `testMatch` is `**\/*.@(spec|test).?(c|m)[jt]s?(x)`, so without that
 * the project runs **zero** tests, reports success, and satisfies its dependents — and every
 * runtime spec then fails for want of a corpus, which reads as a broken runtime spec.
 */
import { test, expect } from "../../support/fixtures.js";
import { seedCorpus, writeCorpusState } from "../../support/corpus.js";
import { corpusFixtureNames } from "../../fixtures/templating.js";
import { writeAxisFixtures } from "../../fixtures/axis-generator.js";

test("the fixture corpus is imported, deployed, and answering", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, run }) => {
  // The import, the deploy wait that grows with the corpus, and the route wait do not fit in 120 s.
  test.setTimeout(5 * 60_000);
  // The generated axis chains are written first, so the corpus below is read off a fresh directory.
  await writeAxisFixtures();
  const fixtures = corpusFixtureNames();
  // Asserted before the corpus is built rather than after: "every route is live" is satisfied by
  // zero routes, and an empty fixture directory produces an archive the importer accepts with an
  // empty result. The count is the only thing that separates the two.
  expect(fixtures.length, "the fixture corpus is empty, so the seed would deploy nothing").toBeGreaterThan(0);

  const corpus = await seedCorpus(catalog, env, run, fixtures);
  expect(corpus.chains.map((each) => each.fixture)).toEqual([...fixtures]);
  writeCorpusState(corpus);

  console.log(`[seed] ${corpus.chains.length} chains deployed and answering for run ${run}`);
});
