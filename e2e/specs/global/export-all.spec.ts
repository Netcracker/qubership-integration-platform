/**
 * The reads that address the whole platform rather than one worker's entities.
 *
 * `GET /v1/catalog/export` takes no ids: it exports every chain the catalog holds. That makes it
 * the one export a spec cannot scope, and `e2e-spec-authoring` rule 2 is what sends it here — a
 * spec needing a global view gets a project running on its own rather than a place in `specs/api/`.
 *
 * The rule earned itself on this endpoint. The exporter lists every chain and then resolves each
 * one's folder, so a folder a parallel worker deletes in between makes the whole request answer
 * **500 `EntityNotFoundException: Unable to find … Folder with id …`** — measured under a full
 * `--project=api` run, 3 of 120 calls answered that way. A retry around the call waits on nothing
 * but the other workers, which is why the project's dependency on `env` is the fix and `workers: 1`
 * is not: it runs when the rest of the suite has finished.
 *
 * The assertion is still over this spec's own chain. Another run's residue, or a chain a person
 * left on the stack, is in the same archive, so an entry count would report the stack rather than
 * the export.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { optionalEntryText } from "../../support/zip.js";

test.describe("whole-catalog export", { tag: ["@catalog"] }, () => {
  test("carries a chain exactly as its own export does", async ({ catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "export-all"), folder.id, "in both");
    const entry = `chains/${chain.id}/${chain.id}.chain.cip.yaml`;

    const everything = await catalog.exportAllChains();
    const mine = await optionalEntryText(everything, entry);

    // The entry has to be there, and its document has to be the same one the chain's own export
    // writes: an archive of every chain that abbreviates what it holds would pass an entry-name check
    // and lose the content.
    expect(mine, "this chain's entry is missing from the export of everything").toBeDefined();
    expect(mine).toBe(await optionalEntryText(await catalog.exportChain(chain.id), entry));
  });
});
