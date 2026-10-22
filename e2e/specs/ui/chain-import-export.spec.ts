/**
 * The chains list's Export and Import: a chain the browser exports imports back from the same file.
 *
 * What the API cannot see: which ids the toolbar's Export sends for the selected row, that the
 * archive reaches the browser as a download (`<a download>`), and that the Import dialog uploads the
 * file, previews it, and imports it. `specs/api/import-export.spec.ts` covers the archive the catalog
 * writes and the folder it rebuilds by name.
 *
 * The chain is deleted between the two (rule 5), so the import has to create it. The case fails
 * when the export holds another chain or none, when the import reports anything but Created, and
 * when the chain comes back outside its folder.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { callToken, tokenized } from "../../support/run.js";

test.describe("chain export and import", { tag: ["@ui"] }, () => {
  test("an exported chain imports back into its folder", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, callToken("ui-chain-export")), folder.id);
    await catalog.createElement(chain.id, "log-record");
    const chains = new ChainsPage(page);

    await chains.gotoFolder(folder.id);
    await chains.row(chain.name).getByRole("checkbox").check();
    const download = await chains.exportSelected();
    // The catalog picks the parser by the file's extension, and the download's own path carries none.
    const archive = test.info().outputPath(download.suggestedFilename());
    await download.saveAs(archive);

    await catalog.deleteChain(chain.id);
    const dialog = await chains.importArchive(archive);

    // Chain ids survive the round trip, so the result row is found by the deleted chain's id.
    await expect(dialog.getByRole("row").filter({ hasText: chain.id })).toContainText("Created");
    const restored = await catalog.getChain(chain.id);
    expect(restored.name).toBe(chain.name);
    // The folder still existed, so the import matched it by name.
    expect(restored.parentId).toBe(folder.id);
  });
});
