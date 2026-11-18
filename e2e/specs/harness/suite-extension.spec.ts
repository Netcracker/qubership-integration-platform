// Shown failing: leaving relative URLs unresolved omits the catalog token from the request.
// Shown failing: removing the UI setup's finally block leaves the bundle reservation in place.
// Shown failing: following redirects sends platform headers to the redirect target.
import { test, expect, request, type APIRequestContext } from "@playwright/test";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { once } from "node:events";
import { configureSuite, suiteExtension, apiOptions } from "../../env/suite-extension.js";
import { platformFetch, authenticatedRequest, newPlatformRequest } from "../../env/platform-http.js";
import { ensureUiServer, stopUiServer } from "../../support/ui-server.js";
import { ensureVsCodeServer, stopVsCodeServer } from "../../support/vscode-server.js";
import { ensureSchemas } from "../../support/schemas-build.js";
import { targetSetup } from "../../env/target-setup.js";
import { kubectl, helm, deploymentOf, containerOf, microServiceOf } from "../../env/k8s.js";
import { BuildLock, BUNDLE_HOLDER } from "../../support/build-lock.js";
import { endpointRequest } from "../../portable/connection.js";
import fs from "node:fs";

const initial = suiteExtension();
const originalFetch = globalThis.fetch;
const variables = ["QIP_E2E_API_OPTIONS", "CIP_CATALOG_URL", "CIP_ENGINE_URL", "CIP_SESSIONS_URL", "CIP_TESTING_SERVICE_URL", "CIP_PROXY_URL"];
let saved: Record<string, string | undefined>;

