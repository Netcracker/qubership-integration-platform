/**
 * The live half of the API inventory guard: what the services serve right now against the cached
 * list the registry is checked into.
 *
 * The schema-project gap spec proves the registry tracks the cache, which is the direction that was
 * already safe — one person writes both. This spec is the other direction: a controller added to
 * the catalog, or an endpoint quietly dropped, fails here, and the fix is
 * `npm run refresh-operations` followed by the registry rows the new operations need.
 *
 * It addresses the services by port rather than through nginx on purpose. `/v3/api-docs` reaches
 * the catalog through the proxy on one prefix shape only, and the engine and sessions-management
 * documents are not proxied at all.
 *
 * Four services and two document shapes: the testing service serves Swagger 2.0 at
 * `/api/v1/swagger/doc.json`, and `operationServices()` carries the path, so a case here reads
 * whatever a service publishes rather than assuming `/v3/api-docs`.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  diffOperations,
  loadCachedOperations,
  operationServices,
  operationsFromOpenApi,
} from "../../registry/operations.js";
import { OPERATION_SERVICE_TAG } from "./constants.js";

const cache = loadCachedOperations();

for (const service of operationServices()) {
  test(`${service.service} serves exactly the operations the cache records`, { tag: [OPERATION_SERVICE_TAG[service.service], "@tier1"] }, async ({ request }) => {
    const response = await request.get(`${service.baseUrl}${service.docPath}`);
    expect(
      response.status(),
      `${service.baseUrl}${service.docPath} — is the stack up?`,
    ).toBe(200);

    const live = operationsFromOpenApi(await response.json());
    expect(live.length, "the document served no operations at all").toBeGreaterThan(0);

    const diff = diffOperations(cache.services[service.service] ?? [], live);
    expect(
      diff,
      `${service.service} drifted from registry/operations.cache.json — run npm run refresh-operations, then add or remove the registry rows`,
    ).toEqual({ missing: [], unexpected: [] });
  });
}
