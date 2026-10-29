/**
 * The path lists of `support/pr-changes.mjs`, which decide the gated jobs of the PR workflow and the
 * images its kind jobs build, and the `changes` job that reads them.
 *
 * Shown failing: `*` translated to `.*` ("a change elsewhere skips the vscode job"); the
 * `e2e/pages/VsCodePage.ts` entry renamed to `e2e/pages/VSCodePage.ts` ("every path matches a
 * tracked file" and "a change under each path runs the vscode job"); the job output renamed to
 * `vs-code`, and the vscode job's `if` replaced by `always()` ("the workflow gates each job on its
 * output"); the printed value negated ("the script prints one output per job and the build list");
 * the `e2e/env/host.ts` entry removed ("a change to any file the vscode project loads runs its job");
 * `--no-renames` dropped from the workflow ("the workflow gates each job on its output");
 * `e2e/pages/**` mapped to `classic` only ("a change to any file a kind job loads runs that job");
 * `integration-build-pipeline/**` dropped from the catalog build ("sample diffs decide the kind jobs
 * and the build list" and "each image is built when a file it is built from changes"); the
 * `help/docs/**` rule removed ("a change to a UI bundle input runs the ui job" and the samples);
 * `e2e/specs/seed-micro/**` mapped to `micro` only (the kind job closures and the samples);
 * `E2E_REBUILD` ignored by the script ("the script prints one output per job and the build list");
 * the step env `E2E_REBUILD` dropped, and an ungated `classic` job added ("the workflow gates each
 * job on its output").
 */
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { suiteProjects } from "../../playwright.config.js";
import { suiteImages } from "../../env/provision/k8s.js";
import { targetSetup, type SuiteProject } from "../../env/target-setup.js";
import { laneFlags } from "../../support/lanes.mjs";
import { BUNDLE_INPUTS } from "../../support/ui-server.js";
import { withEnv } from "../../support/with-env.js";
import { BUILD_PATHS, JOB_PATHS, KIND_JOBS, KIND_RULES, buildFor, globRegExp, jobsFor } from "../../support/pr-changes.mjs";

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

/** The tracked files under `paths`, relative to `cwd`. */
const tracked = (cwd: string, ...paths: string[]) =>
  execFileSync("git", ["ls-files", "--", ...paths], { cwd, encoding: "utf-8" }).split("\n").filter(Boolean);

/** Playwright's default `testMatch`. */
const DEFAULT_TEST_MATCH = /\.(spec|test)\.[cm]?[jt]sx?$/;

/** The files of each project `lane` runs: its selection, their dependencies, and their teardowns. */
function laneFiles(lane: string): string[] {
  const all = withEnv({ E2E_BROKERS: undefined }, () => suiteProjects(targetSetup("k8s")));
  const byName = new Map(all.map((each) => [each.name, each]));
  const runs = new Set<SuiteProject>();
  const visit = (name: string) => {
    const project = byName.get(name);
    if (!project || runs.has(project)) return;
    runs.add(project);
    for (const next of [...(project.dependencies ?? []), ...(project.teardown ? [project.teardown] : [])]) visit(next);
  };
  for (const flag of laneFlags(lane)) visit(flag.replace("--project=", ""));
  const regExps = (value: unknown) => [value ?? []].flat().map((each) => {
    if (!(each instanceof RegExp)) throw new Error(`a testMatch or testIgnore of ${String(each)} is not a RegExp`);
    return each;
  });
  return [...runs].flatMap((project) => {
    const match = project.testMatch ? regExps(project.testMatch) : [DEFAULT_TEST_MATCH];
    const ignore = regExps(project.testIgnore);
    return tracked(E2E, project.testDir ?? "specs")
      .map((file) => path.join(E2E, file))
      .filter((file) => match.some((each) => each.test(file)) && !ignore.some((each) => each.test(file)));
  });
}

/** What every kind job loads besides its projects: the config, the global setup and teardown, and the scripts `_e2e-kind.yaml` runs. */
const KIND_ENTRY_POINTS = [
  "playwright.config.ts",
  "env/provision.ts",
  "support/fixtures.ts",
  "support/lanes.mjs",
  "registry/reconcile.mjs",
  "support/run-summary.mjs",
  "support/run-page.mjs",
  "support/run-email.mjs",
].map((file) => path.join(E2E, file));

type Step = { id?: string; run?: string; env?: Record<string, string> };
type Job = { if?: string; needs?: string | string[]; outputs?: Record<string, string>; permissions?: Record<string, string>; steps?: Step[] };

