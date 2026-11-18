import { test as base, expect, type APIRequestContext } from "@playwright/test";
import { Catalog } from "../support/catalog.js";
import { Engine } from "../support/engine.js";
import { Sessions, callChain } from "../support/sessions.js";
import { createDeployableChain, MARKER_HEADER } from "../support/chain-builder.js";
import { generateRunToken, tokenized, workerFolderName } from "../support/run-token.js";
import type { CatalogSuiteFixtures, EchoChain } from "../suites/types.js";
import { connection, endpointOptions, baseUrl, type QipOptions } from "./connection.js";

export interface QipTestFixtures {
  sessions: Sessions;
  echo: EchoChain;
}

export interface QipWorkerFixtures extends CatalogSuiteFixtures {
  engine: Engine;
  _catalogRequest: APIRequestContext;
  _engineRequest: APIRequestContext;
  _sessionsRequest: APIRequestContext;
  _echo: EchoChain;
}

export const test = base.extend<QipTestFixtures, QipOptions & QipWorkerFixtures>({
  qip: [undefined, { option: true, scope: "worker" }],

  _catalogRequest: [async ({ playwright, qip }, use) => {
    const api = await playwright.request.newContext(endpointOptions(connection(qip).catalog));
    try { await use(api); } finally { await api.dispose(); }
  }, { scope: "worker" }],

  _engineRequest: [async ({ playwright, qip }, use) => {
    const api = await playwright.request.newContext(endpointOptions(connection(qip).engine));
    try { await use(api); } finally { await api.dispose(); }
  }, { scope: "worker" }],

  _sessionsRequest: [async ({ playwright, qip }, use) => {
    const api = await playwright.request.newContext(endpointOptions(connection(qip).sessions));
    try { await use(api); } finally { await api.dispose(); }
  }, { scope: "worker" }],

  catalog: [async ({ _catalogRequest, qip }, use) => {
    await use(new Catalog(_catalogRequest, baseUrl(connection(qip).catalog)));
  }, { scope: "worker" }],

  engine: [async ({ _engineRequest, qip }, use) => {
    await use(new Engine(_engineRequest, baseUrl(connection(qip).engine)));
  }, { scope: "worker" }],

  sessions: async ({ _sessionsRequest, qip }, use) => {
    await use(new Sessions(_sessionsRequest, { base: baseUrl(connection(qip).sessions) }));
  },

  request: async ({ playwright, qip }, use) => {
    const api = await playwright.request.newContext(endpointOptions(connection(qip).engine));
    try { await use(api); } finally { await api.dispose(); }
  },

  run: [async ({}, use) => { await use(generateRunToken()); }, { scope: "worker" }],

  folder: [async ({ catalog, run }, use, workerInfo) => {
    const folder = await catalog.createFolder(workerFolderName(run, workerInfo.workerIndex));
    console.log(`[qip-e2e] folder ${folder.name} (${folder.id})`);
    try { await use(folder); } finally { await catalog.deleteFolder(folder.id); }
  }, { scope: "worker" }],

  _echo: [async ({ catalog, folder, run, qip, _engineRequest, _sessionsRequest }, use) => {
    const settings = connection(qip);
    const timeout = settings.deploymentTimeout ?? 120_000;
    const name = tokenized(run, "portable-echo");
    const chain = await createDeployableChain(catalog, {
      name, parentId: folder.id, contextPath: name, marker: run,
    });
    console.log(`[qip-e2e] echo chain ${chain.name} (${chain.id})`);
    const prefix = (settings.routePrefix ?? "routes").replace(/^\/+|\/+$/g, "");
    const url = [baseUrl(settings.engine), prefix, name].filter(Boolean).join("/");
    try {
      await catalog.saveLoggingProperties(chain.id, { sessionsLoggingLevel: "DEBUG" });
      const snapshot = await catalog.createSnapshot(chain.id);
      await catalog.deploy(chain.id, snapshot.id, settings.deployment.domain, settings.deployment.domainType);
      await expect(async () => {
        const response = await _engineRequest.post(url, {
          data: { ping: "ready" }, headers: { "Content-Type": "application/json" },
        });
        expect(response.status()).toBe(200);
        expect(response.headers()[MARKER_HEADER]).toBe(run);
        await response.dispose();
      }).toPass({ timeout });
      const sessions = new Sessions(_sessionsRequest, { base: baseUrl(settings.sessions) });
      const warmup = await callChain(_engineRequest, url, { data: { ping: "recording" } });
      expect(warmup.response.status()).toBe(200);
      await sessions.byExternalId(warmup.token, { elements: 3 });
      await use({
        id: chain.id, url, marker: run, markerHeader: MARKER_HEADER,
        elementNames: ["HTTP Trigger", "Validate Request", "Header Modification"],
      });
    } finally {
      try {
        await catalog.undeployAll(chain.id);
        await expect(async () => {
          const response = await _engineRequest.post(url, { data: {} });
          expect(response.status()).toBe(404);
          await response.dispose();
        }).toPass({ timeout });
      } finally {
        try { await catalog.deleteLoggingProperties(chain.id); } finally {
          const response = await _sessionsRequest.delete(`${baseUrl(settings.sessions)}/v1/sessions/chains`, {
            params: { chainIds: chain.id },
          });
          expect(response.ok(), "Delete the sessions created by the echo chain").toBe(true);
        }
      }
    }
  }, { scope: "worker", timeout: 180_000 }],

  echo: async ({ _echo }, use) => { await use(_echo); },
});

export { expect };
