/**
 * A chain that fails to load takes down the whole micro domain it is deployed to, healthy chains
 * included, and the catalog reports the domain's engine pod as not ready.
 *
 * The broken chain names a common variable that does not exist. The micro engine substitutes
 * variables while it loads the routes (`VariablesInjectorPreprocessor`), the unresolved one throws
 * `DeploymentRetriableException`, Quarkus fails to start, and the container exits with code 1 and
 * crash-loops. The classic engine parks the same chain at `PROCESSING` and keeps serving the rest
 * (`specs/runtime/placeholder.spec.ts`).
 *
 * Other ways a chain can be wrong do not take a micro domain down, measured on DD k8s: a script that
 * does not parse, and a broker trigger whose topic, queue, or exchange is missing, each report
 * `DEPLOYED` while the pod keeps running.
 *
 * The domain is deleted as soon as the catalog reports it, because each crash-loop restart is
 * another JVM start.
 */
import { test, expect } from "../../support/fixtures.js";
import { withBuilt } from "../../support/cleanup.js";
import { MICRO_READY_TIMEOUT } from "../../support/corpus.js";
import { deleteDomain, dispatchToDomain, tokenedDomain, tokenizedChain } from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { logWindowStart } from "../../support/logs.js";
import { tokenized } from "../../support/run.js";

/** The first crash took 25 to 30 s from the deploy; the rest is building the chains and the delete. */
const CASE_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

test.describe("micro domain with a broken chain", { tag: ["@engine", "@catalog", "@slow"] }, () => {
  test("goes down and the catalog reports its engine not ready", async ({ request, catalog, folder, run }) => {
    test.setTimeout(CASE_TIMEOUT);
    const variable = tokenized(run, "broken-chain-missing");

    await withBuilt(catalog, async (built) => {
      const healthy = await tokenizedChain(catalog, run, { prefix: "broken-chain", what: "healthy", parentId: folder.id });
      const broken = await tokenizedChain(catalog, run, {
        prefix: "broken-chain",
        what: "missing-variable",
        parentId: folder.id,
        marker: `#{${variable}}`,
      });
      for (const chain of [healthy, broken]) {
        built.chains.push(chain);
        noteChain(chain);
      }

      const target = tokenedDomain(run, "broken-chain");
      const since = logWindowStart();
      try {
        await dispatchToDomain(catalog, target, [healthy, broken]);

        // A starting pod is PENDING; a RUNNING pod whose container is not ready has exited, since
        // micro pods declare no readiness probe.
        await expect
          .poll(
            async () =>
              (await catalog.domainEngines(target.domain)).map((engine) => `${engine.runningStatus} ready=${engine.ready}`),
            { timeout: MICRO_READY_TIMEOUT, message: `the catalog never reported micro domain ${target.domain} down` },
          )
          .toEqual(["RUNNING ready=false"]);

        await expect
          .poll(async () => await target.env.engineLogs(since).catch((cause: unknown) => String(cause)), {
            timeout: MICRO_READY_TIMEOUT,
            message: "the micro engine did not log the unresolved variable as the reason it stopped",
          })
          .toContain(`Couldn't resolve variables. #{${variable}} variable doesn't exist`);

        // The healthy chain went down with the domain: no engine reported either chain deployed,
        // and its route answers from the proxy, not from a live route (405 to a GET).
        expect(await catalog.runtimeDeploymentsOf(healthy.id), "the healthy chain was reported deployed").toEqual([]);
        expect(await catalog.runtimeDeploymentsOf(broken.id), "the broken chain was reported deployed").toEqual([]);
        const status = (await request.get(target.env.chainUrl(healthy.contextPath), { failOnStatusCode: false })).status();
        expect([502, 503, 504], `the healthy chain answered ${status}`).toContain(status);
      } finally {
        await deleteDomain(catalog, target);
      }
    });
  });
});
