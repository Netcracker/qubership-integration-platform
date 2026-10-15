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
 * The case deletes one of the chain's two sessions. On this page, a delete of every loaded session
 * sends the bare `DELETE /v1/sessions`, which removes every session on the platform: `Sessions.tsx`
 * passes its route's chain id, which the admin page has none of, to `deleteSessionsByChainId`. So the
 * case waits for both rows before it selects one, and the selection never covers the whole list.
 */
import { test, expect } from "../../support/page-guard.js";
import { FilterDialog } from "../../pages/FilterDialog.js";
import { TableView } from "../../pages/TableView.js";
import { ENGINE_CASE_TIMEOUT, SEED_LOGGING, waitForDeployed, waitForRoutes } from "../../support/corpus.js";
import { createStepsChain } from "../../support/deployable.js";
import { waitForRecording, withBuilt } from "../../support/cleanup.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { callToken } from "../../support/run.js";

test("a session found by its chain's name opens to its trace, and its delete leaves the other session", { tag: ["@ui", "@tier1"] }, async ({ page, request, catalog, env, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await createStepsChain(
      catalog,
      run,
      {
        // A call token keeps the chain name, the filter value, apart from every other chain's.
        what: callToken("ui-admin-sessions"),
        parentId: folder.id,
        steps: [{ name: "Mark", type: "header-modification", properties: { headerModificationToAdd: { "e2e-ui": "admin-sessions" }, headerModificationToRemove: [] } }],
        logging: SEED_LOGGING,
      },
      built.chains,
    );
    await catalog.deploy(chain.id, (await catalog.createSnapshot(chain.id)).id);
    await waitForDeployed(catalog, [chain]);
    await waitForRoutes(env, [chain]);
    await waitForRecording(env, sessions, chain);
    const recorded = async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
      expect(call.response.status()).toBe(200);
      return await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
    };
    const opened = await recorded();
    const kept = await recorded();
    // The id is an anchor with no href, so it carries no link role.
    const rowOf = (sessionId: string) => new TableView(page).rows.filter({ has: page.getByText(sessionId, { exact: true }) });

    await page.goto("/admintools/sessions");
    await new FilterDialog(page).apply("Chain", chain.name);
    // The warm-up calls recorded sessions as well, so the list holds more rows than these two.
    await expect(rowOf(opened.id)).toBeVisible();
    await expect(rowOf(kept.id)).toBeVisible();
    await expect(page.getByRole("row").filter({ hasNot: page.getByText(chain.name, { exact: true }) }).filter({ has: page.getByRole("cell") })).toHaveCount(0);

    await rowOf(opened.id).getByText(opened.id, { exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/sessions/${opened.id}$`));
    expect(elementNames(opened)).toEqual([...HTTP_TRIGGER_STEPS, "Mark"]);
    await expect(page.getByRole("row").filter({ has: page.getByText("HTTP Trigger", { exact: true }) })).toBeVisible();
    await expect(page.getByRole("row").getByText("Mark", { exact: true })).toBeVisible();

    // The filter is kept in the browser's storage, so the list comes back filtered.
    await page.goBack();
    await expect(rowOf(kept.id)).toBeVisible();
    await rowOf(opened.id).getByRole("checkbox").check();
    await page.getByTestId("sessions-delete").click();
    const confirm = page.getByRole("dialog").filter({ hasText: "Delete Sessions" });
    await confirm.getByRole("button", { name: "OK" }).click();
    await expect(confirm).toBeHidden();
    await expect(rowOf(opened.id)).toHaveCount(0);
    await expect(rowOf(kept.id)).toBeVisible();
    expect((await sessions.rawSession(opened.id)).status()).toBe(404);
    expect((await sessions.rawSession(kept.id)).status()).toBe(200);
  });
});
