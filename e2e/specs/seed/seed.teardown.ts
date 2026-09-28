/**
 * Removes the shared corpus once every project that depends on the seed has finished.
 *
 * It is a **project of its own**, named by the seed project's `teardown` property. Playwright's
 * `teardown` names another project (`playwright/types/test.d.ts:587`), so putting this file behind
 * the seed project's own `testMatch` would make it an ordinary test of the setup project: it would
 * run right after the import and take the corpus away before a single runtime spec had used it.
 */
import { test } from "../../support/fixtures.js";
import { CORPUS_STATE_FILE, readCorpusState, teardownCorpus } from "../../support/corpus.js";
import fs from "node:fs";

test("the fixture corpus is removed", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  if (!fs.existsSync(CORPUS_STATE_FILE)) {
    console.log("[seed] no corpus state to tear down");
    return;
  }
  const corpus = readCorpusState();
  await teardownCorpus(catalog, corpus);
});
