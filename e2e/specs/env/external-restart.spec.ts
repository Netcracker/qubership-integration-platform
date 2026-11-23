// Shown failing: an empty restart log fails the entry check, and an ERROR entry fails the
// severity check. A one-sample peak fails the sample count check.
import { expect } from "@playwright/test";
import { assertExternalAuthorized } from "../../env/external-auth.js";
import { test } from "../../support/external-echo.js";
import { waitForEchoRoute } from "../../support/deployed-echo.js";
import { callChain } from "../../support/sessions.js";

const ERROR_LOG_LEVEL = /\[\s*(?:ERROR|FATAL)\s*\]|^\s*(?:ERROR|FATAL)(?:\s|:)|"level"\s*:\s*"(?:ERROR|FATAL)"/i;

test.describe("external engine restart", { tag: ["@engine", "@infra", "@slow"] }, () => {
  test("restores the deployed echo without redeployment", async ({ catalog, echo, env, request }) => {
    const deploymentRows = async () => (await catalog.listDeployments(echo.id)).map(({ id, snapshotId }) => ({ id, snapshotId }));
    const before = await deploymentRows();
    expect(before).toHaveLength(1);

    const infrastructure = env.infrastructure!;
    const since = new Date().toISOString();
    const previous = await infrastructure.processEnvelope("engine");
    await infrastructure.restart("engine");
    expect(await infrastructure.processEnvelope("engine")).not.toMatchObject({
      startedAt: previous.startedAt, restarts: previous.restarts,
    });

    await waitForEchoRoute(request, echo.url, 405, 30_000);
    const { response } = await callChain(request, echo.url, { data: { ping: "restart" } });
    try {
      assertExternalAuthorized(response.status(), "engine");
      expect(response.status()).toBe(200);
      expect(await response.json()).toEqual({ ping: "restart" });
      expect(response.headers()[echo.markerHeader]).toBe(echo.marker);
    } finally {
      await response.dispose();
    }

    expect(await deploymentRows()).toEqual(before);

    if (infrastructure.engineLogs || infrastructure.logs) {
      await test.step("the restarted engine logs the deployment without an error", async () => {
        const log = infrastructure.engineLogs
          ? await infrastructure.engineLogs(since)
          : await infrastructure.logs!("engine", since);
        const id = before[0].id;
        const lines = log.split("\n").filter((line) => line.includes(id) || line.includes(echo.id));
        expect(lines.length, `Engine log entries for deployment ${id}`).toBeGreaterThan(0);
        expect(lines.some((line) => ERROR_LOG_LEVEL.test(line)),
          `Engine log errors for deployment ${id}`).toBe(false);
      });
    }
    if (infrastructure.resourcePeaks) {
      await test.step("the adapter reports sampled engine resource peaks", async () => {
        const peak = (await infrastructure.resourcePeaks!()).find(({ role }) => role === "engine");
        expect(peak, "Engine resource peak").toBeDefined();
        expect(peak!.samples).toBeGreaterThanOrEqual(2);
        expect(peak!.memoryBytes).toBeGreaterThan(0);
        expect(peak!.cpuPercent).toBeGreaterThanOrEqual(0);
      });
    }
  });
});
