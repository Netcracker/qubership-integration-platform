/**
 * The chain's Masking tab: a field added in the browser is masked in the session of the next
 * deployment.
 *
 * What the API cannot see: that the add button creates a row whose name the inline editor then
 * writes, rather than a second empty field. The chain is built and deployed through the API, and the
 * session is read through it; the browser adds the field only. `specs/runtime/masking-runtime.spec.ts`
 * covers the engine half on a seeded field.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { TableView } from "../../pages/TableView.js";
import { ENGINE_CASE_TIMEOUT, MASKING_MARKER, SEED_LOGGING } from "../../support/corpus.js";
import { createStepsChain, markStep } from "../../support/deployable.js";
import { deployAndRecord, withBuilt } from "../../support/cleanup.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS, trace } from "../../support/sessions.js";
import { callToken } from "../../support/run.js";

const MASKED_FIELD = "uiSecret";

/** A value nothing else sends, so finding it in a trace is unambiguous. */
const SECRET = "5500000000000004";

test("a field added on the masking tab is masked in the session of the next deployment", { tag: ["@ui", "@tier1"] }, async ({ page, request, catalog, env, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await createStepsChain(
      catalog,
      run,
      {
        // A call token in the route keeps repeats of the case apart.
        what: callToken("ui-masking"),
        parentId: folder.id,
        steps: [markStep("masking")],
        logging: SEED_LOGGING,
      },
      built.chains,
    );
    const tabs = new ChainTabsPage(page);
    const table = new TableView(page);

    await tabs.goto(chain.id, "masking");
    await expect(page.getByText("No masked fields")).toBeVisible();
    const created = page.waitForResponse((response) => response.request().method() === "POST" && response.url().endsWith("/masking"));
    await page.getByTestId("masking-create").click();
    expect((await created).ok()).toBe(true);
    // The new field has no name yet, so its inline editor's button is the row's only button.
    await expect(table.rows).toHaveCount(1);
    await table.rows.getByRole("button").click();
    const field = table.rows.getByRole("textbox");
    await expect(field).toBeFocused();
    await field.fill(MASKED_FIELD);
    await field.press("Enter");

    await expect.poll(async () => (await catalog.listMaskedFields(chain.id)).map((each) => each.name)).toEqual([MASKED_FIELD]);
    await expect(table.rows.getByRole("button", { name: MASKED_FIELD, exact: true })).toBeVisible();

    // Masked fields reach the engine inside the deployment, so the deploy follows the edit.
    await deployAndRecord(catalog, env, sessions, chain);

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { [MASKED_FIELD]: SECRET, note: "visible" } });
    expect(call.response.status()).toBe(200);
    const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Mark"]);
    const recorded = trace(session).flatMap((step) => [step.bodyBefore ?? "", step.bodyAfter ?? ""]);
    expect(recorded.filter((body) => body.includes(SECRET)), `"${MASKED_FIELD}" reached the session in the clear`).toEqual([]);
    expect(recorded.some((body) => body.includes(MASKING_MARKER))).toBe(true);
    expect(recorded.some((body) => body.includes("visible"))).toBe(true);
  });
});
