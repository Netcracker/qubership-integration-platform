import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { globRegExp } from "../../support/pr-changes.mjs";

type Workflow = {
  on?: { pull_request?: { paths?: string[] }; push?: { paths?: string[] } };
  jobs?: Record<string, { uses?: string; needs?: string; steps?: { name?: string; run?: string; with?: Record<string, unknown> }[] }>;
};

const DIRECTORY = path.join(repoRoot(), ".github/workflows");
const SHARED = [
  ".github/workflows/_maven-module-build.yaml",
  ".github/maven-settings.xml",
  ".mvn/wrapper/maven-wrapper.properties",
  "mvnw",
  "mvnw.cmd",
  "scripts/maven-ci.sh",
  "scripts/maven_analysis.py",
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
