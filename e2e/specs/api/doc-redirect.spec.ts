/**
 * The redirect nginx sends itself from `/doc` to `/doc/`.
 *
 * On the cluster the proxy listens on 8080 and is published on 30080, and an absolute `Location`
 * used to send the browser to 8080 (#1163). On Compose both ports are 8080, so the case passes there
 * either way.
 */
import { test, expect } from "../../support/fixtures.js";
import { proxyUrl } from "../../env/containers.js";

test.describe("doc redirect", { tag: ["@infra"] }, () => {
  test("/doc redirects to /doc/ on the origin the request came to", async ({ request }) => {
    const url = `${proxyUrl()}/doc`;
    const response = await request.get(url, { maxRedirects: 0 });
    expect(response.status()).toBe(301);
    expect(new URL(response.headers()["location"], url).href).toBe(`${url}/`);
  });
});
