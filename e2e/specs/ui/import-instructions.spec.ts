/**
 * Import Instructions under Admin Tools: an instruction added in the dialog keeps a chain out of an
 * import, and the delete from the list lets the next import create it.
 *
 * What the API cannot see: that the Add dialog's defaults, entity type Chain and action Ignore, make
 * an IGNORE instruction for the typed id, and that the row appears and leaves with no reload. The
 * chain is exported, deleted and imported through the API; `specs/api/import-export.spec.ts`
 * covers the instruction store and the import's report.
 */
import { test, expect } from "../../support/page-guard.js";
import type { Catalog } from "../../support/catalog.js";
import { TableView } from "../../pages/TableView.js";
import { chainExists, importChains } from "../../support/chain-import.js";
import { tokenizedChain } from "../../support/deployable.js";
import { callToken } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

async function ignoredChains(catalog: Catalog): Promise<string[]> {
  return ((await catalog.listImportInstructions()).chains.ignore ?? []).map((each) => each.id);
}

test.describe("import instructions", { tag: ["@ui"] }, () => {
  test("an ignore instruction keeps a chain out until it is deleted", async ({ page, catalog, folder, run }) => {
    const chain = await tokenizedChain(catalog, run, { prefix: "ui-instructions", what: callToken("ignored"), parentId: folder.id });
    const archive = await catalog.exportChains([chain.id]);
    await catalog.deleteChain(chain.id);
    // The deleted chain has no name to show, so the row shows its id.
    const row = new TableView(page).rows.filter({ has: page.getByText(chain.id, { exact: true }) });

    const loaded = page.waitForResponse((response) => response.url().endsWith("/catalog/import-instructions"));
    await page.goto("/admintools/import-instructions");
    expect((await loaded).ok()).toBe(true);
    try {
      await page.getByRole("button", { name: "Add", exact: true }).click();
      const dialog = page.getByRole("dialog", { name: "Add Instruction" });
      await dialog.getByRole("textbox", { name: "Id" }).fill(chain.id);
      await dialog.getByRole("button", { name: "Save" }).click();
      await expect(dialog).toBeHidden();
      await expect(row).toBeVisible();
      expect(await ignoredChains(catalog)).toContain(chain.id);

      await importChains(catalog, archive);
      expect(await chainExists(catalog, chain.id), "the import created a chain its instruction ignores").toBe(false);

      await row.getByRole("checkbox").check();
      await page.getByTestId("import-instructions-delete").click();
      const confirm = page.getByRole("dialog").filter({ hasText: "Delete instructions" });
      await confirm.getByRole("button", { name: "Delete" }).click();
      await expect(confirm).toBeHidden();
      await expect(row).toHaveCount(0);
      expect(await ignoredChains(catalog)).not.toContain(chain.id);

      await importChains(catalog, archive);
      expect(await chainExists(catalog, chain.id), "the import left out a chain no instruction names").toBe(true);
    } finally {
      await catalog.deleteImportInstructions({ chains: [chain.id] }).catch(leftBehind(`import instruction ${chain.id}`));
      // The import puts the chain back into the worker folder by name, so the folder cascade covers it too.
      await catalog.deleteChain(chain.id).catch(() => undefined);
    }
  });
});
