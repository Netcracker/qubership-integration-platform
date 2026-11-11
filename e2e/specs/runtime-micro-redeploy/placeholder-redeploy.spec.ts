/**
 * The micro case of the second contract in `specs/runtime/placeholder.spec.ts`: a variable changed
 * under a live chain is served only after a redeploy.
 *
 * The micro engine substitutes placeholders when the domain loads its routes, and a redeploy rolls
 * the domain's pod, so the case runs in a domain of its own rather than in the shared `seed-micro`
 * one. The witness chain reads the variable per exchange, so it shows when the engine holds the new
 * value. The variable is not the classic case's `placeholder-live`, since both cases run at once.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { release, type Built } from "../../support/cleanup.js";
import { DEPLOY_TIMEOUT, MICRO_READY_TIMEOUT, SEED_LOGGING, type DeployedChain } from "../../support/corpus.js";
import {
  createScriptChain,
  deleteDomain,
  deployToDomain,
  MARKER_HEADER,
  tokenedDomain,
  tokenizedChain,
  type DeployableChain,
  type TokenedDomain,
} from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { tokenized } from "../../support/run.js";
import { callChain } from "../../support/sessions.js";
import type { Env } from "../../env/index.js";

/** The domain create and the route poll, plus building the chains. */
const SETUP_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

/** The engine's variable watch, then a redeploy that rolls the pod. */
const CASE_TIMEOUT = DEPLOY_TIMEOUT + MICRO_READY_TIMEOUT + 60_000;

const built: Built = { chains: [], services: [] };
let domain: TokenedDomain | undefined;
let live: DeployableChain;
let witness: DeployedChain;

/** The status and the marker header the chain answers with, as `<status> <marker>`. */
async function answerOf(request: APIRequestContext, env: Env, chain: DeployableChain): Promise<string> {
  const { response } = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
  return `${response.status()} ${response.headers()[MARKER_HEADER]}`;
}

test.describe("placeholder redeploy on the micro engine", { tag: ["@engine", "@catalog", "@slow"] }, () => {
  test.beforeAll(async ({ catalog, folder, run, microDomainState }) => {
    test.setTimeout(SETUP_TIMEOUT);
    const variable = tokenized(run, "placeholder-redeploy");
    await catalog.addCommonVariables({ [variable]: "before" });
    built.services.push({ name: `the common variable ${variable}`, remove: () => catalog.deleteCommonVariables([variable]) });
    live = await tokenizedChain(catalog, run, {
      prefix: "placeholder-redeploy",
      what: "live",
      parentId: folder.id,
      marker: `#{${variable}}`,
    });
    built.chains.push(live);
    noteChain(live);
    witness = await createScriptChain(
      catalog,
      run,
      {
        what: "placeholder-redeploy-witness",
        parentId: folder.id,
        script: `exchange.getMessage().setBody(exchange.getProperty('variables').get('${variable}'))`,
        scriptName: "Read Variable",
        logging: SEED_LOGGING,
      },
      built.chains,
    );
    domain = tokenedDomain(run, "placeholder-redeploy", microDomainState);
    await deployToDomain(catalog, domain, [live, witness]);
  });

  test.afterAll(async ({ catalog }) => {
    if (domain) await deleteDomain(catalog, domain);
    await release(catalog, built, false);
  });

  test("a changed variable is served only after a redeploy", async ({ request, catalog, run }) => {
    test.setTimeout(CASE_TIMEOUT);
    const target = domain as TokenedDomain;
    expect(await answerOf(request, target.env, live)).toBe("200 before");

    await catalog.updateCommonVariable(tokenized(run, "placeholder-redeploy"), "after");
    await expect
      .poll(async () => (await callChain(request, target.env.chainUrl(witness.contextPath), { data: {} })).response.text(), {
        timeout: DEPLOY_TIMEOUT,
        message: "the micro engine never received the updated variable",
      })
      .toBe("after");
    // So the old answer here is the contract, not a slow watch.
    expect(await answerOf(request, target.env, live), "the live chain picked up the variable without a redeploy").toBe("200 before");

    // The routes answer on the old pod at first, and the domain answers 503 while the pod rolls, so
    // the poll reads the status beside the marker.
    await deployToDomain(catalog, target, [live, witness]);
    await expect
      .poll(async () => await answerOf(request, target.env, live), {
        timeout: MICRO_READY_TIMEOUT,
        message: `the redeployed chain never served the updated variable on micro domain ${target.domain}`,
      })
      .toBe("200 after");
  });
});
