/**
 * Access Control under Admin Tools: roles added in the pop-up, then the bulk Redeploy.
 *
 * What the API cannot see: that the pop-up merges the typed role into the selected trigger's roles,
 * that the row shows it with no reload, and that Redeploy sends the selected chain. The chain is
 * built and deployed through the API, and the trigger and the deployments are read through it;
 * `specs/api/chain-roles.spec.ts` covers the search, the update, and the redeploy endpoint.
 *
 * The engine serves every route `permitAll`, so a role changes what the catalog stores and deploys,
 * and no call to the route can tell the two snapshots apart: the case reads the role in the XML of
 * the snapshot the redeploy built.
 */
import { test, expect } from "../../support/page-guard.js";
import { FilterDialog } from "../../pages/FilterDialog.js";
import { TableView } from "../../pages/TableView.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT, waitForDeployed } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { callToken } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

test("a role added in the pop-up is written to the trigger, and Redeploy deploys it as a new snapshot", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  // A call token in the route keeps repeats of the case apart.
  const chain = await tokenizedChain(catalog, run, { prefix: "ui-access", what: callToken("roles"), parentId: folder.id });
  const role = callToken("ui-role");
  const table = new TableView(page);
  // The Endpoint column links the trigger's context path, which is the chain's name.
  const row = table.rows.filter({ has: page.getByRole("link", { name: chain.contextPath, exact: true }) });
  const first = await catalog.createSnapshot(chain.id);
  try {
    await catalog.deploy(chain.id, first.id);
    await waitForDeployed(catalog, [chain]);

    await page.goto("/admintools/access-control");
    // The list loads 30 rows at a time, so the chain is found by the server-side filter.
    await new FilterDialog(page).apply("Chain", chain.name);
    await expect(table.rows).toHaveCount(1);
    await expect(row).toBeVisible();
    await row.getByRole("checkbox").check();

    await page.getByTestId("access-control-add-roles").click();
    const dialog = page.getByRole("dialog", { name: "Add Roles" });
    const roles = dialog.getByRole("combobox");
    await roles.fill(role);
    await roles.press("Enter");
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toBeHidden();
    await expect(row.getByText(role, { exact: true })).toBeVisible();
    const trigger = await catalog.getElement(chain.id, chain.triggerId);
    expect(trigger.properties).toMatchObject({ roles: [role], accessControlType: "RBAC" });
    expect((await catalog.listDeployments(chain.id)).map((each) => each.snapshotId), "Save alone deploys nothing").toEqual([first.id]);

    const redeploy = page.getByTestId("access-control-redeploy");
    await expect(redeploy).toBeEnabled();
    await redeploy.click();
    const confirm = page.getByRole("dialog").filter({ hasText: "Redeploy Chains" });
    await confirm.getByRole("button", { name: "OK" }).click();
    await expect(confirm).toBeHidden();

    await expect.poll(async () => (await catalog.listSnapshots(chain.id)).length).toBe(2);
    const rebuilt = (await catalog.listSnapshots(chain.id)).find((each) => each.id !== first.id)!;
    // The superseded deployment row is pruned once the engine reports, about five seconds later.
    await expect
      .poll(async () => (await catalog.listDeployments(chain.id)).map((each) => each.snapshotId), { timeout: DEPLOY_TIMEOUT })
      .toEqual([rebuilt.id]);
    expect((await catalog.getChain(chain.id)).unsavedChanges, "the redeploy saves the chain").toBe(false);
    expect((await catalog.getSnapshot(chain.id, rebuilt.id)).xmlDefinition, "the deployed snapshot compiles the role").toContain(role);
    expect((await catalog.getSnapshot(chain.id, first.id)).xmlDefinition).not.toContain(role);
  } finally {
    await catalog.undeployAll(chain.id).catch(leftBehind(`chain ${chain.name}`, "undeployed"));
  }
});
