/**
 * Admin tools: variables typed into the UI, and a live exchange terminated from it.
 *
 * The two cases that create a variable have the creation flow as their subject. Each
 * asserts what only the browser decides: the keys that submit or break a line, how the list draws a
 * value, and where a secured value stays once it is sent.
 *
 * The secured cases run on the named-secret path, in the committed fixture secret. On this stack
 * the default secret is disabled: `GET /v2/secured-variables` reports it with `disabled: true`, the
 * screen hides its row behind a migration banner, and no variable can be added to it. A case on the
 * default secret would assert a deprecated path that the stack no longer serves.
 *
 * No case relies on the list to keep a secured value hidden. The list is built from
 * `GET /v2/secured-variables/{secret}`, which returns names only, so no UI change could make a value
 * appear there. The typed value lives only in the browser, so that is where the case looks for it.
 *
 * Three scenarios an earlier draft listed are left out, because the API layer sees everything they
 * would assert. Filtering the audit log: `specs/api/audit.spec.ts` finds a known action's entry by
 * the run token. Listing the domains: the catalog's domains endpoint reports the same rows. Listing
 * and killing live exchanges: `specs/global/live-exchanges.spec.ts` covers both. What remains of
 * live exchanges is the confirmation dialog in front of the kill.
 */
import type { Locator, Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { TableView } from "../../pages/TableView.js";
import { ENGINE_CASE_TIMEOUT, readCorpusState, seedChain } from "../../support/corpus.js";
import { hold, untilListed } from "../../support/held-call.js";
import { SECRET_FIXTURE_NAME, callToken, tokenized } from "../../support/run.js";
import { ensureSecretFixture } from "../../support/secret-fixture.js";
import { leftBehind } from "../../support/teardown.js";

/**
 * The row that shows `text` in a cell. An expanded secret row nests a whole table, so the row that
 * holds no table of its own is the one meant.
 */
function rowOf(page: Page, text: string): Locator {
  return page
    .getByRole("row")
    .filter({ has: page.getByText(text, { exact: true }) })
    .filter({ hasNot: page.getByRole("table") });
}

/**
 * How long the `long-running` fixture holds its exchange. A kill takes effect only once the hold
 * ends, so this is also how long the kill case waits for the chain to answer.
 */
const HOLD_MS = 25_000;

test("a common variable typed over two lines is stored whole and listed by its first line", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  const key = tokenized(run, "ui-common");
  const table = new TableView(page);

  await page.goto("/admintools/variables/common");
  await page.getByTestId("common-variables-add").click();
  const keyField = page.getByPlaceholder("Key");
  await expect(keyField).toBeFocused();
  try {
    await keyField.fill(key);
    // Shift+Enter breaks the line, and Enter alone submits the row.
    await page.getByPlaceholder("Value").click();
    await page.keyboard.type("first line");
    await page.keyboard.press("Shift+Enter");
    await page.keyboard.type("second line");
    await page.keyboard.press("Enter");
    // The editor row closes only after the create succeeds.
    await expect(keyField).toBeHidden();
    expect((await catalog.listCommonVariables())[key]).toBe("first line\nsecond line");

    await page.getByRole("textbox", { name: "Search variables..." }).fill(key);
    const row = table.rows.filter({ has: page.getByText(key, { exact: true }) });
    // Selection, key, value: a value of more than one line is drawn as its first line.
    await expect(row.getByRole("cell").nth(2)).toHaveText("first line...");
  } finally {
    await catalog.deleteCommonVariables([key]).catch(leftBehind(`common variable ${key}`));
  }
});

test("a secured variable added through the UI leaves its value nowhere on the page", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  await ensureSecretFixture(catalog);
  const key = tokenized(run, "ui-secured");
  const value = `secret-${callToken()}`;

  await page.goto("/admintools/variables/secured");
  await page.getByRole("textbox", { name: "Search secrets..." }).fill(SECRET_FIXTURE_NAME);
  const secretRow = rowOf(page, SECRET_FIXTURE_NAME);
  await secretRow.getByTestId("secured-variables-add").click();
  const keyField = page.getByPlaceholder("Key");
  await expect(keyField).toBeFocused();
  try {
    await keyField.fill(key);
    await page.getByPlaceholder("Value").fill(value);
    await page.getByPlaceholder("Value").press("Enter");

    const row = rowOf(page, key);
    await expect(row.getByRole("cell").nth(2)).toHaveText("*****");
    expect(await catalog.securedVariablesInSecret(SECRET_FIXTURE_NAME)).toContain(key);
    // The typed value is kept only by the editor row, which closes once the variable is stored.
    await expect(page.getByPlaceholder("Value")).toHaveCount(0);
    await expect(page.getByText(value)).toHaveCount(0);
    const fields = await page.locator("input, textarea").evaluateAll((elements) => elements.map((each) => (each as HTMLInputElement).value));
    expect(fields).not.toContain(value);
  } finally {
    await catalog.deleteSecuredVariablesFromSecret(SECRET_FIXTURE_NAME, [key]).catch(leftBehind(`secured variable ${key}`));
  }
});

test("editing a secured variable starts from an empty value, not from the mask", { tag: ["@ui", "@tier2"] }, async ({ page, catalog, run }) => {
  await ensureSecretFixture(catalog);
  const key = tokenized(run, "ui-secured-edit");
  await catalog.addSecuredVariables(SECRET_FIXTURE_NAME, { [key]: "stored" });
  try {
    await page.goto("/admintools/variables/secured");
    await page.getByRole("textbox", { name: "Search secrets..." }).fill(SECRET_FIXTURE_NAME);
    const secretRow = rowOf(page, SECRET_FIXTURE_NAME);
    await secretRow.getByRole("button", { name: "Expand row" }).click();
    const row = rowOf(page, key);
    await row.getByText("*****", { exact: true }).click();
    const editor = row.getByRole("textbox");
    await expect(editor).toBeVisible();
    await expect(editor).toHaveValue("");
  } finally {
    await catalog.deleteSecuredVariablesFromSecret(SECRET_FIXTURE_NAME, [key]).catch(leftBehind(`secured variable ${key}`));
  }
});

test("a live exchange is killed only after its termination is confirmed", { tag: ["@ui", "@tier1"] }, async ({ page, request, env, catalog }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), "long-running");
  const { answer } = hold(request, env, chain.contextPath, HOLD_MS);
  try {
    const listed = await untilListed(catalog, chain.id);
    const kills: string[] = [];
    page.on("request", (sent) => {
      if (sent.method() === "DELETE" && sent.url().includes("/live-exchanges/")) kills.push(sent.url());
    });

    await page.goto("/admintools/exchanges");
    const row = new TableView(page).rows.filter({ has: page.getByText(listed.sessionId, { exact: true }) });
    const dialog = page.getByRole("dialog").filter({ hasText: "Terminate Exchange" });
    // The actions column shows its buttons only while the row is hovered.
    const terminate = async () => {
      await row.hover();
      await row.getByTestId("live-exchanges-terminate").click();
    };

    await terminate();
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();
    // Cancelling sends nothing, so the exchange is still running.
    expect(kills).toEqual([]);
    await expect(row).toBeVisible();

    await terminate();
    await dialog.getByRole("button", { name: "OK" }).click();
    // The row leaves once the kill is accepted, with no refresh.
    await expect(row).toHaveCount(0);
    expect(kills).toHaveLength(1);
    // `QIP-0114` is the engine's code for a session ended by hand.
    const response = await answer;
    expect(response.status()).toBe(500);
    expect(((await response.json()) as { code: string }).code).toBe("QIP-0114");
  } finally {
    await answer.catch(() => undefined);
  }
});
