/**
 * Sessions under Admin Tools: the list across chains, filtered by chain name, a session opened to
 * its trace, and one session deleted.
 *
 * What the API cannot see: that the Filter dialog's Chain filter reaches the session search, that
 * the id opens the session's trace on its chain's page, and that the row leaves with no reload.
 * The chain is built, deployed and called through the API. Its name carries a call token, so the
 * filter matches no other chain; `scheduler-basic`, whose sessions are in every list, does not
 * match it either.
 *
 * The first case deletes one of the chain's two sessions. The second selects every loaded row and
 * reads the delete the page sends, which names those sessions. Until #1108 it sent the bare
 * `DELETE /v1/sessions`, which removes every session on the platform, so the second case answers
 * the page's delete in the browser and never lets it reach sessions-management.
 */
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { FilterDialog } from "../../pages/FilterDialog.js";
import { TableView } from "../../pages/TableView.js";
import { ENGINE_CASE_TIMEOUT, SEED_LOGGING, type DeployedChain } from "../../support/corpus.js";
import { createStepsChain, markStep, type BuiltChain } from "../../support/deployable.js";
import { deployAndRecord, withBuilt } from "../../support/cleanup.js";
import { callChain, HTTP_TRIGGER_STEPS, type RecordedSession, type Sessions } from "../../support/sessions.js";
import type { Catalog } from "../../support/catalog.js";
import type { Env } from "../../env/index.js";
import { callToken } from "../../support/run.js";

/** Builds and deploys a chain named with a call token, and records two of its sessions. */
async function recordedChain(
  { request, catalog, env, sessions }: { request: APIRequestContext; catalog: Catalog; env: Env; sessions: Sessions },
  run: string,
  parentId: string,
  built: BuiltChain[],
): Promise<{ chain: DeployedChain; opened: RecordedSession; kept: RecordedSession }> {
  const chain = await createStepsChain(
    catalog,
    run,
    // A call token keeps the chain name, the filter value, apart from every other chain's.
    { what: callToken("ui-admin-sessions"), parentId, steps: [markStep("admin-sessions")], logging: SEED_LOGGING },
    built,
  );
  await deployAndRecord(catalog, env, sessions, chain);
  const recorded = async () => {
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    expect(call.response.status()).toBe(200);
    return await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
  };
  return { chain, opened: await recorded(), kept: await recorded() };
}

/** A session's row; the id is an anchor with no href, so it carries no link role. */
function rowOf(page: Page, sessionId: string): Locator {
  return new TableView(page).rows.filter({ has: page.getByText(sessionId, { exact: true }) });
}

/** The data rows that do not name `chainName`. */
function foreignRows(page: Page, chainName: string): Locator {
  return page.getByRole("row").filter({ hasNot: page.getByText(chainName, { exact: true }) }).filter({ has: page.getByRole("cell") });
}

test("a session found by its chain's name opens to its trace, and its delete leaves the other session", { tag: ["@ui", "@tier1"] }, async ({ page, request, catalog, env, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const { chain, opened, kept } = await recordedChain({ request, catalog, env, sessions }, run, folder.id, built.chains);

    await page.goto("/admintools/sessions");
    await new FilterDialog(page).apply("Chain", chain.name);
    // The warm-up calls recorded sessions as well, so the list holds more rows than these two.
    await expect(rowOf(page, opened.id)).toBeVisible();
    await expect(rowOf(page, kept.id)).toBeVisible();
    await expect(foreignRows(page, chain.name)).toHaveCount(0);

    await rowOf(page, opened.id).getByText(opened.id, { exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/sessions/${opened.id}$`));
    await expect(page.getByRole("row").filter({ has: page.getByText("HTTP Trigger", { exact: true }) })).toBeVisible();
    await expect(page.getByRole("row").getByText("Mark", { exact: true })).toBeVisible();

    // The filter is kept in the browser's storage, so the list comes back filtered.
    await page.goBack();
    await expect(rowOf(page, kept.id)).toBeVisible();
    await rowOf(page, opened.id).getByRole("checkbox").check();
    await page.getByTestId("sessions-delete").click();
    const confirm = page.getByRole("dialog").filter({ hasText: "Delete Sessions" });
    await confirm.getByRole("button", { name: "OK" }).click();
    await expect(confirm).toBeHidden();
    await expect(rowOf(page, opened.id)).toHaveCount(0);
    await expect(rowOf(page, kept.id)).toBeVisible();
    expect((await sessions.rawSession(opened.id)).status()).toBe(404);
    expect((await sessions.rawSession(kept.id)).status()).toBe(200);
  });
});

test("a delete of every loaded session on the admin page removes those sessions only", { tag: ["@ui", "@tier2"] }, async ({ page, request, catalog, env, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  // Every delete the page sends is answered here and recorded, so a regression to the bare delete
  // cannot wipe the platform.
  const deletes: { sent: string; ids: unknown }[] = [];
  await page.route(/\/sessions-management\/sessions/, async (route) => {
    const sent = route.request();
    if (sent.method() !== "DELETE" && !sent.url().endsWith("/sessions/bulk-delete")) return route.continue();
    deletes.push({ sent: `${sent.method()} ${new URL(sent.url()).pathname}`, ids: sent.postDataJSON() });
    return route.fulfill({ status: 200 });
  });
  await withBuilt(catalog, async (built) => {
    const { chain, opened, kept } = await recordedChain({ request, catalog, env, sessions }, run, folder.id, built.chains);
    await page.goto("/admintools/sessions");
    await new FilterDialog(page).apply("Chain", chain.name);
    await expect(rowOf(page, opened.id)).toBeVisible();
    await expect(rowOf(page, kept.id)).toBeVisible();
    // The unfiltered list shows the two rows too, so the selection waits for the filter to apply.
    await expect(foreignRows(page, chain.name)).toHaveCount(0);
    await page.getByRole("row").filter({ has: page.getByRole("columnheader") }).getByRole("checkbox").check();
    // The answer, not the request: the route records a delete before it answers.
    const answered = page.waitForResponse((each) => each.request().method() === "DELETE" || each.url().endsWith("/sessions/bulk-delete"));
    await page.getByTestId("sessions-delete").click();
    const confirm = page.getByRole("dialog").filter({ hasText: "Delete Sessions" });
    await confirm.getByRole("button", { name: "OK" }).click();
    await answered;
    // The page sent the bare `DELETE /v1/sessions` here until #1108.
    expect(deletes.map((each) => each.sent)).toEqual(["POST /api/v1/cip/sessions-management/sessions/bulk-delete"]);
    expect(new Set(deletes[0].ids as string[])).toEqual(new Set([opened.id, kept.id]));
  });
});
