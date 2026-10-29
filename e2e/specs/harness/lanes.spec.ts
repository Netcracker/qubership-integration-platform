/**
 * The lane map of `support/lanes.mjs`, which decides the projects each kind job of the PR workflow
 * selects, against the config that declares the projects and the workflow that calls the lanes.
 *
 * Shown failing: the printed flags joined by commas ("the script prints a lane's flags and refuses
 * an unknown one"); `ui` declared with the lanes of `api` ("the config declares the lanes the map
 * holds"); a lane on `global` in the map and the config ("no lane reaches a project that only the
 * nightly runs"); a `classic` job calling lane `ui`, a job calling lane `nightly`, and a `classic`
 * job with no lane ("the workflow calls each lane by the job of its name").
 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { suiteProjects } from "../../playwright.config.js";
import { repoRoot } from "../../env/host.js";
import { targetSetup, type SuiteProject } from "../../env/target-setup.js";
import { withEnv } from "../../support/with-env.js";
import { PROJECT_LANES, laneFlags, lanes } from "../../support/lanes.mjs";

const SCRIPT = path.join(repoRoot(), "e2e", "support", "lanes.mjs");
const WORKFLOW = path.join(repoRoot(), ".github", "workflows", "e2e-pr.yaml");

/** Projects that restart a service or read state every worker shares, which a pull request job must not run. */
const NIGHTLY_ONLY = ["env", "env-micro", "global", "global-micro", "brokers-restart"];

/** Every project the Kubernetes target declares with the brokers on, the widest list any lane selects from. */
const projects = () => withEnv({ E2E_BROKERS: undefined }, () => suiteProjects(targetSetup("k8s")));

/** The projects `selection` runs: the selection and everything its `dependencies` reach. */
function reached(all: readonly SuiteProject[], selection: string[]): Set<string> {
  const byName = new Map(all.map((each) => [each.name, each]));
  const seen = new Set<string>();
  const visit = (name: string) => {
    if (seen.has(name)) return;
    seen.add(name);
    for (const dependency of byName.get(name)?.dependencies ?? []) visit(dependency);
  };
  for (const name of selection) visit(name);
  return seen;
}

type Job = { uses?: string; with?: Record<string, unknown> };

test.describe("lanes", () => {
  test("the script prints a lane's flags and refuses an unknown one", () => {
    const run = (...args: string[]) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: "utf-8" });
    expect(run("classic")).toMatchObject({ status: 0, stdout: `${laneFlags("classic").join(" ")}\n` });
    expect(run("ui")).toMatchObject({ status: 0, stdout: "--project=ui\n" });
    expect(run("")).toMatchObject({ status: 0, stdout: "" });
    expect(run()).toMatchObject({ status: 0, stdout: "" });
    const unknown = run("nightly");
    expect(unknown).toMatchObject({ status: 1, stdout: "" });
    expect(unknown.stderr).toContain(`Unknown lane "nightly". The lanes are classic, micro, ui.`);
    expect(laneFlags("micro")).toEqual([
      "--project=k8s",
      "--project=runtime-micro",
      "--project=runtime-micro-redeploy",
      "--project=brokers-micro",
    ]);
  });

  test("the config declares the lanes the map holds", () => {
    const declared = projects();
    const names = declared.map((each) => each.name);
    expect(Object.keys(PROJECT_LANES).filter((name) => !names.includes(name))).toEqual([]);
    const fromConfig = Object.fromEntries(declared.map((each) => [each.name, each.metadata?.lanes]));
    const fromMap = Object.fromEntries(names.map((name) => [name, PROJECT_LANES[name]]));
    expect(fromConfig).toEqual(fromMap);
  });

  test("no lane reaches a project that only the nightly runs", () => {
    const declared = projects();
    for (const lane of lanes()) {
      const selection = laneFlags(lane).map((flag) => flag.replace("--project=", ""));
      const runs = reached(declared, selection);
      expect(NIGHTLY_ONLY.filter((name) => runs.has(name)), lane).toEqual([]);
    }
  });

  test("the workflow calls each lane by the job of its name", () => {
    const jobs = (yaml.load(fs.readFileSync(WORKFLOW, "utf-8")) as { jobs: Record<string, Job> }).jobs;
    const called = Object.entries(jobs).flatMap(([name, job]) =>
      job.with && "lane" in job.with ? [{ job: name, lane: job.with.lane }] : [],
    );
    expect(called.filter(({ lane }) => !lanes().includes(String(lane)))).toEqual([]);
    expect(called.filter(({ job, lane }) => job !== lane)).toEqual([]);
    const withJob = lanes().filter((lane) => jobs[lane]);
    for (const lane of withJob) expect(jobs[lane].with?.lane, lane).toBe(lane);
    // The jobs enter the workflow one at a time, so a lane with no job yet is reported, not failed.
    const withoutJob = lanes().filter((lane) => !jobs[lane]);
    if (withoutJob.length) {
      test.info().annotations.push({ type: "lanes without a job", description: withoutJob.join(", ") });
    }
  });
});
