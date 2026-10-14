/**
 * Target selection, pinned without a stack: which target a run gets, which projects it declares,
 * and which ports it addresses.
 *
 * `CIP_TARGET` is read at config load, so a value that is misread fails every project at once or,
 * worse, runs the Compose projects against a cluster. The project list is compared with the list
 * the config declared before the target seam existed, so moving a project behind a target cannot
 * drop it from a Compose run.
 */
import { test, expect } from "@playwright/test";
import { suiteProjects } from "../../playwright.config.js";
import { proxyUrl, serviceUrl } from "../../env/containers.js";
import { stateFileName, target } from "../../env/target.js";
import { targetSetup, type SuiteProject } from "../../env/target-setup.js";
import { URL_OVERRIDES, withEnv } from "../../support/with-env.js";
import { BROKER_PROJECTS } from "./projects.js";

/** The projects the config declared on 2026-09-24, before the target seam, with brokers on. */
const COMPOSE_PROJECTS = [
  "schema",
  "api",
  "seed",
  "seed-teardown",
  "tooling",
  "runtime",
  "env",
  "ui-server",
  "ui-server-teardown",
  "ui",
  "brokers-seed",
  "brokers-seed-teardown",
  "brokers",
  "brokers-restart",
  "global",
];

function composeProjects(brokers: string | undefined): SuiteProject[] {
  return withEnv({ E2E_BROKERS: brokers }, () => suiteProjects(targetSetup("compose")));
}

test("the target defaults to compose", { tag: ["@infra", "@tier1"] }, () => {
  expect(withEnv({ CIP_TARGET: undefined }, target)).toBe("compose");
  expect(withEnv({ CIP_TARGET: "compose" }, target)).toBe("compose");
  expect(withEnv({ CIP_TARGET: "k8s" }, target)).toBe("k8s");
});

test("an unknown target fails, naming both valid values", { tag: ["@infra", "@tier1"] }, () => {
  // Read as `compose`, a typo such as `kube` would run the Compose projects and report them as a
  // cluster run.
  for (const value of ["kube", "K8S", ""]) {
    expect(() => withEnv({ CIP_TARGET: value }, target)).toThrow(/"compose" or "k8s"/);
  }
});

/**
 * How the Compose run schedules each project: what it waits for, its teardown, which files it
 * collects, and how many workers it gets. A change to any of them changes the run, so it changes
 * this table in the same commit.
 */
const COMPOSE_SCHEDULE: Record<string, string> = {
  schema: "./specs/schema; parallel",
  api: "./specs/api; after seed; parallel",
  seed: "./specs/seed /.*\\.setup\\.ts$/; teardown seed-teardown",
  "seed-teardown": "./specs/seed /.*\\.teardown\\.ts$/",
  tooling: "./specs/tooling; after seed",
  runtime: "./specs/runtime; after seed, tooling; parallel",
  env: "./specs/env; after api, runtime, ui; 1 worker",
  "ui-server": "./specs/ui-server /.*\\.setup\\.ts$/; teardown ui-server-teardown",
  "ui-server-teardown": "./specs/ui-server /.*\\.teardown\\.ts$/",
  ui: "./specs/ui; after seed, ui-server; parallel",
  // `decorate` carries the one Compose edge on a common project: the broker teardown waits for
  // `global` without gating either one.
  global: "./specs/global; after api, runtime, env, ui; teardown brokers-seed-teardown; 1 worker",
  "brokers-seed": "./specs/brokers-seed /.*\\.setup\\.ts$/; teardown brokers-seed-teardown",
  "brokers-seed-teardown": "./specs/brokers-seed /.*\\.teardown\\.ts$/",
  brokers: "./specs/brokers except /broker-restart\\.spec\\.ts$/; after brokers-seed; parallel",
  "brokers-restart": "./specs/brokers /broker-restart\\.spec\\.ts$/; after brokers; 1 worker",
};

