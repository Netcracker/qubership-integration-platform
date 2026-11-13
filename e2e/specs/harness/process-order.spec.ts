import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { suiteProjects } from "../../playwright.config.js";
import { targetSetup } from "../../env/target-setup.js";
import { withEnv } from "../../support/with-env.js";

const E2E = path.join(repoRoot(), "e2e");

// Run the production project edges with small cases and a real resource-band assertion.
test.describe("process check scheduling", () => {
  for (const target of ["compose", "k8s"] as const) {
    test(`${target}: resource failures keep nightly checks running`, async ({}, testInfo) => {
      const directory = testInfo.outputPath(target);
      const events = path.join(directory, "events.jsonl");
      const snapshot = path.join(directory, "snapshot.json");
      const independent = ["env", "global", "brokers-restart", ...(target === "k8s" ? ["global-micro", "env-micro"] : [])];
      const names = ["process-snapshot", "process-envelope", ...independent];
      const projects = withEnv({ E2E_BROKERS: undefined }, () => suiteProjects(targetSetup(target)))
        .filter((project) => names.includes(project.name!));
      for (const project of projects) {
        const folder = path.join(directory, project.name!);
        fs.mkdirSync(folder, { recursive: true });
        fs.writeFileSync(path.join(folder, "case.spec.ts"), `
          import fs from "node:fs";
          import { test, expect } from "@playwright/test";
          import { BANDS, outsideBand } from ${JSON.stringify(pathToFileURL(path.join(E2E, "env/resources.ts")).href)};
          import { capturedValue, readProcessSnapshot, saveProcessSnapshot } from ${JSON.stringify(pathToFileURL(path.join(E2E, "env/process-snapshot.ts")).href)};
          test("records the project result", async () => {
            fs.appendFileSync(${JSON.stringify(events)}, JSON.stringify(${JSON.stringify(project.name)}) + "\\n");
            if (${JSON.stringify(project.name)} === "process-snapshot") {
              await saveProcessSnapshot({
                processEnvelope: async (role) => ({ role, startedAt: "before restarts", restarts: 0, metaspace: null }),
                resourcePeaks: async () => [{ role: "engine", memoryBytes: BANDS.engine.memoryBytes + 1, cpuPercent: 0, samples: 2 }],
              }, "ab12cd", ${JSON.stringify(snapshot)}, []);
            }
            if (${JSON.stringify(project.name)} === "process-envelope") {
              const snapshot = readProcessSnapshot("ab12cd", ${JSON.stringify(snapshot)});
              expect(capturedValue(snapshot.envelopes).every((entry) => entry.startedAt === "before restarts")).toBe(true);
              expect(outsideBand(capturedValue(snapshot.peaks))).toEqual([]);
            }
          });
        `);
      }
      const config = path.join(directory, "playwright.config.ts");
      fs.writeFileSync(config, `export default ${JSON.stringify({
        workers: 1,
        retries: 0,
        reporter: "json",
        outputDir: path.join(directory, "results"),
        projects: projects.map((project) => ({
          name: project.name,
          testDir: path.join(directory, project.name!),
          workers: 1,
          dependencies: project.dependencies?.filter((name) => names.includes(name)),
          ...(project.teardown && names.includes(project.teardown) ? { teardown: project.teardown } : {}),
        })),
      })};`);
      const result = spawnSync(process.execPath, [path.join(E2E, "node_modules", "@playwright", "test", "cli.js"), "test", "--config", config], {
        cwd: E2E,
        encoding: "utf8",
      });
      expect(result.status, result.stderr).toBe(1);
      const report = JSON.parse(result.stdout) as { stats: { unexpected: number; skipped: number } };
      expect(report.stats.unexpected).toBe(1);
      expect(report.stats.skipped).toBe(0);
      expect(result.stdout).toContain("engine peaked at");
      const ran = fs.readFileSync(events, "utf8").trim().split("\n").map((line) => JSON.parse(line) as string);
      expect(ran).toEqual(expect.arrayContaining(independent));
      expect(ran.indexOf("process-snapshot")).toBeLessThan(ran.indexOf("env"));
      expect(ran.indexOf("process-envelope")).toBeGreaterThan(Math.max(...independent.map((name) => ran.indexOf(name))));
    });
  }
});
