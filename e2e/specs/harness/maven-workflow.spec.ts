/**
 * Shown failing: remove the shared-workflow path from the catalog trigger (consumer routing);
 * replace stale-OpenAPI removal with a no-op (the generator-output assertion);
 * remove the engine source trigger (selective module routing).
 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { globRegExp } from "../../support/pr-changes.mjs";

type Workflow = {
  on?: { pull_request?: { paths?: string[] }; push?: { paths?: string[] } };
  jobs?: Record<string, { uses?: string; steps?: { name?: string; run?: string }[] }>;
};

const DIRECTORY = path.join(repoRoot(), ".github/workflows");
const SHARED = [
  ".github/workflows/_maven-module-build.yaml",
  ".github/maven-settings.xml",
];

const workflows = () => fs.readdirSync(DIRECTORY).filter((name) => name.endsWith(".yaml")).map((name) => ({
  name,
  workflow: yaml.load(fs.readFileSync(path.join(DIRECTORY, name), "utf-8")) as Workflow,
}));

const matches = (patterns: string[], file: string) => patterns.some((pattern) => globRegExp(pattern).test(file));

test.describe("Maven workflow selection", () => {
  test("shared implementation changes run every Maven consumer", () => {
    const consumers = workflows().filter(({ workflow }) => Object.values(workflow.jobs ?? {})
      .some((job) => job.uses === "./.github/workflows/_maven-module-build.yaml"));
    expect(consumers.length).toBeGreaterThan(0);
    for (const { name, workflow } of consumers) {
      const patterns = workflow.on?.pull_request?.paths ?? workflow.on?.push?.paths ?? [];
      for (const file of [...SHARED, `.github/workflows/${name}`]) {
        expect(matches(patterns, file), `${name} must run for ${file}`).toBe(true);
      }
    }
  });

  for (const forceUpdate of ["false", "true"]) {
    test(`regenerates OpenAPI with force-update=${forceUpdate}`, () => {
      const workflow = workflows().find(({ name }) => name === "_maven-module-build.yaml")!.workflow;
      const command = workflow.jobs?.build.steps?.find((step) => step.name === "Build and verify (no publish)")?.run;
      expect(command).toBeTruthy();
      const directory = fs.mkdtempSync(path.join(os.tmpdir(), "maven-openapi-"));
      try {
        fs.mkdirSync(path.join(directory, "bin"));
        fs.mkdirSync(path.join(directory, "service/api-spec"), { recursive: true });
        const output = path.join(directory, "service/api-spec/openapi.yaml");
        fs.writeFileSync(output, "committed specification");
        fs.writeFileSync(path.join(directory, "bin/mvn"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > maven-args.txt\n", { mode: 0o755 });
        const result = spawnSync("bash", ["-eu", "-c", command!], {
          cwd: directory,
          env: { ...process.env, PATH: `${path.join(directory, "bin")}:${process.env.PATH}`, GITHUB_WORKSPACE: directory, MODULE_NAME: "service", FORCE_UPDATE: forceUpdate, OPENAPI_CHECK: "true" },
          encoding: "utf-8",
        });
        expect(result.status, result.stderr).toBe(0);
        expect(fs.existsSync(output), "an unchanged checkout file cannot substitute for generator output").toBe(false);
        expect(fs.readFileSync(path.join(directory, "maven-args.txt"), "utf-8").trim().split("\n")).toEqual([
          "--batch-mode", "-s", `${directory}/.github/maven-settings.xml`, "verify",
          ...(forceUpdate === "true" ? ["-U"] : []), "-pl", "service", "-am", "-Dgpg.skip=true",
        ]);
      } finally {
        fs.rmSync(directory, { recursive: true, force: true });
      }
    });
  }

  test("module source changes retain selective Maven builds", () => {
    const byName = new Map(workflows().map(({ name, workflow }) => [name, workflow]));
    const affected = (file: string) => [...byName].filter(([, workflow]) =>
      workflow.on?.pull_request?.paths && matches(workflow.on.pull_request.paths, file),
    ).map(([name]) => name);
    expect(affected("engine/src/main/java/App.java")).toContain("engine-build.yaml");
    expect(affected("engine/src/main/java/App.java")).not.toContain("runtime-catalog-build.yaml");
    expect(affected("integration-build-pipeline/src/main/java/App.java")).toEqual(expect.arrayContaining([
      "integration-build-pipeline-build.yaml", "runtime-catalog-build.yaml", "integration-build-maven-plugin-build.yaml",
    ]));
  });
});