test.describe("pr-changes", () => {
  test("every path matches a tracked file", () => {
    const files = tracked(repoRoot());
    const globs = [...Object.values(JOB_PATHS), ...KIND_RULES.map((rule) => rule.paths), ...Object.values(BUILD_PATHS)];
    const unmatched = globs.flat().filter((glob) => !files.some((file) => globRegExp(glob).test(file)));
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

  test("a change elsewhere skips the vscode job", () => {
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
    expect(changed.filter((file) => jobsFor([file]).vscode)).toEqual([]);
    expect(jobsFor([])).toEqual({ vscode: false, classic: false, micro: false, ui: false });
  });

  test("a change to any file a kind job loads runs that job", () => {
    for (const job of KIND_JOBS) {
      const loaded = importClosure([...laneFiles(job), ...KIND_ENTRY_POINTS]);
      expect(loaded, job).toContain("e2e/env/provision.ts");
      expect(loaded.filter((file) => !jobsFor([file])[job]), job).toEqual([]);
    }
    // The closures reach the files the narrower rules map, so the check above is not vacuous.
    const ui = importClosure(laneFiles("ui"));
    expect(ui).toEqual(expect.arrayContaining(["e2e/specs/ui/doc.spec.ts", "e2e/pages/ChainsPage.ts"]));
    expect(importClosure(laneFiles("classic"))).toContain("e2e/specs/seed-micro/seed-micro.teardown.ts");
    expect(importClosure(laneFiles("micro"))).toEqual(expect.arrayContaining(["e2e/specs/seed-micro/seed-micro.setup.ts", "e2e/specs/k8s/discovery.spec.ts"]));
  });

  test("a change to a UI bundle input runs the ui job", () => {
    const inputs = tracked(path.join(repoRoot(), "ui"), ...BUNDLE_INPUTS);
    expect(inputs).toEqual(expect.arrayContaining(["../help/docs/00__Overview/0__Getting_Started/readme.md", "../package-lock.json"]));
    // The bundle serves the help pages; `ui/src/components/AGENTS.md` only changes its hash.
    const files = inputs
      .map((file) => path.relative(repoRoot(), path.join(repoRoot(), "ui", file)))
      .filter((file) => file.startsWith("help/docs/") || !file.endsWith(".md"));
    expect(files.filter((file) => !jobsFor([file]).ui)).toEqual([]);
  });

  test("each image is built when a file it is built from changes", () => {
    // The micro job adds micro-engine to every build list itself.
    for (const image of suiteImages().filter((each) => each.name !== "micro-engine")) {
      const sources = tracked(repoRoot(), ...image.sources);
      expect(sources.length, image.name).toBeGreaterThan(0);
      expect(sources.filter((file) => !buildFor([file], false).split(",").includes(image.name)), image.name).toEqual([]);
    }
  });

  test("sample diffs decide the kind jobs and the build list", () => {
    const decide = (files: string[], rebuild = false) => {
      const { classic, micro, ui } = jobsFor(files);
      return { classic, micro, ui, build: buildFor(files, rebuild) };
    };
    const none = { classic: false, micro: false, ui: false, build: "none" };
    const all = { classic: true, micro: true, ui: true };
    expect(decide(["ui/src/pages/Chains.tsx", "package-lock.json"]), "ui only").toEqual({ ...none, ui: true });
    expect(decide(["micro-engine/src/main/java/App.java"]), "micro-engine only").toEqual({ ...none, micro: true });
    expect(decide(["vscode-extension/src/web/test/golden/a.chain.cip.yaml"]), "golden file").toEqual({ ...none, classic: true });
    expect(decide(["vscode-extension/src/web/test/workspace/chains/a/resources/a.groovy"]), "extension workspace").toEqual({ ...none, classic: true });
    expect(decide(["vscode-extension/src/web/extension.ts"]), "extension").toEqual(none);
    expect(decide(["docs/plans/x.md", "help/README.md", ".apm/instructions/e2e.instructions.md"]), "docs only").toEqual(none);
    expect(decide(["README.md"]), "root README.md").toEqual(none);
    expect(decide(["help/docs/00__Overview/0__Getting_Started/readme.md"]), "help page").toEqual({ ...none, ui: true });
    expect(decide([".github/workflows/ui-build.yaml"]), "other workflow").toEqual(none);
    expect(decide([".github/workflows/_e2e-kind.yaml"]), "kind workflow").toEqual({ ...all, build: "none" });
    expect(decide(["e2e/specs/k8s/discovery.spec.ts"]), "k8s spec").toEqual({ ...none, micro: true });
    expect(decide(["e2e/specs/seed-micro/seed-micro.teardown.ts"]), "seed-micro").toEqual({ ...none, classic: true, micro: true });
    expect(decide(["e2e/specs/vscode/smoke.spec.ts"]), "vscode spec").toEqual(none);
    expect(decide(["e2e/support/catalog.ts"]), "e2e only").toEqual({ ...all, build: "none" });
    expect(decide(["parent/pom.xml"]), "parent").toEqual({ ...all, build: "engine,runtime-catalog,sessions-management" });
    expect(decide(["pom.xml", "testing-service/main.go"]), "root pom").toEqual({ ...all, build: "engine,runtime-catalog,sessions-management,testing-service" });
    expect(decide(["integration-build-pipeline/pom.xml"]), "build pipeline").toEqual({ ...all, build: "runtime-catalog" });
    expect(decide(["sessions-management/src/a.java"]), "sessions").toEqual({ ...all, build: "sessions-management" });
    expect(decide(["e2e/support/catalog.ts"], true), "E2E_REBUILD").toEqual({ ...all, build: "all" });
    expect(decide([]), "no change").toEqual(none);
  });

  test("the script prints one output per job and the build list", () => {
    const run = (input: string, rebuild?: string) =>
      spawnSync(process.execPath, [SCRIPT], { input, encoding: "utf-8", env: { ...process.env, E2E_REBUILD: rebuild } });
    expect(run("docs/README.md\nui/src/index.ts\n")).toMatchObject({
      status: 0,
      stdout: "vscode=true\nclassic=false\nmicro=false\nui=true\nbuild=none\n",
    });
    expect(run("docs/README.md\n", "false")).toMatchObject({
      status: 0,
      stdout: "vscode=false\nclassic=false\nmicro=false\nui=false\nbuild=none\n",
    });
    expect(run("engine/pom.xml\n", "true")).toMatchObject({
      status: 0,
      stdout: "vscode=false\nclassic=true\nmicro=true\nui=true\nbuild=all\n",
    });
  });

  test("the workflow gates each job on its output", () => {
    const jobs = (yaml.load(fs.readFileSync(WORKFLOW, "utf-8")) as { jobs: Record<string, Job> }).jobs;
    const changes = jobs["changes"];
    const step = changes.steps?.find((each) => each.run?.includes("support/pr-changes.mjs"));
    expect(step?.id, "the changes job runs the script in a step with an id").toBeTruthy();
    // With rename detection a file moved out of a watched tree lists only its new path.
    expect(step?.run).toMatch(/git diff --name-only --no-renames HEAD\^1 HEAD \|/);
    const names = [...Object.keys(JOB_PATHS), ...KIND_JOBS, "build"];
    const outputs = Object.fromEntries(names.map((name) => [name, `\${{ steps.${step?.id}.outputs.${name} }}`]));
    expect(changes.outputs).toEqual(outputs);
    // The label is read at run time, which a rerun after labeling picks up and the event payload does not.
    const labels = changes.steps?.find((each) => each.run?.includes("gh pr view"));
    expect(labels?.run).toMatch(/gh pr view "\$PR" -R "\$GITHUB_REPOSITORY" --json labels .*"e2e-rebuild"/);
    expect(labels?.env).toMatchObject({ GH_TOKEN: "${{ github.token }}", PR: "${{ github.event.pull_request.number }}" });
    expect(step?.env?.E2E_REBUILD).toBe(`\${{ steps.${labels?.id}.outputs.rebuild }}`);
    expect(changes.permissions).toEqual({ contents: "read", "pull-requests": "read" });
    // The kind jobs enter the workflow one at a time, so a kind job not added yet is reported, not failed.
    const kindJobs = KIND_JOBS.filter((job) => jobs[job]);
    for (const job of [...Object.keys(JOB_PATHS), ...kindJobs]) {
      expect(jobs[job]?.needs, job).toContain("changes");
      expect(jobs[job]?.if, job).toBe(`needs.changes.outputs.${job} == 'true'`);
    }
    const missing = KIND_JOBS.filter((job) => !jobs[job]);
    if (missing.length) test.info().annotations.push({ type: "kind jobs not in the workflow", description: missing.join(", ") });
    expect(jobs["stack-free"]?.needs, "stack-free runs on every pull request").toBeUndefined();
  });
});
