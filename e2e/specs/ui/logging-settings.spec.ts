/**
 * The chain's Logging tab: overriding the logging properties and dropping the override again.
 *
 * What the API cannot see: that the form posts the values its selects show, that unchecking
 * "Override default properties" sends the delete rather than a write of the defaults, and that the
 * source tag and the disabled selects follow the change with no reload.
 *
 * The catalog answers `GET .../properties/logging` from a cache it refills from Consul every second,
 * so every read of `custom` here polls.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import type { Catalog, LoggingProperties } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

/** The custom layer once the cache shows it, or `undefined` once it shows none. */
async function settledCustom(catalog: Catalog, chainId: string, present: boolean): Promise<LoggingProperties | undefined> {
  let custom: LoggingProperties | undefined;
  await expect
    .poll(async () => (custom = (await catalog.getLoggingProperties(chainId)).custom) !== undefined, {
      message: `waiting for the custom logging layer of ${chainId} to be ${present ? "written" : "gone"}`,
    })
    .toBe(present);
  return custom;
}

/** `logPayload` is a set on the server, so its order changes between catalog boots. */
function sorted(properties: LoggingProperties | undefined): LoggingProperties | undefined {
  return properties && { ...properties, logPayload: [...properties.logPayload].sort() };
}

/** Picks `option` in the select labelled `label`; antd renders a visible option by its title. */
async function choose(tabs: ChainTabsPage, label: string, option: string): Promise<void> {
  await tabs.field("combobox", label).click();
  await tabs.page.getByTitle(option, { exact: true }).click();
}

/** Clicks Apply and returns once the page has the answer, so the next change starts from the saved form. */
async function apply(tabs: ChainTabsPage, method: "POST" | "DELETE"): Promise<void> {
  const answered = tabs.page.waitForResponse(
    (response) => response.request().method() === method && response.url().endsWith("/properties/logging"),
  );
  await tabs.apply.click();
  expect((await answered).ok()).toBe(true);
}

test("a logging override is saved from the form and dropped again by unchecking it", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-logging"), folder.id);
  const tabs = new ChainTabsPage(page);
  const override = page.getByRole("checkbox", { name: "Override default properties" });
  const sessionLevel = tabs.field("combobox", "Session level");

  try {
    const initial = await catalog.getLoggingProperties(chain.id);
    expect(initial.custom, "a new chain has no custom logging layer").toBeUndefined();
    const defaultSource = initial.consulDefault ? "Default (Consul)" : "Default (Fallback)";
    const defaults = initial.consulDefault ?? initial.fallbackDefault;

    await tabs.goto(chain.id, "logging-settings");
    await expect(page.getByText(defaultSource, { exact: true })).toBeVisible();
    await expect(override).not.toBeChecked();
    await expect(sessionLevel).toBeDisabled();

    await override.check();
    await expect(sessionLevel).toBeEnabled();
    await choose(tabs, "Session level", "Debug");
    await choose(tabs, "Log level", "Warning");
    await apply(tabs, "POST");

    // The fields the case did not touch keep the defaults the form was filled with.
    expect(sorted(await settledCustom(catalog, chain.id, true))).toEqual(
      sorted({ ...defaults, sessionsLoggingLevel: "DEBUG", logLoggingLevel: "WARN" }),
    );
    await expect(page.getByText("Custom", { exact: true })).toBeVisible();

    await override.uncheck();
    await expect(sessionLevel).toBeDisabled();
    await apply(tabs, "DELETE");

    expect(await settledCustom(catalog, chain.id, false)).toBeUndefined();
    await expect(page.getByText(defaultSource, { exact: true })).toBeVisible();
    await expect(page.getByText("Custom", { exact: true })).toBeHidden();
  } finally {
    // The override lives in Consul, which the folder cascade does not reach.
    await catalog.deleteLoggingProperties(chain.id).catch(leftBehind(`the logging properties of ${chain.name}`));
  }
});
