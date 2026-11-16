import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";
import path from "node:path";
import { ROLES } from "../../env/containers.js";
import { repoRoot } from "../../env/host.js";
import type { Env } from "../../env/index.js";
import { capturedValue, readProcessSnapshot, saveProcessSnapshot } from "../../env/process-snapshot.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";
import { untaggedSpecs } from "../../registry/elements.js";

test.describe("process snapshot", () => {
  test("collector metadata passes component reconciliation", () => {
    const directory = path.join(repoRoot(), "e2e");
    const report = JSON.parse(execFileSync(process.execPath, [
      path.join(directory, "node_modules", "@playwright", "test", "cli.js"), "test", "--project=process-snapshot", "--no-deps", "--list", "--reporter=json",
    ], { cwd: directory, encoding: "utf8" })) as Parameters<typeof untaggedSpecs>[0];
    expect(untaggedSpecs(report)).toEqual([]);
  });

  test("keeps readings from before restarts and later load", async ({}, testInfo) => {
    const file = testInfo.outputPath("snapshot.json");
    let startedAt = "before restarts";
    let memoryBytes = 100;
    const env: Pick<Env, "processEnvelope" | "resourcePeaks"> = {
      processEnvelope: async (role) => ({ role, startedAt, restarts: 0, metaspace: null }),
      resourcePeaks: async () => [{ role: "engine", memoryBytes, cpuPercent: 10, samples: 2 }],
    };
    const baseline = [await env.processEnvelope("engine")];
    await saveProcessSnapshot(env, EXAMPLE_RUN_TOKEN, file, baseline);
    startedAt = "after restarts";
    memoryBytes = 200;
    const snapshot = readProcessSnapshot(EXAMPLE_RUN_TOKEN, file);
    expect(snapshot.baseline).toEqual(baseline);
    expect(capturedValue(snapshot.envelopes)).toEqual(ROLES.map((role) => ({
      role, startedAt: "before restarts", restarts: 0, metaspace: null,
    })));
    expect(capturedValue(snapshot.peaks)[0].memoryBytes).toBe(100);
  });

  test("records read errors for the later assertions", async ({}, testInfo) => {
    const file = testInfo.outputPath("snapshot.json");
    await saveProcessSnapshot({
      processEnvelope: async () => { throw new Error("process reading unavailable"); },
      resourcePeaks: async () => { throw new Error("peak reading unavailable"); },
    }, EXAMPLE_RUN_TOKEN, file, []);
    const snapshot = readProcessSnapshot(EXAMPLE_RUN_TOKEN, file);
    expect(() => capturedValue(snapshot.envelopes)).toThrow("process reading unavailable");
    expect(() => capturedValue(snapshot.peaks)).toThrow("peak reading unavailable");
  });

  test("refuses missing readings and readings from another run", async ({}, testInfo) => {
    const file = testInfo.outputPath("snapshot.json");
    expect(() => readProcessSnapshot(EXAMPLE_RUN_TOKEN, file)).toThrow("No process snapshot");
    await saveProcessSnapshot({
      processEnvelope: async (role) => ({ role, startedAt: "before restarts", restarts: 0, metaspace: null }),
      resourcePeaks: async () => [],
    }, EXAMPLE_RUN_TOKEN, file, []);
    expect(() => readProcessSnapshot("zz99zz", file)).toThrow("No process snapshot for run zz99zz");
  });
});
