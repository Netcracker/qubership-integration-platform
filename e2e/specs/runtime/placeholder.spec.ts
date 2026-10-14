/**
 * A `#{variable}` placeholder in an element property, asserted once for the whole platform.
 *
 * Every value union in the element schemas (a literal or a placeholder) is resolved by one
 * mechanism, so one chain proves it and a case per element would prove the same fact again. The
 * mechanism is the engine's, and it runs at **deploy** time: `IntegrationRuntimeService` passes the
 * route XML through `VariablesService.injectVariables` before it builds the route, and the snapshot
 * still carries the literal `#{…}`. Three contracts follow, one case each:
 *
 * - the seeded chain answers with the value the seed wrote into the variable;
 * - a variable changed under a live chain is **not** served until the chain is redeployed, even
 *   once the engine holds the new value;
 * - a variable missing at deploy parks the deployment in `PROCESSING` with "Couldn't resolve
 *   variables", not `FAILED`, and the engine's retry brings it up once the variable exists.
 *
 * The last two build their own chains in the worker folder. The seed refuses any chain that does
 * not go live, and changing the seeded variable would race the first case in a parallel project.
 *
 * Common variables only. Secured variables never reach the engine on Compose: its
 * `pollSecuredVariables()` reads them through `KubeOperator`, which returns nothing in dev mode,
 * while the catalog keeps them in `LocalDevKubeSecretOperator`. See `registry/elements.ts`.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  DEPLOY_TIMEOUT,
  ENGINE_CASE_TIMEOUT,
  PLACEHOLDER_FIXTURE,
  SEED_LOGGING,
  placeholderValue,
  placeholderVariable,
  readCorpusState,
  RETRY_DELAY,
  seedChain,
  waitForDeployed,
  waitForRoutes,
} from "../../support/corpus.js";
import { MARKER_HEADER, createScriptChain, tokenizedChain, type DeployableChain } from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { tokenized } from "../../support/run.js";
import { callChain, element, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { withBuilt, type Built, type BuiltService } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

/** A chain whose marker header is the placeholder, built in the worker folder and not deployed. */
async function placeholderChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
  variable: string,
  built: Built,
): Promise<DeployableChain> {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "placeholder",
    what,
    parentId: folderId,
    marker: `#{${variable}}`,
  });
  built.chains.push(chain);
  noteChain(chain);
  return chain;
}

/** A common variable the case added, deleted with what it built. */
function commonVariable(catalog: Catalog, name: string): BuiltService {
  return { name: `the common variable ${name}`, remove: () => catalog.deleteCommonVariables([name]) };
}

async function deploy(catalog: Catalog, chain: { id: string }): Promise<void> {
  const snapshot = await catalog.createSnapshot(chain.id);
  await catalog.deploy(chain.id, snapshot.id);
}

async function markerOf(request: APIRequestContext, env: Env, chain: DeployableChain): Promise<string | undefined> {
  const { response } = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
  expect(response.status(), `${chain.name} answered ${response.status()}`).toBe(200);
  return response.headers()[MARKER_HEADER];
}

test("a placeholder in an element property resolves to the variable's value at deploy", { tag: ["@engine", "@catalog", "@tier1"] }, async ({ request, env, sessions }) => {
  const corpus = readCorpusState();
  const chain = seedChain(corpus, PLACEHOLDER_FIXTURE);
  const value = placeholderValue(corpus.run);

  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "placeholder" } });
  expect(call.response.status()).toBe(200);
  expect(call.response.headers()["e2e-placeholder"], `#{${placeholderVariable(corpus.run)}} did not resolve`).toBe(value);

  // The trace is the element's own record of the header it set, so the value reached the element
  // rather than being added somewhere on the way out.
  const session = await sessions.byExternalId(call.token, { elements: 3 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Resolve Placeholder"]);
  expect(element(session, "Resolve Placeholder")?.headersAfter?.["e2e-placeholder"]).toBe(value);
});

test("a variable changed under a live chain is served only after a redeploy", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);

  const variable = tokenized(run, "placeholder-live");
  await withBuilt(catalog, async (built) => {
    await catalog.addCommonVariables({ [variable]: "before" });
    built.services.push(commonVariable(catalog, variable));
    const live = await placeholderChain(catalog, run, folder.id, "live", variable, built);
    // The witness reads the variable per exchange, out of the `variables` property the engine
    // fills on every exchange, so it sees an update as soon as the engine's Consul watch has.
    const witness = await createScriptChain(
      catalog,
      run,
      {
        what: "placeholder-witness",
        parentId: folder.id,
        script: `exchange.getMessage().setBody(exchange.getProperty('variables').get('${variable}'))`,
        scriptName: "Read Variable",
        logging: SEED_LOGGING,
      },
      built.chains,
    );
    for (const chain of [live, witness]) await deploy(catalog, chain);
    await waitForDeployed(catalog, [live, witness]);
    await waitForRoutes(env, [live, witness]);
    expect(await markerOf(request, env, live)).toBe("before");

    await catalog.updateCommonVariable(variable, "after");

    // Polled, not read once: the engine's variable watch and its deployment watch are separate
    // blocking queries, and a first attempt that deployed a second placeholder chain right after
    // the update resolved it to the old value.
    await expect
      .poll(async () => (await callChain(request, env.chainUrl(witness.contextPath), { data: {} })).response.text(), {
        timeout: DEPLOY_TIMEOUT,
        message: "the engine never received the updated variable",
      })
      .toBe("after");
    // So the old answer here is the contract, not a slow watch.
    expect(await markerOf(request, env, live), "the live chain picked up the variable without a redeploy").toBe("before");

    await deploy(catalog, live);
    await expect
      .poll(async () => await markerOf(request, env, live), {
        timeout: DEPLOY_TIMEOUT,
        message: "the redeployed chain never served the updated variable",
      })
      .toBe("after");
  });
});

test("a variable missing at deploy parks the chain until the variable exists", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);

  const variable = tokenized(run, "placeholder-missing");
  await withBuilt(catalog, async (built) => {
    const parked = await placeholderChain(catalog, run, folder.id, "missing", variable, built);
    await deploy(catalog, parked);

    await expect
      .poll(
        async () =>
          (await catalog.runtimeDeploymentsOf(parked.id)).map((row) => `${row.status}: ${row.errorMessage ?? ""}`),
        { timeout: DEPLOY_TIMEOUT, message: "the engine never reported the unresolved variable" },
      )
      .toEqual([`PROCESSING: Couldn't resolve variables. #{${variable}} variable doesn't exist`]);
    // No route: 405 would mean the chain went live with the literal placeholder in it.
    const status = await fetch(env.chainUrl(parked.contextPath)).then((response) => response.status);
    expect(status, "a chain with an unresolved variable is serving").toBe(404);

    await catalog.addCommonVariables({ [variable]: "late" });
    built.services.push(commonVariable(catalog, variable));
    // The parked deployment is retried on the engine's own schedule rather than on the variable
    // update, so the wait is one retry period on top of a deploy.
    const budget = RETRY_DELAY + DEPLOY_TIMEOUT;
    await expect
      .poll(async () => (await catalog.runtimeDeploymentsOf(parked.id)).map((row) => row.status), {
        timeout: budget,
        message: "the parked deployment was not retried once its variable existed",
      })
      .toEqual(["DEPLOYED"]);
    await waitForRoutes(env, [parked]);
  });
});