function schedule(project: SuiteProject): string {
  const collects = [
    project.testDir,
    ...(project.testMatch ? [String(project.testMatch)] : []),
    ...(project.testIgnore ? [`except ${String(project.testIgnore)}`] : []),
  ].join(" ");
  return [
    collects,
    ...(project.dependencies?.length ? [`after ${project.dependencies.join(", ")}`] : []),
    ...(project.teardown ? [`teardown ${project.teardown}`] : []),
    ...(project.workers ? [`${project.workers} worker${project.workers === 1 ? "" : "s"}`] : []),
    ...(project.fullyParallel ? ["parallel"] : []),
  ].join("; ");
}

test("the Compose project list is the list the config declared before the seam", { tag: ["@infra", "@tier1"] }, () => {
  const projects = composeProjects(undefined);
  expect(projects.map((each) => each.name).sort()).toEqual([...COMPOSE_PROJECTS].sort());
  expect(Object.fromEntries(projects.map((each) => [each.name, schedule(each)]))).toEqual(COMPOSE_SCHEDULE);
});

test("E2E_BROKERS=0 drops the broker projects and names them as absent", { tag: ["@infra", "@tier1"] }, () => {
  const projects = composeProjects("0");
  expect(projects.map((each) => each.name).sort()).toEqual(
    COMPOSE_PROJECTS.filter((name) => !BROKER_PROJECTS.includes(name)).sort(),
  );
  // Naming a `teardown` project that is not declared fails the config outright.
  expect(projects.find((each) => each.name === "global")?.teardown).toBeUndefined();
  const absent = withEnv({ E2E_BROKERS: "0" }, () => targetSetup("compose").absent);
  expect(absent.map((each) => each.name)).toEqual(BROKER_PROJECTS);
});

test("the default ports follow the target", { tag: ["@infra", "@tier1"] }, () => {
  const unset = Object.fromEntries(URL_OVERRIDES.map((name) => [name, undefined]));
  const urls = (value: string) =>
    withEnv({ ...unset, CIP_TARGET: value }, () => ({
      catalog: serviceUrl("runtime-catalog"),
      engine: serviceUrl("engine"),
      sessions: serviceUrl("sessions-management"),
      testing: serviceUrl("testing-service"),
      proxy: proxyUrl(),
    }));
  expect(urls("compose")).toEqual({
    catalog: "http://localhost:8091",
    engine: "http://localhost:8092",
    sessions: "http://localhost:8093",
    testing: "http://localhost:8095",
    proxy: "http://localhost:8080",
  });
  // Fixed NodePorts, so a kind or k3d cluster can map them when it is created.
  expect(urls("k8s")).toEqual({
    catalog: "http://localhost:30091",
    engine: "http://localhost:30092",
    sessions: "http://localhost:30093",
    testing: "http://localhost:30095",
    proxy: "http://localhost:30080",
  });
});

test("the Kubernetes state files carry a prefix of their own", { tag: ["@infra", "@tier1"] }, () => {
  expect(withEnv({ CIP_TARGET: "compose" }, () => stateFileName(".e2e-corpus.json"))).toBe(
    ".e2e-corpus.json",
  );
  expect(withEnv({ CIP_TARGET: "k8s" }, () => stateFileName(".e2e-corpus.json"))).toBe(
    ".e2e-k8s-corpus.json",
  );
});

test("a project can ask for the micro engine, which Compose refuses", { tag: ["@infra", "@tier1"] }, () => {
  // Typed so that `tsc` checks the option on a project, as `runtime-micro` sets it.
  const micro: SuiteProject = { name: "runtime-micro", use: { engineKind: "micro" } };
  expect(targetSetup("k8s").projects.find((each) => each.name === micro.name)?.use).toEqual(micro.use);
  const compose = targetSetup("compose");
  expect(compose.createEnv("classic").name).toBe("compose");
  expect(() => compose.createEnv("micro")).toThrow(/classic engine only/);
});
