/**
 * A chain's own properties: the Properties tab, and the guard against leaving it with unsaved
 * changes. The move to another folder is in `chain-create.spec.ts`.
 *
 * What the API cannot see: which fields the form sends on Apply, that the header shows the new name
 * with no reload, and that the router blocks a tab change while the form holds an edit. The guard
 * covers in-app navigation only: the page sets no `beforeunload` handler, so a reload or a typed URL
 * leaves without the dialog.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { tokenized } from "../../support/run.js";

test.describe("chain properties", () => {
  test("an applied name, description, and label are saved", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-props"), folder.id);
    const renamed = tokenized(run, "ui-props-renamed");
    const label = tokenized(run, "ui-props-label");
    const tabs = new ChainTabsPage(page);

    await tabs.goto(chain.id, "properties");
    const name = tabs.field("textbox", "Name");
    await expect(name).toHaveValue(chain.name);
    // Nothing to save until the form holds a change.
    await expect(tabs.apply).toBeDisabled();
    await name.fill(renamed);
    await tabs.field("textbox", "Description").fill("set in the browser");
    const labels = tabs.field("combobox", "Labels");
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
    // The header and the tab title read the chain the page holds, so both show the new name in place.
    await expect(tabs.breadcrumbItem(renamed)).toBeVisible();
    await expect(page).toHaveTitle(renamed);
    await expect(tabs.apply).toBeDisabled();
    // A saved form holds no change, so a tab change opens no dialog.
    await tabs.tab("Graph").click();
    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph$`));
    await expect(page.getByRole("dialog", { name: "Unsaved Changes" })).toHaveCount(0);
  });

  test("leaving with an unsaved edit asks, and Yes discards it", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-props-blocker"), folder.id);
    const edited = tokenized(run, "ui-props-unsaved");
    const tabs = new ChainTabsPage(page);
    const unsaved = page.getByRole("dialog", { name: "Unsaved Changes" });

    await tabs.goto(chain.id, "properties");
    const name = tabs.field("textbox", "Name");
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
    // The URL changes before the tab strip redraws, and a click on the tab that still reads as
    // selected changes nothing: CI run 38936680246 stayed on Graph that way.
    await expect(tabs.tab("Graph")).toHaveAttribute("aria-selected", "true");
    // Back on the tab, the form reads the saved chain again, so the edit is gone.
    await tabs.tab("Properties").click();
    await expect(name).toHaveValue(chain.name);
    expect((await catalog.getChain(chain.id)).name, "the discarded edit reached the catalog").toBe(chain.name);
  });
});
