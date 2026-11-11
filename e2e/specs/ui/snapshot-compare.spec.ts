/**
 * The Snapshots tab's Compare and Revert to.
 *
 * What the API cannot see: that Compare opens the diff of the two selected rows and its table shows
 * the property that differs, and that `Revert to` in a row's menu, once confirmed, reverts the chain
 * to that row's snapshot. `specs/api/snapshots.spec.ts` covers what the revert writes back.
 *
 * The two snapshots differ in one element property, the sender of a Log Record. The case fails when
 * the diff omits either value, and when the revert leaves the later value in place or reverts to the
 * other snapshot.
 *
 * Shown failing: the second snapshot's sender expected after the revert fails the case.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { callToken, tokenized } from "../../support/run.js";

test.describe("snapshot compare and revert", () => {
  test("Compare shows the changed property and Revert restores it", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, callToken("ui-snapshot-compare")), folder.id);
    const element = await catalog.createElement(chain.id, "log-record");
    const first = tokenized(run, "ui-sender-first");
    const second = tokenized(run, "ui-sender-second");
    await catalog.patchElementProperties(chain.id, element.id, { sender: first });
    const older = await catalog.createSnapshot(chain.id);
    await catalog.patchElementProperties(chain.id, element.id, { sender: second });
    const newer = await catalog.createSnapshot(chain.id);
    const tabs = new ChainTabsPage(page);

    await tabs.goto(chain.id, "snapshots");
    await tabs.snapshotRow(older.name).getByRole("checkbox").check();
    await tabs.snapshotRow(newer.name).getByRole("checkbox").check();
    await tabs.compareSnapshots.click();
    const diff = page.getByRole("dialog", { name: "Chain compare" });
    await diff.getByText("Table", { exact: true }).click();

    // The table shows each value as JSON, so the strings keep their quotes.
    const changed = diff.getByRole("row").filter({ hasText: "element property" }).filter({ hasText: `"${first}"` });
    await expect(changed).toContainText(`"${second}"`);
    await diff.getByRole("button", { name: "Close" }).click();
    await expect(diff).toBeHidden();

    await tabs.revertTo(older.name);
    // The revert opens the graph once the catalog has answered.
    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph$`));
    expect((await catalog.getElement(chain.id, element.id)).properties.sender).toBe(first);
  });
});
