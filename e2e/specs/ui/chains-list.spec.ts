/**
 * The chains list: a chain the API created is found by the run token and opens.
 *
 * What the API layer cannot see is the navigation: that the list's search reaches the chain inside
 * its folder, and that the link lands on that chain's page with its folder path loaded.
 */
import { test } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { expectChainOpened } from "../../support/chain-opened.js";
import { tokenized } from "../../support/run.js";

test.describe("chains list", () => {
  test("the run-token search finds an API-made chain and opens it", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-chains-list"), folder.id);
    const chains = new ChainsPage(page);

    await chains.goto();
    await chains.search(`e2e-${run}`);
    // The search answers with the worker folder collapsed, so the chain is reached through it.
    await chains.link(folder.name).click();
    await chains.link(chain.name).click();

    await expectChainOpened(page, chain, folder);
  });
});
