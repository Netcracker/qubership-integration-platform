/**
 * The chains list: a chain the API created is found by the run token and opens.
 *
 * What the API layer cannot see is the navigation: that the list's search reaches the chain inside
 * its folder, and that the link lands on that chain's page with its folder path loaded.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { tokenized } from "../../support/run.js";

test("a chain created through the API is found by the run token and opens", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-chains-list"), folder.id);
  const chains = new ChainsPage(page);

  await chains.goto();
  await chains.search(`e2e-${run}`);
  // The search answers with the worker folder collapsed, so the chain is reached through it.
  await chains.link(folder.name).click();
  await chains.link(chain.name).click();

  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}(/graph)?$`));
  // Both come from the chain the page loaded by id: the title from its name, the breadcrumb from
  // its navigation path.
  await expect(page).toHaveTitle(chain.name);
  const breadcrumb = page.getByRole("navigation").filter({ hasText: chain.name });
  await expect(breadcrumb.getByRole("link", { name: folder.name, exact: true })).toBeVisible();
});
