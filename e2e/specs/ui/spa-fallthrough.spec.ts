/**
 * nginx sends every path no `location` claims to the UI bundle, `/routes/` included.
 *
 * In this project rather than beside the prefix table in `specs/api/api-prefixes.spec.ts`, because
 * the answer comes from the bundle `ui-server` serves on 4200. With nothing listening there, the
 * proxy answers 502, and the `api` project starts before a rebuilt bundle is up. No page is opened.
 */
import { test, expect } from "../../support/fixtures.js";

test.describe("SPA fallthrough", () => {
  // No location matches `/routes/`, so the proxy answers 200 with the SPA's index.html whatever the
  // chain did. A runtime spec addressed through it could not fail, so `Env.chainUrl` resolves the
  // engine instead.
  test("/routes/ answers the SPA on the proxy and 404 on the engine", async ({ request, env }) => {
    // The `ui` project's base URL is the proxy.
    const throughProxy = await request.get("/routes/no-such-chain", { failOnStatusCode: false });
    expect(throughProxy.status()).toBe(200);
    expect(throughProxy.headers()["content-type"]).toMatch(/^text\/html/);

    const onTheEngine = await request.get(env.chainUrl("no-such-chain"), { failOnStatusCode: false });
    expect(onTheEngine.status()).toBe(404);
    expect(onTheEngine.headers()["content-type"] ?? "").not.toMatch(/^text\/html/);
  });
});
