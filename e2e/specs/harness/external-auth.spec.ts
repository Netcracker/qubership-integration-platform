// Shown failing: bypassing the in-flight cache duplicates refreshes. Reusing expired UI state
// fails the renewal assertion. Selecting Engine for Catalog or following an unsigned redirect
// fails the transport assertions.
import { createServer } from "node:http";
import { writeFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { createHeaderCache, createUiSession } from "../../env/external-auth.js";
import { authenticatedRequest, newPlatformRequest, pathWithin, platformFetch } from "../../env/platform-http.js";

test.describe("external authorization", () => {
  test("shares a refresh and renews before the next request", async () => {
    let now = 0;
    let refreshes = 0;
    const headers = createHeaderCache(() => now);
    const refresh = async () => ({ headers: { authorization: `token-${++refreshes}` }, expiresAt: now + 10_000 });
    const get = () => headers("engine", refresh);
    expect(await Promise.all([get(), get()])).toEqual([{ authorization: "token-1" }, { authorization: "token-1" }]);
    expect(await headers("catalog", refresh)).toEqual({ authorization: "token-2" });
    now = 6_000;
    expect(await Promise.all([get(), get()])).toEqual([{ authorization: "token-3" }, { authorization: "token-3" }]);
  });

  test("renews a browser session between contexts", async () => {
    let now = 0;
    let logins = 0;
    const session = createUiSession(() => now);
    const state = () => session(async () => ({ state: `session-${++logins}.json`, expiresAt: now + 10_000 }));
    expect(await state()).toBe("session-1.json");
    expect(await state()).toBe("session-1.json");
    now = 10_000;
    expect(await state()).toBe("session-2.json");
  });

  test("leaves credentials off unrelated URLs and redirects", async ({ playwright }, info) => {
    const seen: Array<{ path: string; authorization: string | undefined }> = [];
    const server = createServer((req, res) => {
      seen.push({ path: req.url!, authorization: req.headers.authorization });
      if (req.url === "/engine/write" || req.url === "/engine/public-write") {
        res.writeHead(307, { Location: "/other/write" });
      }
      if (req.url === "/gateway/api/v1/cip/engine/live-exchanges") res.writeHead(307, { Location: "/other/read" });
      if (req.url === "/engine/denied") res.writeHead(401);
      res.end();
    });
    await new Promise<void>((ready) => server.listen(0, "127.0.0.1", ready));
    try {
      const address = server.address();
      if (!address || typeof address === "string") throw new Error("Expected a TCP address.");
      const base = new URL(`http://127.0.0.1:${address.port}/engine/`);
      const request = await playwright.request.newContext({ baseURL: base.href });
      try {
        const api = authenticatedRequest(request, base.href, async (url): Promise<Record<string, string>> =>
          pathWithin(url, base) === undefined ? {} : { Authorization: "token" });
        expect((await api.post("write")).status()).toBe(307);
        expect((await api.post("denied")).status()).toBe(401);
        expect((await api.get("../other/read")).status()).toBe(200);
      } finally {
        await request.dispose();
      }
      const auth = info.outputPath("auth.mjs");
      await writeFile(auth, 'export default { api: { headers: { engine: { Authorization: "native" }, "runtime-catalog": { Authorization: "catalog" } } } };');
      const original = process.env.QIP_E2E_EXTERNAL;
      const settings = { run: "transport", services: {
        engine: base.href, "runtime-catalog": new URL("../catalog", base).href,
      }, proxy: new URL("../gateway", base).href };
      process.env.QIP_E2E_EXTERNAL = JSON.stringify({ ...settings, auth });
      try {
        const proxy = new URL("../gateway/api/v1/cip/engine/live-exchanges", base);
        expect((await platformFetch(proxy)).status).toBe(307);
        expect((await platformFetch(new URL("../gateway/api/v1/other/engine/live-exchanges", base))).status).toBe(200);
        const paths = [
          new URL("../gateway/e2e/svc/cip-runtime-catalog-v1/v1/folders", base),
          new URL("../gateway/e2e/svc/unrelated/v1/folders", base),
        ];
        const context = await playwright.request.newContext();
        try {
          const api = authenticatedRequest(context);
          for (const target of paths) {
            expect((await api.get(target.href)).status()).toBe(200);
            expect((await platformFetch(target)).status).toBe(200);
          }
        } finally {
          await context.dispose();
        }
        process.env.QIP_E2E_EXTERNAL = JSON.stringify(settings);
        const publicWrite = new URL("public-write", base);
        const direct = await newPlatformRequest(playwright.request, "engine", base.href);
        try {
          expect((await direct.post(publicWrite.href)).status()).toBe(307);
          expect((await platformFetch(publicWrite, { method: "POST" })).status).toBe(307);
        } finally {
          await direct.dispose();
        }
      } finally {
        if (original === undefined) delete process.env.QIP_E2E_EXTERNAL;
        else process.env.QIP_E2E_EXTERNAL = original;
      }
      expect(seen).toEqual([
        { path: "/engine/write", authorization: "token" },
        { path: "/engine/denied", authorization: "token" },
        { path: "/other/read", authorization: undefined },
        { path: "/gateway/api/v1/cip/engine/live-exchanges", authorization: "native" },
        { path: "/gateway/api/v1/other/engine/live-exchanges", authorization: undefined },
        { path: "/gateway/e2e/svc/cip-runtime-catalog-v1/v1/folders", authorization: "catalog" },
        { path: "/gateway/e2e/svc/cip-runtime-catalog-v1/v1/folders", authorization: "catalog" },
        { path: "/gateway/e2e/svc/unrelated/v1/folders", authorization: undefined },
        { path: "/gateway/e2e/svc/unrelated/v1/folders", authorization: undefined },
        { path: "/engine/public-write", authorization: undefined },
        { path: "/engine/public-write", authorization: undefined },
      ]);
    } finally {
      await new Promise<void>((closed, reject) => server.close((error) => error ? reject(error) : closed()));
    }
  });
});
