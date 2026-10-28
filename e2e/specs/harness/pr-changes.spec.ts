/**
 * The path lists of `support/pr-changes.mjs`, which decide the gated jobs of the PR workflow, and
 * the `changes` job that reads them.
 *
 * Shown failing: `*` translated to `.*` ("a change elsewhere skips every gated job"); the
 * `e2e/pages/VsCodePage.ts` entry renamed to `e2e/pages/VSCodePage.ts` ("every path matches a
 * tracked file" and "a change under each path runs the vscode job"); the job output renamed to
 * `vs-code`, and the vscode job's `if` replaced by `always()` ("the workflow gates each job on its
 * output"); the printed value negated ("the script prints one output per job").
 */
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { JOB_PATHS, globRegExp, jobsFor } from "../../support/pr-changes.mjs";

const SCRIPT = path.join(repoRoot(), "e2e", "support", "pr-changes.mjs");
const WORKFLOW = path.join(repoRoot(), ".github", "workflows", "e2e-pr.yaml");

type Job = { if?: string; needs?: string | string[]; outputs?: Record<string, string>; steps?: { id?: string; run?: string }[] };

test.describe("pr-changes", () => {
  test("every path matches a tracked file", () => {
    const tracked = execFileSync("git", ["ls-files"], { cwd: repoRoot(), encoding: "utf-8" }).split("\n");
    const unmatched = Object.values(JOB_PATHS)
      .flat()
      .filter((glob) => !tracked.some((file) => globRegExp(glob).test(file)));
    expect(unmatched).toEqual([]);
  });

  test("a change under each path runs the vscode job", () => {
    const changed = [
      "vscode-extension/src/web/extension.ts",
      "ui/src/api/api.ts",
      "schemas/src/main/resources/conf-model/element/script.schema.yaml",
      "package.json",
      "package-lock.json",
      "e2e/specs/vscode/smoke.spec.ts",
      "e2e/specs/vscode-server/vscode-server.setup.ts",
      "e2e/specs/schemas-build/schemas-build.setup.ts",
      "e2e/specs/harness/vscode-guard.spec.ts",
      "e2e/specs/harness/schemas-build.spec.ts",
      "e2e/pages/VsCodePage.ts",
      "e2e/support/vscode-server.ts",
      "e2e/support/schemas-build.ts",
      "e2e/registry/vscode-messages.ts",
      "e2e/fixtures/vscode/workspace/chains/a/a.chain.cip.yaml",
      "e2e/playwright.config.ts",
      ".github/workflows/e2e-pr.yaml",
    ];
    expect(changed.filter((file) => !jobsFor([file]).vscode)).toEqual([]);
  });

  test("a change elsewhere skips every gated job", () => {
    const changed = [
      "docs/README.md",
      "help/docs/index.md",
      "engine/pom.xml",
      "vscode-extension-notes.md",
      "ui-old/index.ts",
      "packages/app/settings.json",
      "e2e/package.json",
      "e2e/specs/ui/chains.spec.ts",
      "e2e/specs/harness/target.spec.ts",
      "e2e/specs/harness/nested/vscode-guard.spec.ts",
      "e2e/support/ui-server.ts",
      "e2e/support/nested/vscode-server.ts",
      "e2e/pages/ChainGraphPage.ts",
      "e2e/registry/elements.ts",
      ".github/workflows/e2e-k8s.yaml",
    ];
    expect(jobsFor(changed)).toEqual({ vscode: false });
    expect(jobsFor([])).toEqual({ vscode: false });
  });

  test("the script prints one output per job", () => {
    const run = (input: string) => spawnSync(process.execPath, [SCRIPT], { input, encoding: "utf-8" });
    expect(run("docs/README.md\nui/src/index.ts\n")).toMatchObject({ status: 0, stdout: "vscode=true\n" });
    expect(run("docs/README.md\n")).toMatchObject({ status: 0, stdout: "vscode=false\n" });
  });

  test("the workflow gates each job on its output", () => {
    const jobs = (yaml.load(fs.readFileSync(WORKFLOW, "utf-8")) as { jobs: Record<string, Job> }).jobs;
    const changes = jobs["changes"];
    const step = changes.steps?.find((each) => each.run?.includes("support/pr-changes.mjs"));
    expect(step?.id, "the changes job runs the script in a step with an id").toBeTruthy();
    const outputs = Object.fromEntries(Object.keys(JOB_PATHS).map((job) => [job, `\${{ steps.${step?.id}.outputs.${job} }}`]));
    expect(changes.outputs).toEqual(outputs);
    for (const job of Object.keys(JOB_PATHS)) {
      expect(jobs[job]?.needs, job).toContain("changes");
      expect(jobs[job]?.if, job).toBe(`needs.changes.outputs.${job} == 'true'`);
    }
    expect(jobs["stack-free"]?.needs, "stack-free runs on every pull request").toBeUndefined();
  });
});
