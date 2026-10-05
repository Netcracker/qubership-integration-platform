/**
 * Service discovery: the catalog reads the Services of its own namespace and turns each one that
 * serves an OpenAPI document into a catalog service.
 *
 * A discovered service is named after its Kubernetes Service and carries no run token, so the sweep
 * cannot find it; this case deletes what it discovered in its `finally`. It also deletes what an
 * earlier run discovered before it starts, because discovery skips a Service that already has a
 * catalog service. The catalog leaves out every Service whose name ends in `-v<n>`
 * (`KubeOperator.REGEX_FOR_SEARCH_BLUEGREEN_SERVICE_NAME`), so the chart's `qip-<role>-v1` Services
 * are never discovered; the `qip-runtime-catalog` alias the e2e values add always is.
 *
 * Measured on Docker Desktop's Kubernetes: a run took 8 s and discovered four services.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog } from "../../support/catalog.js";
import { serviceNames } from "../../support/kube.js";

const DISCOVERY_TIMEOUT = 60_000;

/** The Service the e2e values add under the catalog's Compose name. */
const CATALOG_ALIAS = "qip-runtime-catalog";

async function deleteDiscovered(catalog: Catalog): Promise<string[]> {
  const failures: string[] = [];
  for (const each of await catalog.discoveredServices()) {
    await catalog.deleteSystem(each.id).catch((cause: unknown) => failures.push(`${each.id}: ${String(cause)}`));
  }
  return failures;
}

test("discovery creates a catalog service for each Service in the namespace that serves a specification", { tag: ["@catalog", "@infra", "@tier2"] }, async ({ catalog }) => {
  expect(await deleteDiscovered(catalog), "services an earlier run discovered").toEqual([]);

  let bodyFailed = true;
  try {
    await catalog.runDiscovery();
    // The progress only says when to look; what the run did is read off the services it created.
    await expect.poll(() => catalog.discoveryProgress(), { timeout: DISCOVERY_TIMEOUT }).toBe("100");

    const discovered = await catalog.discoveredServices();
    const result = await catalog.discoveryResult();
    expect(result.errorMessages).toEqual([]);
    expect([...result.discoveredSystemIds].sort()).toEqual(discovered.map((each) => each.id).sort());
    expect(discovered.map((each) => each.id)).toContain(CATALOG_ALIAS);

    const services = await serviceNames();
    for (const each of discovered) {
      expect(each.id, "a discovered service is named after its Service").toBe(each.internalServiceName);
      expect(services).toContain(each.internalServiceName);
      expect(each.internalServiceName).not.toMatch(/-v\d+$/);
      expect(each.serviceGroups.map((group) => Boolean(group.specificationId)), each.id).toContain(true);
    }
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
