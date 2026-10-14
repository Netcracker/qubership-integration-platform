/**
 * The micro case of the first contract in `specs/runtime/script-failures.spec.ts`: a script that does
 * not parse fails the deployment, and the chain does not serve.
 *
 * The micro engine deploys the chain anyway (`SCRIPT_COMPILE_IGNORED`), so the case asserts the
 * classic contract and pins the micro reading. The chain is built here rather than imported from the
 * spec-owned fixture, whose fixed id the `runtime` project imports and deletes at the same time.
 */
import { test, expect } from "../../support/fixtures.js";
import { release, type Built } from "../../support/cleanup.js";
import { MICRO_READY_TIMEOUT, SEED_LOGGING, type SeedChain } from "../../support/corpus.js";
import { createScriptChain, deleteDomain, dispatchToDomain, tokenedDomain, type TokenedDomain } from "../../support/deployable.js";
import { strikesAsKnown } from "../../support/known-defect.js";
import { logWindowStart, withEngineLines } from "../../support/logs.js";
import { deploymentRows, routeStatus } from "../../support/spec-owned.js";
import { COMPILE_FAILURE, ignoredCompileFailure, SCRIPT_COMPILE_IGNORED } from "./micro-defects.js";

/** The domain's first JVM start and the deployment status after it, plus building the chain. */
const CASE_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

const built: Built = { chains: [], services: [] };
let domain: TokenedDomain | undefined;
let chain: SeedChain;
let since: string;

test.beforeAll(async ({ catalog, folder, run, microDomainState }) => {
  chain = await createScriptChain(
    catalog,
    run,
    { what: "script-failures-syntax", parentId: folder.id, script: "def broken = (", scriptName: "Unbalanced Script", logging: SEED_LOGGING },
    built.chains,
  );
  domain = tokenedDomain(run, "script-failures", microDomainState);
  since = logWindowStart();
  // No route wait: a chain that fails its deployment never serves.
  await dispatchToDomain(catalog, domain, [chain]);
});

test.afterAll(async ({ catalog }) => {
  if (domain) await deleteDomain(catalog, domain);
  await release(catalog, built, false);
});

test("a script that does not compile fails the deployment on a micro domain", { tag: ["@engine", "@catalog", "@tier2"] }, async ({ catalog }) => {
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

  await strikesAsKnown(SCRIPT_COMPILE_IGNORED, () =>
    withEngineLines(target.env, since, "ignoring the compile failure", ignoredCompileFailure, async () =>
      expect(rows, `the deployment of ${chain.name} settled at ${rows.join("; ")}`).toEqual([`FAILED: ${COMPILE_FAILURE}`]),
    ),
  );
  expect(await routeStatus(target.env, chain), "a chain that failed to compile is serving").toBe(404);
});
