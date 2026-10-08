/**
 * A chain with an external trigger, called the way production calls one: through the public
 * gateway.
 *
 * Every corpus chain has an internal trigger, which the gateway does not route. This case deploys
 * one chain with `externalRoute: true` to the classic engine, which writes a rule for it into the
 * HTTPRoute `qip-engine-v1-chain-public-routes`, and reads that rule off the cluster. The engine
 * keeps every external trigger of a domain in that one HTTPRoute, and the Gateway API caps it at 16
 * rules (#1001), so this case deploys one chain and undeploys it before it ends.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed } from "../../support/corpus.js";
import { MARKER_HEADER, tokenizedChain } from "../../support/deployable.js";
import {
  CLASSIC_ENGINE_SERVICE,
  CLASSIC_PUBLIC_ROUTES,
  GATEWAY_ROUTE_PREFIX,
  gatewayUrl,
  httpRoute,
} from "../../support/kube.js";
import { leftBehind } from "../../support/teardown.js";

/** How long Istio gets to program the gateway after the HTTPRoute changes. */
const GATEWAY_TIMEOUT = 60_000;

/** The rules of the public-routes HTTPRoute that match `path`, as `<type> <path> -> <backend>`. */
async function rulesFor(path: string): Promise<string[]> {
  const route = await httpRoute(CLASSIC_PUBLIC_ROUTES);
  return (route?.spec.rules ?? [])
    .filter((rule) => rule.matches.some((match) => match.path.value === path))
    .map((rule) => {
      const match = rule.matches[0].path;
      const backends = (rule.backendRefs ?? []).map((each) => `${each.name}:${each.port}`).join(",");
      return `${match.type} ${match.value} -> ${backends}`;
    });
}

test("a chain with an external trigger answers through the public gateway until it is undeployed", { tag: ["@engine", "@infra", "@tier2"] }, async ({ catalog, folder, run, request }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await tokenizedChain(catalog, run, {
    prefix: "gateway",
    what: "external",
    parentId: folder.id,
    externalRoute: true,
  });
  const path = `${GATEWAY_ROUTE_PREFIX}/${chain.contextPath}`;
  const snapshot = await catalog.createSnapshot(chain.id);

  try {
    const deployment = await catalog.deploy(chain.id, snapshot.id);
    await waitForDeployed(catalog, [chain]);

    expect(await rulesFor(path)).toEqual([`PathPrefix ${path} -> ${CLASSIC_ENGINE_SERVICE}:8080`]);
    const route = await httpRoute(CLASSIC_PUBLIC_ROUTES);
    expect(route?.spec.parentRefs.map((each) => each.name)).toEqual(["public-gateway"]);

    await expect
      .poll(async () => (await request.post(gatewayUrl(path), { data: { gateway: run } })).status(), {
        timeout: GATEWAY_TIMEOUT,
        message: `the public gateway never routed ${path} to the chain`,
      })
      .toBe(200);
    const answer = await request.post(gatewayUrl(path), { data: { gateway: run } });
    expect(answer.headers()[MARKER_HEADER]).toBe("external");

    await catalog.undeploy(chain.id, deployment.id);
    await expect.poll(() => rulesFor(path), { timeout: GATEWAY_TIMEOUT }).toEqual([]);
    await expect
      .poll(async () => (await request.post(gatewayUrl(path), { data: { gateway: run } })).status(), {
        timeout: GATEWAY_TIMEOUT,
        message: `the public gateway kept routing ${path} after the undeploy`,
      })
      .toBe(404);
  } finally {
    // The folder cascade undeploys too, but only at the end of the worker, and until then the
    // chain holds one of the HTTPRoute's 16 rules.
    await catalog.undeployAll(chain.id).catch(leftBehind(chain.name, "undeployed"));
  }
});