test.describe("suite extension", () => {
  test.beforeEach(() => {
    saved = Object.fromEntries(variables.map((name) => [name, process.env[name]]));
    process.env.CIP_CATALOG_URL = "http://platform.test/catalog";
    process.env.CIP_ENGINE_URL = "http://platform.test/engine";
    process.env.CIP_SESSIONS_URL = "http://platform.test/sessions";
    process.env.CIP_TESTING_SERVICE_URL = "http://platform.test/testing";
    process.env.CIP_PROXY_URL = "http://platform.test/ui";
    process.env.QIP_E2E_API_OPTIONS = JSON.stringify({
      common: { extraHTTPHeaders: { Authorization: "Bearer common" } },
      services: { "runtime-catalog": { extraHTTPHeaders: { Authorization: "Bearer catalog", "x-catalog": "yes" } } },
    });
  });

  test.afterEach(() => {
    globalThis.fetch = originalFetch;
    configureSuite(initial);
    for (const [name, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  });

  test("uses the service credentials during cleanup fetches", async () => {
    let received = new Headers();
    globalThis.fetch = async (_input, init) => {
      received = new Headers(init?.headers);
      return new Response(null, { status: 204 });
    };
    await platformFetch("http://platform.test/catalog/v1/chains/owned", { method: "DELETE" });
    expect(received.get("authorization")).toBe("Bearer catalog");
    expect(received.get("x-catalog")).toBe("yes");
    expect(apiOptions("runtime-catalog")).toMatchObject({ authorization: "Bearer catalog" });
  });

  test("keeps platform credentials off unrelated endpoints", async () => {
    let received: RequestInit | undefined;
    globalThis.fetch = async (_input, init) => { received = init; return new Response("ok"); };
    await platformFetch("http://platform.test/catalog-other/receiver", { method: "POST" });
    expect(new Headers(received?.headers).has("authorization")).toBe(false);
  });

  test("adds credentials to raw Playwright service calls", async () => {
    let received: { headers?: Record<string, string> } | undefined;
    const api = { get: async (_url: string, options?: typeof received) => { received = options; } } as unknown as APIRequestContext;
    process.env.CIP_CATALOG_URL = "http://PLATFORM.test:80/catalog";
    const authenticated = authenticatedRequest(api, "http://platform.test/catalog/");
    await authenticated.get("v1/chains", { headers: { "x-test": "case" } });
    expect(received?.headers).toEqual({ authorization: "Bearer catalog", "x-catalog": "yes", "x-test": "case" });
    await authenticated.get("http://platform.test/ui/api/cip/v1/folders");
    expect(received?.headers).toMatchObject({ authorization: "Bearer catalog" });
  });

  test("returns redirects before forwarding credentials", async () => {
    const followed: string[] = [];
    const server = createServer((req, res) => {
      if (req.url === "/catalog/redirect" || req.url === "/catalog/local-redirect") {
        const port = (server.address() as AddressInfo).port;
        const location = req.url === "/catalog/local-redirect" ? "/outside" : `http://localhost:${port}/outside`;
        res.writeHead(302, { location }).end();
        return;
      }
      followed.push(req.headers["x-catalog"] as string);
      res.end();
    });
    server.listen(0);
    await once(server, "listening");
    const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    process.env.CIP_CATALOG_URL = `${origin}/catalog`;
    process.env.CIP_PROXY_URL = origin;
    const api = await newPlatformRequest(request, "runtime-catalog");
    const raw = await request.newContext();
    const portable = await endpointRequest(request, {
      url: process.env.CIP_CATALOG_URL,
      extraHTTPHeaders: { "x-catalog": "yes" },
    });
    try {
      const url = `${origin}/catalog/redirect`;
      expect.soft((await platformFetch(url)).status).toBe(302);
      expect.soft((await authenticatedRequest(raw).get(url, { maxRedirects: 5 })).status()).toBe(302);
      expect.soft((await authenticatedRequest(raw).get(`${origin}/catalog/local-redirect`)).status()).toBe(302);
      expect.soft((await api.get(url)).status()).toBe(302);
      expect.soft((await portable.get(url)).status()).toBe(302);
      expect.soft(followed).toEqual([]);
      await platformFetch(`${origin}/catalog/../outside`);
      expect(followed).toEqual([undefined]);
    } finally {
      await Promise.all([api.dispose(), raw.dispose(), portable.dispose()]);
      await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    }
  });

  test("delegates all frontend setup and teardown operations", async ({}, info) => {
    const calls: string[] = [];
    configureSuite({ lifecycle: {
      schemas: async () => { calls.push("schemas"); return false; },
      startUi: async () => { calls.push("start-ui"); return { pid: null, built: false }; },
      stopUi: async () => { calls.push("stop-ui"); },
      startVsCode: async () => { calls.push("start-vscode"); return { pid: 42, built: [] }; },
      stopVsCode: async () => { calls.push("stop-vscode"); },
    } });
    expect(await ensureSchemas()).toBe(false);
    expect(await ensureUiServer(new BuildLock(info.outputPath("bundle-lock")))).toEqual({ pid: null, built: false });
    await stopUiServer();
    expect(await ensureVsCodeServer()).toEqual({ pid: 42, built: [] });
    await stopVsCodeServer();
    expect(calls).toEqual(["schemas", "start-ui", "stop-ui", "start-vscode", "stop-vscode"]);
  });

  test("releases the bundle reservation for an external UI", async ({}, info) => {
    configureSuite({ lifecycle: { startUi: async () => ({ pid: null, built: false }) } });
    const lock = new BuildLock(info.outputPath("bundle-lock"), process.pid, process.pid);
    const reserved = lock.reserve(BUNDLE_HOLDER);
    try {
      expect(reserved).toBe(true);
      await ensureUiServer(lock);
      expect(fs.existsSync(lock.file)).toBe(false);
    } finally {
      if (reserved) lock.dropReservation(BUNDLE_HOLDER);
    }
  });

  test("delegates service and broker restarts to the target", async () => {
    const calls: string[] = [];
    configureSuite({ target: (base) => ({
      ...base,
      createEnv(kind, state) {
        return Object.assign(Object.create(base.createEnv(kind, state)), {
          async restartWith(role: string) { calls.push(`service:${role}`); },
          async restartOverlay(broker: string) { calls.push(`broker:${broker}`); },
        });
      },
    }) });
    const env = targetSetup().createEnv("classic");
    await env.restartWith("engine", { example: "value" });
    await env.restartOverlay("kafka");
    expect(calls).toEqual(["service:engine", "broker:kafka"]);
  });

  test("uses the application's Kubernetes commands and names", async () => {
    const calls: string[][] = [];
    configureSuite({ kubernetes: {
      kubectl: async (args) => { calls.push(args); return "kubectl output"; },
      helm: async (args) => { calls.push(args); return "helm output"; },
      deployment: (role) => `application-${role}`,
      container: (role) => `container-${role}`,
      microService: (domain) => `micro-${domain}`,
    } });
    expect(await kubectl(["get", "pods"])).toBe("kubectl output");
    expect(await helm(["list"])).toBe("helm output");
    expect(calls).toEqual([["get", "pods"], ["list"]]);
    expect(deploymentOf("engine")).toBe("application-engine");
    expect(containerOf("engine")).toBe("container-engine");
    expect(microServiceOf("domain")).toBe("micro-domain");
  });
});
