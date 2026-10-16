/**
 * The chains list's own flows: create through the dialogs, move through cut and paste, delete.
 *
 * Creating through the dialog is this spec's subject, so it bypasses the API. The rest is what
 * the API cannot see: which field has focus when a dialog opens, and the tree the list redraws
 * from its own state after a move or a delete, with no reload in between.
 *
 * The creation dialog has no unsaved-changes confirmation (`ChainCreate.tsx` cancels through
 * `closeContainingModal` with no dirty check), so there is no scenario for one.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { tokenized } from "../../support/run.js";

test("the chain dialog focuses the name field and opens the chain it creates", { tag: ["@ui", "@tier1"] }, async ({ page, folder, run }) => {
  const chains = new ChainsPage(page);
  const name = tokenized(run, "ui-chain-create");

  await chains.gotoFolder(folder.id);
  await chains.create("New Chain");
  const dialog = page.getByRole("dialog", { name: "New Chain" });
  // Focus on the name field is a fix the project shipped in 8eb6753ec. Typing without a click is
  // what a user does next.
  await expect(dialog.getByRole("textbox", { name: "Name" })).toBeFocused();
  await page.keyboard.type(name);
  await dialog.getByRole("button", { name: "Submit" }).click();

  // `Open chain` is checked by default, so the dialog lands on the chain, inside the open folder.
  await expect(page).toHaveURL(/\/chains\/[0-9a-f-]{36}(\/graph)?$/);
  await expect(page).toHaveTitle(name);
  const breadcrumb = page.getByRole("navigation").filter({ hasText: name });
  await expect(breadcrumb.getByRole("link", { name: folder.name, exact: true })).toBeVisible();
});

test("a chain cut into a new folder moves under it in the tree", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-chain-move"), folder.id);
  const target = tokenized(run, "ui-folder-create");
  const chains = new ChainsPage(page);

  await chains.gotoFolder(folder.id);
  await expect(chains.row(chain.name)).toBeVisible();
  await chains.create("New Folder");
  const dialog = page.getByRole("dialog", { name: "New Folder" });
  await expect(dialog.getByRole("textbox", { name: "Name" })).toBeFocused();
  await page.keyboard.type(target);
  await dialog.getByRole("checkbox", { name: "Open folder" }).uncheck();
  await dialog.getByRole("button", { name: "Create" }).click();
  await expect(dialog).toBeHidden();

  await chains.rowAction(chain.name, "Cut");
  await chains.rowAction(target, "Paste");

  // The list re-parents the chain in its own state, so it leaves the top level of the open folder
  // and shows only while the new folder is expanded.
  const expand = chains.row(target).getByRole("button", { name: "Expand row", expanded: false });
  await expect(chains.row(chain.name)).toHaveCount(0);
  await expand.click();
  await expect(chains.row(chain.name)).toBeVisible();
  await chains.row(target).getByRole("button", { name: "Collapse row", expanded: true }).click();
  await expect(chains.row(chain.name)).toHaveCount(0);
});

test("a chain deleted from its row leaves the list", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-chain-delete"), folder.id);
  const chains = new ChainsPage(page);

  await chains.gotoFolder(folder.id);
  await chains.rowAction(chain.name, "Delete");
  const confirm = page.getByRole("dialog").filter({ hasText: "Delete Chain" });
  await confirm.getByRole("button", { name: "OK" }).click();

  await expect(confirm).toBeHidden();
  await expect(chains.row(chain.name)).toHaveCount(0);
});
