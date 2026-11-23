import { externalSettings } from "../env/external-settings.js";
import { newPlatformRequest } from "../env/platform-http.js";
import type { EchoChain } from "../suites/types.js";
import { withDeployedEcho } from "./deployed-echo.js";
import { externalTest as base } from "./fixtures.js";
import { tokenized } from "./run-token.js";

export const test = base.extend<{ echo: EchoChain }, { deployedEcho: EchoChain }>({
  deployedEcho: [async ({ catalog, env, folder, playwright, run }, use, workerInfo) => {
    const deployment = externalSettings()?.deployment;
    if (!deployment) throw new Error("Set deployment.domain for the external runtime suite.");
    const name = tokenized(run, `echo-w${workerInfo.workerIndex}`);
    const sessionsBase = env.url("sessions-management");
    const engine = await newPlatformRequest(playwright.request, "engine", env.engineUrl());
    const sessions = await newPlatformRequest(playwright.request, "sessions-management", sessionsBase);
    try {
      await withDeployedEcho(catalog, engine, sessions, {
        name, parentId: folder.id, marker: run, url: env.chainUrl(name), deployment,
        timeout: 120_000, sessionsBase,
      }, use);
    } finally {
      await Promise.all([sessions.dispose(), engine.dispose()]);
    }
  }, { scope: "worker", timeout: 180_000 }],
  echo: async ({ deployedEcho }, use) => { await use(deployedEcho); },
});
