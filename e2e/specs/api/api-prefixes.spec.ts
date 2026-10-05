/**
 * The prefix table re-derived against the live proxy.
 *
 * The point is that it fails when the nginx config drifts rather than when some unrelated spec
 * does. Each row is fetched twice — once through the proxy's `/api/` surface and once on the
 * service's own port — and the two answers have to agree. A rewrite that stops reaching the service
 * shows up here as a mismatch naming the `location`, instead of as a 404 a spec somewhere else
 * reads as the platform's answer.
 *
 * Comparing status and content type rather than bodies, because a body can legitimately differ
 * between two calls a second apart. What the comparison catches is the failure that matters: the
 * SPA answering **200 `text/html`** for a path no `location` claimed, which is a pass to anything
 * checking only `response.ok()`.
 *
 * The chart keeps a separately maintained copy of this config in `charts/ui/templates/config.yaml`
 * and nothing enforces the correspondence, which is the whole reason the `/api/` surface is
 * addressed through a table at all rather than by concatenation.
 */
import { test, expect } from "../../support/fixtures.js";
import { proxyUrl } from "../../env/containers.js";
import { API_ROUTES, NOT_PROXIED, UNCLAIMED_API_PATH } from "../../env/api-routes.js";

/**
 * Rows whose answer depends on what the stack is doing at that instant.
 *
 * Both answer **204 with no content type** while they are empty and **200 `application/json`** once
 * they are not. The `api` project is fullyParallel at eight workers and runs beside the seed's
 * deploys, so a deployment landing between the proxied call and the direct one turns the row red
 * for a reason that has nothing to do with nginx. These two are asserted as "the request reached
 * the service" instead of compared answer for answer; every other row is state-independent and is
 * still compared in full.
 */
const STATE_DEPENDENT = new Set(["/v1/catalog/runtime-deployments", "/v1/engine/live-exchanges"]);

interface Answer {
  status: number;
  type: string;
}

async function answer(request: import("@playwright/test").APIRequestContext, url: string): Promise<Answer> {
  const response = await request.get(url, { failOnStatusCode: false });
  return { status: response.status(), type: (response.headers()["content-type"] ?? "").split(";")[0] };
}

for (const route of API_ROUTES.filter((each) => each.outcome === "proxied")) {
  test(`routes.conf:${route.line} — ${route.api} reaches ${route.role}`, { tag: ["@infra", "@tier1"] }, async ({ request, env }) => {
    const throughProxy = await answer(request, `${proxyUrl()}${route.api}`);
    // The failure this whole table exists for: no location matched and the SPA answered instead.
    expect(throughProxy.type, `${route.api} was answered by the SPA`).not.toBe("text/html");

    if (STATE_DEPENDENT.has(route.service)) {
      expect([200, 204], `${route.api} did not reach ${route.role}`).toContain(throughProxy.status);
      return;
    }

    const direct = await answer(request, `${env.url(route.role)}${route.service}`);
    expect(throughProxy, `${route.api} vs ${route.service}`).toEqual(direct);
  });
}

for (const route of API_ROUTES.filter((each) => each.outcome === "blocked")) {
  test(`routes.conf:${route.line} — ${route.api} is blocked on purpose`, { tag: ["@infra", "@tier1"] }, async ({ request, env }) => {
    // The engine calls endpoint mocks from inside the network. The rule returns 404 itself, so the
    // service still serves the path — which is what makes the block worth asserting.
    expect((await answer(request, `${proxyUrl()}${route.api}`)).status).toBe(404);
    expect((await answer(request, `${env.url(route.role)}${route.service}`)).status).not.toBe(404);
  });
}

test("the UI's /catalog/ form does not generalize", { tag: ["@infra", "@tier1"] }, async ({ request, env }) => {
  for (const shape of NOT_PROXIED) {
    const throughProxy = await answer(request, `${proxyUrl()}${shape.api}`);
    const direct = await answer(request, `${env.url("runtime-catalog")}${shape.service}`);
    expect(throughProxy.status, shape.why).toBe(404);
    expect(direct.status, shape.why).toBe(200);
  }
});

test("an /api/ path no service claims answers 404 rather than the SPA", { tag: ["@infra", "@tier1"] }, async ({ request }) => {
  const unclaimed = await answer(request, `${proxyUrl()}${UNCLAIMED_API_PATH}`);
  expect(unclaimed.status).toBe(404);
});
