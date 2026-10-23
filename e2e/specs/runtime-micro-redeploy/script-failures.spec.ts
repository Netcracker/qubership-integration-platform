/**
 * The micro case of the first contract in `specs/runtime/script-failures.spec.ts`: a script that does
 * not parse fails the deployment, and the chain does not serve.
 *
 * The chain is built here rather than imported from the spec-owned fixture, whose fixed id the
 * `runtime` project imports and deletes at the same time.
 */
import { test, expect } from "../../support/fixtures.js";
import { release, type Built } from "../../support/cleanup.js";
import { MICRO_READY_TIMEOUT, SEED_LOGGING, type SeedChain } from "../../support/corpus.js";
import { createScriptChain, deleteDomain, dispatchToDomain, tokenedDomain, type TokenedDomain } from "../../support/deployable.js";
import { deploymentRows, routeStatus } from "../../support/spec-owned.js";

/** The message both engines raise for a script that does not compile. */
const COMPILE_FAILURE = "Failed to compile groovy script.";

/** The domain's first JVM start and the deployment status after it, plus building the chain. */
const CASE_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

const built: Built = { chains: [], services: [] };
let domain: TokenedDomain | undefined;
let chain: SeedChain;

test.describe("script failures on the micro engine", { tag: ["@engine", "@catalog", "@slow"] }, () => {
  test.beforeAll(async ({ catalog, folder, run, microDomainState }) => {
    chain = await createScriptChain(
      catalog,
      run,
      { what: "script-failures-syntax", parentId: folder.id, script: "def broken = (", scriptName: "Unbalanced Script", logging: SEED_LOGGING },
      built.chains,
    );
    domain = tokenedDomain(run, "script-failures", microDomainState);
    // No route wait: a chain that fails its deployment never serves.
    await dispatchToDomain(catalog, domain, [chain]);
  });

  test.afterAll(async ({ catalog }) => {
    if (domain) await deleteDomain(catalog, domain);
    await release(catalog, built, false);
  });

  test("a script that does not compile fails the deployment", async ({ catalog }) => {
    test.setTimeout(CASE_TIMEOUT);
    const target = domain as TokenedDomain;
    // Empty until the domain's pod reports, and PROCESSING while a classic engine retries.
    let rows: string[] = [];
    await expect
      .poll(async () => (rows = await deploymentRows(catalog, chain.id)).length > 0 && !rows.some((row) => row.startsWith("PROCESSING")), {
        timeout: MICRO_READY_TIMEOUT,
        message: `micro domain ${target.domain} never reported the deployment of ${chain.name}`,
      })
      .toBe(true);

    // The micro engine compiled the scripts in a Camel event notifier, which ignored the failure, and
    // reported DEPLOYED until #1134.
    expect(rows, `the deployment of ${chain.name} settled at ${rows.join("; ")}`).toEqual([`FAILED: ${COMPILE_FAILURE}`]);
    // The domain reports the failure while its pod starts, and the proxy answers 503 until the pod is ready.
    await expect
      .poll(() => routeStatus(target.env, chain), { timeout: MICRO_READY_TIMEOUT, message: "a chain that failed to compile is serving" })
      .toBe(404);
  });
});
