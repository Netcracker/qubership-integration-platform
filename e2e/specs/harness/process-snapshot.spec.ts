import { test, expect } from "@playwright/test";
import fs from "node:fs";
import { execFileSync, spawnSync } from "node:child_process";
import path from "node:path";
import { BANDS } from "../../env/resources.js";
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

  for (const target of ["compose", "k8s"] as const) {
    for (const state of ["missing", "foreign", "resource-violation"] as const) {
      test(`${target}: ${state} snapshot keeps the right verdict`, async ({}, testInfo) => {
        const directory = testInfo.outputPath("checks");
        fs.mkdirSync(directory, { recursive: true });
        const snapshot = path.join(directory, target === "compose" ? ".e2e-process-snapshot.json" : ".e2e-k8s-process-snapshot.json");
        if (state !== "missing") {
          const envelopes = ROLES.map((role) => ({
            role, startedAt: "before restarts", restarts: 0,
            metaspace: role === "testing-service" ? null : { usedBytes: 10, maxBytes: 100 },
          }));
          await saveProcessSnapshot({
            processEnvelope: async (role) => envelopes.find((entry) => entry.role === role)!,
            resourcePeaks: async () => ROLES.map((role) => ({
              role, memoryBytes: BANDS[role].memoryBytes + 1, cpuPercent: 0, samples: 2,
            })),
          }, state === "foreign" ? "zz99zz" : EXAMPLE_RUN_TOKEN, snapshot, envelopes);
        }
        const e2e = path.join(repoRoot(), "e2e");
        const config = path.join(directory, "playwright.config.ts");
        fs.writeFileSync(config, `export default ${JSON.stringify({
          testDir: path.join(e2e, "specs/env"), testMatch: "process-envelope.spec.ts",
          workers: 1, retries: 0, reporter: "json", outputDir: path.join(directory, "results"),
        })};`);
        const result = spawnSync(process.execPath, [path.join(e2e, "node_modules/@playwright/test/cli.js"), "test", "--config", config], {
          cwd: e2e, encoding: "utf8", timeout: 30_000,
          env: { ...process.env, CIP_TARGET: target, E2E_RUN: EXAMPLE_RUN_TOKEN, E2E_RESOURCES_FILE: path.join(directory, "resources.json") },
        });
        expect(result.status, result.stderr).toBe(1);
        const report = JSON.parse(result.stdout) as { stats: { unexpected: number; skipped: number; expected: number } };
        expect(report.stats.unexpected).toBe(1);
        expect(report.stats.skipped).toBe(state === "resource-violation" ? 0 : 2);
        expect(report.stats.expected).toBe(state === "resource-violation" ? 2 : 0);
        expect(result.stdout).toContain(state === "resource-violation" ? "engine peaked at" : "No process snapshot for run");
      });
    }
  }
});
