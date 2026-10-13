/**
 * The chain page's snapshots, deployments, and sessions tabs.
 *
 * What the API cannot see: the header's unsaved-changes tag clearing once a snapshot is built, an
 * inline rename closing its editor (issue #679), a label keeping its size when its cell opens for
 * editing (#679 again), a deployment's status badge updating from the event poll, and the element
 * trace a session opens to. The catalog, the engine and the session store answer the same way
 * whether or not any of these render.
 */
import type { Locator } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { TableView } from "../../pages/TableView.js";
import { DEPLOY_TIMEOUT, readCorpusState, seedChain } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";
import { callChain } from "../../support/sessions.js";
import { leftBehind } from "../../support/teardown.js";

/** What a reader compares between a label in its cell and the same label in the cell's editor. */
async function labelBox(label: Locator): Promise<{ fontSize: string; height: number; left: number }> {
  return label.evaluate((element) => {
    const box = element.getBoundingClientRect();
    return { fontSize: getComputedStyle(element).fontSize, height: Math.round(box.height), left: Math.round(box.x) };
  });
}

test("a snapshot built on the snapshots tab clears the unsaved-changes tag with no reload", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-tabs-snapshot"), folder.id);
  await catalog.createElement(chain.id, "log-record");
  const tabs = new ChainTabsPage(page);

  await tabs.goto(chain.id, "snapshots");
  // An element added since the last snapshot is an unsaved change.
  await expect(tabs.unsavedChanges).toBeVisible();
  await tabs.createSnapshot.click();

  await expect.poll(async () => (await catalog.listSnapshots(chain.id)).length).toBe(1);
  const [snapshot] = await catalog.listSnapshots(chain.id);
  await expect(page.getByRole("button", { name: snapshot.name, exact: true })).toBeVisible();
  // The tab refetches the chain after the build, so the header drops the tag in place.
  await expect(tabs.unsavedChanges).toBeHidden();
});

test("a snapshot renamed inline returns its cell to the read state", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-tabs-rename"), folder.id);
  const snapshot = await catalog.createSnapshot(chain.id);
  const byEnter = tokenized(run, "ui-snapshot-enter");
  const byTab = tokenized(run, "ui-snapshot-tab");
  const tabs = new ChainTabsPage(page);

  await tabs.goto(chain.id, "snapshots");
  // The table's only inline editor; an input's value is not row text, so no row filter finds it.
  const field = page.getByRole("row").getByRole("textbox");
  await page.getByRole("button", { name: snapshot.name, exact: true }).click();
  await expect(field).toBeFocused();
  await field.fill(byEnter);
  await field.press("Enter");

  // Issue #679: the rename saved and the editor opened again, which read as a refused save.
  await expect.poll(async () => (await catalog.getSnapshot(chain.id, snapshot.id, true)).name).toBe(byEnter);
  await expect(page.getByRole("button", { name: byEnter, exact: true })).toBeVisible();
  await expect(field).toHaveCount(0);

  // Leaving the field commits too; before the fix it left the editor open until Enter.
  await page.getByRole("button", { name: byEnter, exact: true }).click();
  await field.fill(byTab);
  await page.keyboard.press("Tab");

  await expect.poll(async () => (await catalog.getSnapshot(chain.id, snapshot.id, true)).name).toBe(byTab);
  await expect(page.getByRole("button", { name: byTab, exact: true })).toBeVisible();
  await expect(field).toHaveCount(0);
});

test("a snapshot label keeps its size when its cell opens for editing", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-tabs-labels"), folder.id);
  const snapshot = await catalog.createSnapshot(chain.id);
  const label = tokenized(run, "ui-label");
  await catalog.renameSnapshot(chain.id, snapshot.id, snapshot.name, [{ name: label, technical: false }]);
  const tabs = new ChainTabsPage(page);

  await tabs.goto(chain.id, "snapshots");
  const row = new TableView(page).row(label);
  const read = row.getByText(label, { exact: true });
  await expect(read).toBeVisible();
  const inCell = await labelBox(read);
  await read.click();

  // Issue #679: the cell drew labels smaller than the chips its editor shows.
  const chip = row.getByTitle(label, { exact: true });
  await expect(chip).toBeVisible();
  await expect.poll(() => labelBox(chip)).toEqual(inCell);
});

test("a deployment made on the deployments tab shows its engine's badge as deployed with no reload", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, { prefix: "ui-tabs", what: "deploy", parentId: folder.id });
  const snapshot = await catalog.createSnapshot(chain.id);
  const tabs = new ChainTabsPage(page);
  const table = new TableView(page);

  try {
    await tabs.goto(chain.id, "deployments");
    await tabs.deploy(snapshot.name);
    await expect(tabs.deploymentDialog).toBeHidden();

    // The create response carries no runtime state, so the badge comes only from the refetch a
    // deployment event on `GET /v1/catalog/events` triggers. Its text is the engine's host.
    await expect(table.row(snapshot.name).getByTestId("deployment-status-deployed")).toBeVisible({ timeout: DEPLOY_TIMEOUT });
  } finally {
    await catalog.undeployAll(chain.id).catch(leftBehind(`chain ${chain.name}`, "undeployed"));
  }
});

test("a seed chain's session is found on its sessions tab and opens to its element trace", { tag: ["@ui", "@tier1"] }, async ({ page, request, env, sessions }) => {
  const corpus = readCorpusState();
  const chain = seedChain(corpus, "http-echo");
  const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "ui" } });
  expect(response.status()).toBe(200);
  const session = await sessions.byExternalId(token, { elements: 3 });
  const tabs = new ChainTabsPage(page);

  await tabs.goto(chain.id, "sessions");
  // The token is in the call's headers, which the tab's full-text search covers.
  await tabs.sessionSearch.fill(token);
  // The id and the element names are anchors with no href, so they carry no link role.
  const listed = page.getByRole("row").getByText(session.id, { exact: true });
  await expect(listed).toBeVisible();
  await listed.click();
  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/sessions/${session.id}$`));

  const trigger = page.getByRole("row").filter({ has: page.getByText("HTTP Trigger", { exact: true }) });
  const validate = page.getByRole("row").getByText("Validate Request", { exact: true });
  await expect(trigger).toBeVisible();
  // The trigger's own step is nested under it and starts collapsed.
  await expect(validate).toBeHidden();
  await trigger.getByRole("button", { name: "Expand row" }).click();
  await expect(validate).toBeVisible();

  await page.getByRole("row").getByText("Header Modification", { exact: true }).click();
  const details = page.getByRole("dialog").filter({ hasText: "Header Modification" });
  await details.getByRole("tab", { name: "Headers" }).click();
  // The header the fixture adds, valued with the token the corpus was rendered with.
  await expect(details.getByRole("row").filter({ hasText: "e2e-fixture" })).toContainText(corpus.run);
});
