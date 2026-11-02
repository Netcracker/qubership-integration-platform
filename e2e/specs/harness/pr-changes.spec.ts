/**
 * The path lists of `support/pr-changes.mjs`, which decide the gated jobs of the PR workflow, and
 * the `changes` job that reads them.
 *
 * Shown failing: `*` translated to `.*` ("a change elsewhere skips every gated job"); the
 * `e2e/pages/VsCodePage.ts` entry renamed to `e2e/pages/VSCodePage.ts` ("every path matches a
 * tracked file" and "a change under each path runs the vscode job"); the job output renamed to
 * `vs-code`, and the vscode job's `if` replaced by `always()` ("the workflow gates each job on its
 * output"); the printed value negated ("the script prints one output per job"); the
 * `e2e/env/host.ts` entry removed ("a change to any file the vscode project loads runs its job");
 * `--no-renames` dropped from the workflow ("the workflow gates each job on its output").
 */
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { JOB_PATHS, globRegExp, jobsFor } from "../../support/pr-changes.mjs";

const E2E = path.join(repoRoot(), "e2e");
const SCRIPT = path.join(E2E, "support", "pr-changes.mjs");
const WORKFLOW = path.join(repoRoot(), ".github", "workflows", "e2e-pr.yaml");

/** A runtime import or re-export, and a bare `import "…"`. A type-only import is erased, and `check-types` reads it. */
const IMPORT = /^\s*(?:import|export)\s+(?!type\s)(?:[^"';]*?\sfrom\s+)?["'](\.[^"']+)["']/gm;

/**
 * Every file `seeds` load, relative to the repository root. The config's own imports load in every
 * project, so the stack-free job's harness run reads them.
 */
function importClosure(seeds: string[]): string[] {
  const seen = new Set<string>();
  const visit = (file: string) => {
    if (seen.has(file)) return;
    seen.add(file);
    for (const [, specifier] of fs.readFileSync(file, "utf-8").matchAll(IMPORT)) {
      const target = path.resolve(path.dirname(file), specifier);
      const found = [target, target.replace(/\.js$/, ".ts")].find((each) => fs.existsSync(each));
      if (!found) throw new Error(`${path.relative(E2E, file)} imports ${specifier}, which resolves to no file`);
      visit(found);
    }
  };
  for (const seed of seeds) visit(seed);
  return [...seen].map((file) => path.relative(repoRoot(), file)).sort();
}

type Job = { if?: string; needs?: string | string[]; outputs?: Record<string, string>; steps?: { id?: string; run?: string }[] };

test.describe("pr-changes", () => {
  test("every path matches a tracked file", () => {
    const tracked = execFileSync("git", ["ls-files"], { cwd: repoRoot(), encoding: "utf-8" }).split("\n");
    const unmatched = Object.values(JOB_PATHS)
      .flat()
      .filter((glob) => !tracked.some((file) => globRegExp(glob).test(file)));
    expect(unmatched).toEqual([]);
  });

  test("a change to any file the vscode project loads runs its job", () => {
    const seeds = execFileSync("git", ["ls-files", "specs/vscode*", "specs/schemas-build"], { cwd: E2E, encoding: "utf-8" })
      .split("\n")
      .filter((file) => /\.(ts|mjs)$/.test(file))
      .map((file) => path.join(E2E, file));
    const loaded = importClosure([...seeds, path.join(E2E, "registry", "reconcile.mjs")]);
    // The closure reaches past the trees the project's own globs name.
    expect(loaded).toEqual(expect.arrayContaining(["e2e/pages/ChainGraphPage.ts", "e2e/registry/elements.ts", "e2e/env/host.ts"]));
    const packageFiles = ["e2e/package.json", "e2e/package-lock.json", "e2e/tsconfig.json"];
    expect([...loaded, ...packageFiles].filter((file) => !jobsFor([file]).vscode)).toEqual([]);
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
      "e2e/specs/ui/chains.spec.ts",
      "e2e/specs/harness/target.spec.ts",
      "e2e/specs/harness/nested/vscode-guard.spec.ts",
      "e2e/support/ui-server.ts",
      "e2e/support/nested/vscode-server.ts",
      "e2e/pages/ChainsPage.ts",
      "e2e/registry/operations.cache.json",
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
    // With rename detection a file moved out of a watched tree lists only its new path.
    expect(step?.run).toMatch(/git diff --name-only --no-renames HEAD\^1 HEAD \|/);
    const outputs = Object.fromEntries(Object.keys(JOB_PATHS).map((job) => [job, `\${{ steps.${step?.id}.outputs.${job} }}`]));
    expect(changes.outputs).toEqual(outputs);
    for (const job of Object.keys(JOB_PATHS)) {
      expect(jobs[job]?.needs, job).toContain("changes");
      expect(jobs[job]?.if, job).toBe(`needs.changes.outputs.${job} == 'true'`);
    }
    expect(jobs["stack-free"]?.needs, "stack-free runs on every pull request").toBeUndefined();
  });
});
