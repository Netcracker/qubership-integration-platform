/** Repeats the audit fixture's 21 concurrent writes on an owned parent folder. */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { Catalog } from "../../support/catalog.js";

test.describe("folder response diagnostics", { tag: "@catalog" }, () => {
  for (let lane = 0; lane < 4; lane++) {
    test(`diagnostic folder bursts, lane ${lane}`, async ({ playwright, catalog, folder, run }) => {
      test.setTimeout(180_000);
      for (let round = 0; round < 100; round++) {
        const parent = await catalog.createFolder(tokenized(run, `http-probe-${lane}-${round}`), folder.id);
        // A new context makes each burst open new TCP connections.
        const api = await playwright.request.newContext();
        const fresh = new Catalog(api);
        const responses = await Promise.allSettled(Array.from({ length: 21 }, (_, index) => fresh.createFolder(tokenized(run, `http-probe-${lane}-${round}-${index}`), parent.id)));
        await api.dispose();
        const failures = responses.filter((response) => response.status === "rejected");
        expect(failures, `lane ${lane}, round ${round}`).toEqual([]);
        expect((await catalog.listNestedElements(parent.id)).filter((item) => item.id !== parent.id).length).toBe(21);
        await catalog.deleteFolder(parent.id);
      }
    });
  }
});
