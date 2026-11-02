/**
 * The Domains page under Admin Tools: a domain, its engine pods, and the deployments each pod runs.
 *
 * What the API cannot see: that an expanded engine row refetches its deployments when a deployment
 * event on `GET /v1/catalog/events` names its host and domain, so a chain shows up after its deploy
 * and leaves after its undeploy with no reload. The chain deploys to the classic `default` domain,
 * never to a micro one, whose pod a deploy would roll.
 *
 * The engine rows come from `GET /v1/catalog/domains/{domain}/engines`, which lists pods only where
 * `Env.domainFacts().listsEnginePods` says so. The first case reads the engine level on both targets.
 * Compose's dev-mode domain source lists no pod, so no engine row exists to expand there, and the
 * second case is skipped on it with that reason.
 */
import type { Locator, Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import type { Catalog } from "../../support/catalog.js";
import { DEFAULT_DOMAIN } from "../../support/catalog-types.js";
import { DEPLOY_TIMEOUT } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { callToken } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

/** Expands the `default` domain and returns its engine row, once the page has the engine listing. */
async function expandDefaultDomain(page: Page, host: string): Promise<Locator> {
  const engines = page.waitForResponse((response) => response.url().endsWith(`/domains/${DEFAULT_DOMAIN}/engines`));
  await page.goto("/admintools/domains");
  await rowOf(page, DEFAULT_DOMAIN).getByRole("button", { name: "Expand row" }).click();
  expect((await engines).ok()).toBe(true);
  return rowOf(page, host);
}

function rowOf(page: Page, text: string): Locator {
  return page.getByRole("row").filter({ has: page.getByRole("cell", { name: text, exact: true }) });
}

async function defaultEngineHost(catalog: Catalog): Promise<string> {
  const [host] = (await catalog.engineHosts())[DEFAULT_DOMAIN] ?? [];
  expect(host, `no engine registered in the ${DEFAULT_DOMAIN} domain`).toBeDefined();
  return host;
}

test.describe("domains", () => {
  test("the default domain shows the engine pods its source lists", async ({ page, catalog, env }) => {
    const host = await defaultEngineHost(catalog);
    const engine = await expandDefaultDomain(page, host);
    // The expanded domain row holds the engine table, so it matches the engine row's filter too.
    await expect(engine.getByRole("cell", { name: host, exact: true })).toHaveCount(env.domainFacts().listsEnginePods ? 1 : 0);
  });

  test("an engine row shows a deployed chain and drops it in place", async ({ page, catalog, env, folder, run }) => {
    test.skip(!env.domainFacts().listsEnginePods, "the domain source lists no engine pod, so no engine row exists to expand");
    // A call token in the route keeps repeats of the case apart.
    const chain = await tokenizedChain(catalog, run, { prefix: "ui-domains", what: callToken("deploy"), parentId: folder.id });
    const snapshot = await catalog.createSnapshot(chain.id);
    const host = await defaultEngineHost(catalog);
    const listed = page.getByRole("link", { name: chain.name, exact: true });

    const engine = await expandDefaultDomain(page, host);
    const deployments = page.waitForResponse((response) => response.url().endsWith(`/engines/${host}/deployments`));
    await engine.getByRole("button", { name: "Expand row" }).click();
    expect((await deployments).ok()).toBe(true);
    await expect(listed).toHaveCount(0);

    try {
      await catalog.deploy(chain.id, snapshot.id);
      await expect(listed).toBeVisible({ timeout: DEPLOY_TIMEOUT });
      // The row's name is its cells; the rows of the levels above hold the cell too.
      await expect(page.getByRole("row", { name: `${chain.name} ${snapshot.name} Status: Deployed`, exact: true })).toBeVisible({ timeout: DEPLOY_TIMEOUT });
    } finally {
      await catalog.undeployAll(chain.id).catch(leftBehind(`chain ${chain.name}`, "undeployed"));
    }
    await expect(listed).toBeHidden({ timeout: DEPLOY_TIMEOUT });
  });
});
