/**
 * A chain's own properties: the Properties tab, the move to another folder, and the guard against
 * leaving the tab with unsaved changes.
 *
 * What the API cannot see: which fields the form sends on Apply, that the header shows the new name
 * with no reload, that Cut and Paste on the chains list send the move, and that the router blocks a
 * tab change while the form holds an edit. The guard covers in-app navigation only: the page sets no
 * `beforeunload` handler, so a reload or a typed URL leaves without the dialog.
 */
import type { Locator, Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { tokenized } from "../../support/run.js";

/** A form field by its label, which antd names with a trailing colon and, when required, a leading `* `. */
function field(page: Page, role: "textbox" | "combobox", label: string): Locator {
  return page.getByRole(role, { name: new RegExp(`^(\\* )?${label} :$`) });
}

test("a name, description, and label applied on the properties tab are saved", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-props"), folder.id);
  const renamed = tokenized(run, "ui-props-renamed");
  const label = tokenized(run, "ui-props-label");
  const tabs = new ChainTabsPage(page);

  await tabs.goto(chain.id, "properties");
  const name = field(page, "textbox", "Name");
  await expect(name).toHaveValue(chain.name);
  // Nothing to save until the form holds a change.
  await expect(tabs.apply).toBeDisabled();
  await name.fill(renamed);
  await field(page, "textbox", "Description").fill("set in the browser");
  const labels = field(page, "combobox", "Labels");
  await labels.fill(label);
  await labels.press("Enter");
  const saved = page.waitForResponse((response) => response.request().method() === "PUT" && response.url().endsWith(`/chains/${chain.id}`));
  await tabs.apply.click();
  expect((await saved).ok()).toBe(true);

  const stored = await catalog.getChain(chain.id);
  expect({ name: stored.name, description: stored.description, labels: stored.labels?.map((each) => each.name) }).toEqual({
    name: renamed,
    description: "set in the browser",
    labels: [label],
  });
  // The header reads the chain the page holds, so it shows the new name in place.
  await expect(page).toHaveTitle(renamed);
  await expect(tabs.apply).toBeDisabled();
  // A saved form holds no change, so a tab change opens no dialog.
  await tabs.tab("Graph").click();
  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph$`));
  await expect(page.getByRole("dialog", { name: "Unsaved Changes" })).toHaveCount(0);
});

test("leaving the properties tab with an unsaved edit asks first, and discards the edit on Yes", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-props-blocker"), folder.id);
  const edited = tokenized(run, "ui-props-unsaved");
  const tabs = new ChainTabsPage(page);
  const unsaved = page.getByRole("dialog", { name: "Unsaved Changes" });

  await tabs.goto(chain.id, "properties");
  const name = field(page, "textbox", "Name");
  await expect(name).toHaveValue(chain.name);
  await name.fill(edited);

  await tabs.tab("Graph").click();
  await expect(unsaved).toBeVisible();
  // The dialog asks whether to leave, so "No" stays on the tab and keeps the edit.
  await unsaved.getByRole("button", { name: "No", exact: true }).click();
  await expect(unsaved).toBeHidden();
  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/properties$`));
  await expect(name).toHaveValue(edited);

  await tabs.tab("Graph").click();
  await expect(unsaved).toBeVisible();
  await unsaved.getByRole("button", { name: "Yes", exact: true }).click();
  await expect(unsaved).toBeHidden();
  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph$`));
  expect((await catalog.getChain(chain.id)).name, "the discarded edit reached the catalog").toBe(chain.name);
});

test("a chain cut on the chains list and pasted on a folder moves into that folder", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const target = await catalog.createFolder(tokenized(run, "ui-move-target"), folder.id);
  const chain = await catalog.createChain(tokenized(run, "ui-move"), folder.id);
  const chains = new ChainsPage(page);

  await chains.gotoFolder(folder.id);
  await expect(chains.row(chain.name)).toBeVisible();
  await chains.rowAction(chain.name, "Cut");
  await chains.rowAction(target.name, "Paste");

  await expect.poll(async () => (await catalog.getChain(chain.id)).parentId).toBe(target.id);
  // The list drops the row it moved without a reload.
  await expect(chains.row(chain.name)).toBeHidden();
  expect((await catalog.listNestedChains(target.id)).map((each) => each.id)).toEqual([chain.id]);
});
