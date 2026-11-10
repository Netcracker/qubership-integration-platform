/**
 * Service discovery: the catalog reads the Services of its own namespace and turns each one that
 * serves an OpenAPI document into a catalog service.
 *
 * A discovered service is named after its Kubernetes Service and carries no run token, so the sweep
 * cannot find it; this case deletes what it discovered in its `finally`. It also deletes what an
 * earlier run discovered before it starts, because discovery skips a Service that already has a
 * catalog service. The catalog leaves out every Service whose name ends in `-v<n>`
 * (`KubeOperator.REGEX_FOR_SEARCH_BLUEGREEN_SERVICE_NAME`), so the chart's `cip-<role>-v1` Services
 * are never discovered; the `qip-runtime-catalog` alias the e2e values add always is.
 *
 * Until #1139 the catalog reported progress 100 before it stored the result, and a read in between
 * answered an empty body.
 *
 * Cold OpenAPI generation and import took over 60 s on CI while other Kubernetes cases ran.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog } from "../../support/catalog.js";
import { serviceNames } from "../../support/kube.js";

const DISCOVERY_TIMEOUT = 120_000;
const CLEANUP_TIMEOUT = 30_000;

/** The Service the e2e values add under the catalog's Compose name. */
const CATALOG_ALIAS = "qip-runtime-catalog";

async function deleteDiscovered(catalog: Catalog): Promise<string[]> {
  try {
    // Discovery can still be importing a specification after the test's poll times out.
    await expect.poll(() => catalog.discoveryProgress(), { timeout: CLEANUP_TIMEOUT }).toBe("100");
  } catch (cause) {
    return [`discovery did not complete; services were retained: ${String(cause)}`];
  }

  const failures: string[] = [];
  for (const each of await catalog.discoveredServices()) {
    await catalog.deleteSystem(each.id).catch((cause: unknown) => failures.push(`${each.id}: ${String(cause)}`));
  }
  return failures;
}

test.describe("service discovery", { tag: ["@catalog", "@infra"] }, () => {
  test("creates a catalog service per Service with a specification", async ({ catalog }) => {
    test.setTimeout(180_000);
    expect(await deleteDiscovered(catalog), "services an earlier run discovered").toEqual([]);

    let bodyFailed = true;
    try {
      await catalog.runDiscovery();
      // The progress only says when to look; what the run did is read off the services it created.
      await expect.poll(() => catalog.discoveryProgress(), { timeout: DISCOVERY_TIMEOUT }).toBe("100");

      const discovered = await catalog.discoveredServices();
      expect(discovered.map((each) => each.id)).toContain(CATALOG_ALIAS);

      const services = await serviceNames();
      for (const each of discovered) {
        expect(each.id, "a discovered service is named after its Service").toBe(each.internalServiceName);
        expect(services).toContain(each.internalServiceName);
        expect(each.internalServiceName).not.toMatch(/-v\d+$/);
        expect(each.serviceGroups.map((group) => Boolean(group.specificationId)), each.id).toContain(true);
      }

      const result = await catalog.discoveryResult();
      expect(result, "the discovery result once the progress reads 100").toBeDefined();
      expect(result.errorMessages).toEqual([]);
      expect([...result.discoveredSystemIds].sort()).toEqual(discovered.map((each) => each.id).sort());
      bodyFailed = false;
    } finally {
      const failures = await deleteDiscovered(catalog);
      if (failures.length > 0) {
        const report = `discovered services were not deleted: ${failures.join("; ")}`;
        if (bodyFailed) console.error(`[cleanup] ${report}`);
        else throw new Error(report);
      }
    }
  });
});
