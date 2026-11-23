import { test as base, expect, type APIRequestContext } from "@playwright/test";
import { Catalog } from "../support/catalog.js";
import { Engine } from "../support/engine.js";
import { Sessions } from "../support/sessions.js";
import { withDeployedEcho } from "../support/deployed-echo.js";
import { generateRunToken, tokenized, workerFolderName } from "../support/run-token.js";
import type { CatalogSuiteFixtures, EchoChain } from "../suites/types.js";
import { connection, endpointRequest, baseUrl, type QipOptions } from "./connection.js";

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
    const api = await endpointRequest(playwright.request, connection(qip).catalog);
    try { await use(api); } finally { await api.dispose(); }
  }, { scope: "worker" }],

  _engineRequest: [async ({ playwright, qip }, use) => {
    const api = await endpointRequest(playwright.request, connection(qip).engine);
    try { await use(api); } finally { await api.dispose(); }
  }, { scope: "worker" }],

  _sessionsRequest: [async ({ playwright, qip }, use) => {
    const api = await endpointRequest(playwright.request, connection(qip).sessions);
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
    const api = await endpointRequest(playwright.request, connection(qip).engine);
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
    const name = tokenized(run, "portable-echo");
    const prefix = (settings.routePrefix ?? "routes").replace(/^\/+|\/+$/g, "");
    const url = [baseUrl(settings.engine), prefix, name].filter(Boolean).join("/");
    await withDeployedEcho(catalog, _engineRequest, _sessionsRequest, {
      name, parentId: folder.id, marker: run, url, deployment: settings.deployment,
      timeout: settings.deploymentTimeout ?? 120_000, legacyRouteProbe: true, sessionsBase: baseUrl(settings.sessions),
    }, use);
  }, { scope: "worker", timeout: 180_000 }],

  echo: async ({ _echo }, use) => { await use(_echo); },
});

export { expect };
