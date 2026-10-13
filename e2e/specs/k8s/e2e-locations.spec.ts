/**
 * The proxy locations only the e2e values render: `/e2e/svc/<service>/` and `/e2e/gateway/`.
 *
 * The micro seed polls a chain through `/e2e/svc/` and counts a 200 as serving. The server block
 * declares `error_page 502 503 504 @error`, whose location answers `200 ""`, so a location that
 * inherited it would report a Service that does not exist as serving, and a chain that answers 503
 * as a chain that answers 200. Each e2e location declares `error_page` of its own for that reason,
 * and this spec holds it to that.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed, waitForRoutes } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { CLASSIC_ENGINE_SERVICE, proxiedChainUrl } from "../../support/kube.js";
import { leftBehind } from "../../support/teardown.js";

/** A DNS label no Service in the namespace carries. */
const ABSENT_SERVICE = "qip-e2e-no-such-service";

test("a request through /e2e/svc/ to a Service that does not exist answers 502, not 200", { tag: ["@infra", "@tier1"] }, async ({ request }) => {
  const response = await request.get(proxiedChainUrl(ABSENT_SERVICE, "e2e-locations.txt?probe=1"), {
    failOnStatusCode: false,
  });
  // The inherited error page would answer 200 with no body, and the SPA fallback 200 with the page.
  expect(response.status()).toBe(502);
});

test("a chain that answers 503 reaches the caller through /e2e/svc/ as 503", { tag: ["@infra", "@engine", "@tier2"] }, async ({ catalog, env, folder, run, request }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await tokenizedChain(catalog, run, { prefix: "locations", what: "unavailable", parentId: folder.id });
  // Camel's HTTP binding answers with the status this header holds.
  await catalog.patchElementProperties(chain.id, chain.headerElementId, {
    headerModificationToAdd: { CamelHttpResponseCode: "503" },
  });
  const snapshot = await catalog.createSnapshot(chain.id);

  try {
    await catalog.deploy(chain.id, snapshot.id);
    await waitForDeployed(catalog, [chain]);
    await waitForRoutes(env, [chain]);

    const direct = await request.post(env.chainUrl(chain.contextPath), { data: { locations: run } });
    expect(direct.status(), "the chain itself answers 503").toBe(503);

    const proxied = await request.post(proxiedChainUrl(CLASSIC_ENGINE_SERVICE, chain.contextPath), {
      data: { locations: run },
    });
    expect(proxied.status()).toBe(503);
  } finally {
    await catalog.undeployAll(chain.id).catch(leftBehind(chain.name, "undeployed"));
  }
});
