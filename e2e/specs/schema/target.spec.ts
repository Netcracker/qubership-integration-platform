/**
 * Target selection, pinned without a stack: which target a run gets, which projects it declares,
 * and which ports it addresses.
 *
 * `CIP_TARGET` is read at config load, so a value that is misread fails every project at once or,
 * worse, runs the Compose projects against a cluster. The project list is compared with the list
 * the config declared before the target seam existed, so moving a project behind a target cannot
 * drop it from a Compose run.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { suiteProjects } from "../../playwright.config.js";
import {
  kafkaHostBrokers,
  proxyUrl,
  pubsubHostUrl,
  rabbitmqHostUrl,
  serviceUrl,
  sftpHostAddress,
} from "../../env/containers.js";
import { brokersEnabled, stateFileName, target } from "../../env/target.js";
import { targetSetup, type SuiteProject } from "../../env/target-setup.js";
import { URL_OVERRIDES, withEnv } from "../../support/with-env.js";
import { BROKERS_MICRO_CORPUS_STATE_FILE, MICRO_CORPUS_STATE_FILE, MICRO_REDEPLOY_STATE_FILE } from "../../env/k8s.js";
import { BROKER_PROJECTS } from "./projects.js";

const E2E_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * The projects the config declared on 2026-09-24, before the target seam, with brokers on, and with
 * `suite-teardown` in place of `brokers-seed-teardown`.
 */
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
  "brokers",
  "brokers-restart",
  "global",
  "suite-teardown",
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
  // The broker and micro teardowns wait for `global` without gating either side.
  global: "./specs/global; after api, runtime, env, ui; teardown suite-teardown; 1 worker",
  "suite-teardown":
    "./specs /\\/(seed-micro|brokers-seed|brokers-micro-seed)\\/[^/]*\\.teardown\\.ts$/",
  // Both seeds deploy to the classic engine, which reports a batch's state once the whole batch loads.
  "brokers-seed": "./specs/brokers-seed /.*\\.setup\\.ts$/; after seed; teardown suite-teardown",
  brokers: "./specs/brokers except /broker-restart\\.spec\\.ts$/; after brokers-seed; parallel",
  // After `global`, and so after `env`, whose engine restart drops the consumer a broker restart case waits on.
  "brokers-restart": "./specs/brokers /broker-restart\\.spec\\.ts$/; after brokers, global; 1 worker",
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

test("suite-teardown collects the micro and broker teardowns and no other", { tag: ["@infra", "@tier1"] }, () => {
  const testMatch = composeProjects(undefined).find((each) => each.name === "suite-teardown")?.testMatch;
  expect(testMatch).toBeInstanceOf(RegExp);
  const collects = (file: string) => (testMatch as RegExp).test(`/repo/e2e/${file}`);
  expect(collects("specs/seed-micro/seed-micro.teardown.ts")).toBe(true);
  expect(collects("specs/brokers-seed/brokers.teardown.ts")).toBe(true);
  expect(collects("specs/brokers-micro-seed/brokers-micro.teardown.ts")).toBe(true);
  // `seed` and `ui-server` keep teardowns of their own, which `global` depends on through them.
  expect(collects("specs/seed/seed.teardown.ts")).toBe(false);
  expect(collects("specs/ui-server/ui-server.teardown.ts")).toBe(false);
  expect(collects("specs/brokers-seed/brokers.setup.ts")).toBe(false);
});

test("E2E_BROKERS=0 drops the broker projects and names them as absent", { tag: ["@infra", "@tier1"] }, () => {
  const projects = composeProjects("0");
  expect(projects.map((each) => each.name).sort()).toEqual(
    COMPOSE_PROJECTS.filter((name) => !BROKER_PROJECTS.includes(name)).sort(),
  );
  // `suite-teardown` stays declared, so `global` still names it.
  expect(projects.find((each) => each.name === "global")?.teardown).toBe("suite-teardown");
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

function brokerAddresses(vars: Record<string, string | undefined>) {
  const unset = Object.fromEntries(URL_OVERRIDES.map((name) => [name, undefined]));
  return withEnv({ ...unset, ...vars }, () => ({
    kafka: kafkaHostBrokers(),
    rabbitmq: rabbitmqHostUrl(),
    pubsub: pubsubHostUrl(),
    sftp: sftpHostAddress(),
  }));
}

test("the broker host addresses follow the target", { tag: ["@infra", "@tier1"] }, () => {
  expect(brokerAddresses({ CIP_TARGET: "compose" })).toEqual({
    kafka: "localhost:9092",
    rabbitmq: "amqp://guest:guest@localhost:5672",
    pubsub: "http://localhost:8085",
    sftp: { host: "localhost", port: 2222 },
  });
  // The node ports `e2e/k8s/bootstrap-cluster.sh` maps on the kind cluster.
  expect(brokerAddresses({ CIP_TARGET: "k8s" })).toEqual({
    kafka: "localhost:30992",
    rabbitmq: "amqp://guest:guest@localhost:30672",
    pubsub: "http://localhost:30085",
    sftp: { host: "localhost", port: 30022 },
  });
});

test("a CIP_* broker variable wins over the table on either target", { tag: ["@infra", "@tier1"] }, () => {
  const overrides = {
    CIP_KAFKA_URL: "broker.example:19092",
    CIP_RABBITMQ_URL: "amqp://user:pass@broker.example:15672",
    CIP_PUBSUB_URL: "http://broker.example:18085",
    CIP_SFTP_HOST: "broker.example",
    CIP_SFTP_PORT: "12222",
  };
  for (const on of ["compose", "k8s"]) {
    expect(brokerAddresses({ ...overrides, CIP_TARGET: on })).toEqual({
      kafka: "broker.example:19092",
      rabbitmq: "amqp://user:pass@broker.example:15672",
      pubsub: "http://broker.example:18085",
      sftp: { host: "broker.example", port: 12222 },
    });
  }
  // Each SFTP variable overrides its own half.
  expect(brokerAddresses({ CIP_TARGET: "k8s", CIP_SFTP_HOST: "broker.example" }).sftp).toEqual({
    host: "broker.example",
    port: 30022,
  });
});

test("E2E_BROKERS turns the brokers off only when it is 0", { tag: ["@infra", "@tier1"] }, () => {
  expect(withEnv({ E2E_BROKERS: undefined }, brokersEnabled)).toBe(true);
  expect(withEnv({ E2E_BROKERS: "1" }, brokersEnabled)).toBe(true);
  expect(withEnv({ E2E_BROKERS: "0" }, brokersEnabled)).toBe(false);
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

test("on Compose the engine and its meters answer on the engine's own port", { tag: ["@infra", "@tier1"] }, () => {
  withEnv({ CIP_TARGET: "compose", CIP_ENGINE_URL: undefined }, () => {
    const env = targetSetup("compose").createEnv("classic");
    expect(env.engineUrl()).toBe("http://localhost:8092");
    expect(env.engineMetricsUrl()).toBe("http://localhost:8092/actuator/prometheus");
    expect(env.chainUrl("e2e/echo")).toBe("http://localhost:8092/routes/e2e/echo");
  });
});

test("runtime-micro-redeploy runs one file at a time after seed-micro, on the domain its own state file records", { tag: ["@infra", "@tier1"] }, () => {
  const project = targetSetup("k8s").projects.find((each) => each.name === "runtime-micro-redeploy");
  expect(project && schedule(project)).toBe("./specs/runtime-micro-redeploy; after seed-micro; 1 worker");
  expect(project?.grepInvert).toEqual(/@classic-only/);
  // Never the shared domain's file: a deploy there would roll the pod under every runtime-micro case.
  expect(project?.use).toEqual({ engineKind: "micro", microDomainState: MICRO_REDEPLOY_STATE_FILE });
  expect(MICRO_REDEPLOY_STATE_FILE).not.toBe(MICRO_CORPUS_STATE_FILE);
});

test("global-micro runs the engine files of specs/global on the shared micro domain, one worker after global", { tag: ["@infra", "@tier1"] }, () => {
  const project = targetSetup("k8s").projects.find((each) => each.name === "global-micro");
  expect(project && schedule(project)).toBe(
    "./specs/global /\\/(live-exchanges|runtime-deployments)\\.spec\\.ts$/; after global, seed-micro; 1 worker",
  );
  expect(project?.grepInvert).toEqual(/@classic-only/);
  // The shared domain's state file, the default: global-micro deploys nothing.
  expect(project?.use).toEqual({ engineKind: "micro" });
  // Each file it names exists, so a rename cannot leave the project collecting nothing.
  const files = fs.readdirSync(path.join(E2E_DIR, "specs", "global"));
  expect(files.filter((file) => (project?.testMatch as RegExp).test(`/${file}`)).sort()).toEqual([
    "live-exchanges.spec.ts",
    "runtime-deployments.spec.ts",
  ]);
  // Compose declares no micro engine.
  expect(composeProjects(undefined).map((each) => each.name)).not.toContain("global-micro");
});

test("env-micro restarts the shared micro domain after global-micro and runtime-micro, so after every classic restart", { tag: ["@infra", "@tier1"] }, () => {
  const projects = withEnv({ E2E_BROKERS: "0" }, () => suiteProjects(targetSetup("k8s")));
  const project = projects.find((each) => each.name === "env-micro");
  expect(project && schedule(project)).toBe(
    "./specs/env /restart-resilience\\.spec\\.ts$/; after global-micro, runtime-micro; 1 worker",
  );
  expect(project?.grepInvert).toEqual(/@classic-only/);
  // The shared domain's state file, the default, and the micro copy seed-micro deployed to it.
  expect(project?.use).toEqual({ engineKind: "micro" });
  const files = fs.readdirSync(path.join(E2E_DIR, "specs", "env"));
  expect(files.filter((file) => (project?.testMatch as RegExp).test(`/${file}`))).toEqual(["restart-resilience.spec.ts"]);
  // Through every edge, the classic restarts in `env` and the session wipe in `global` come first.
  const byName = new Map(projects.map((each) => [each.name, each]));
  const after = new Set<string>();
  const visit = (name: string) => {
    for (const dependency of byName.get(name)?.dependencies ?? []) {
      if (after.has(dependency)) continue;
      after.add(dependency);
      visit(dependency);
    }
  };
  visit("env-micro");
  expect([...after]).toEqual(expect.arrayContaining(["env", "global"]));
  expect(composeProjects(undefined).map((each) => each.name)).not.toContain("env-micro");
});

test("brokers-micro-seed deploys to the domain its own state file records, and suite-teardown removes it", { tag: ["@infra", "@tier1"] }, () => {
  const project = withEnv({ E2E_BROKERS: undefined }, () => targetSetup("k8s")).projects.find(
    (each) => each.name === "brokers-micro-seed",
  );
  expect(project && schedule(project)).toBe("./specs/brokers-micro-seed /.*\\.setup\\.ts$/; teardown suite-teardown");
  expect(project?.use).toEqual({ engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE });
  expect([MICRO_CORPUS_STATE_FILE, MICRO_REDEPLOY_STATE_FILE]).not.toContain(BROKERS_MICRO_CORPUS_STATE_FILE);
  // The setup project collects its seed, and `suite-teardown` the teardown file beside it.
  const dir = path.join(E2E_DIR, "specs", "brokers-micro-seed");
  expect(fs.readdirSync(dir).filter((file) => (project?.testMatch as RegExp).test(file))).toEqual(["brokers-micro.setup.ts"]);
  expect(fs.existsSync(path.join(dir, "brokers-micro.teardown.ts"))).toBe(true);
  // Compose declares no micro engine, so it declares no micro broker seed either.
  expect(composeProjects(undefined).map((each) => each.name)).not.toContain("brokers-micro-seed");
});

test("brokers-micro runs the broker specs on the micro copy after both broker seeds, and brokers-restart waits for it on k8s", { tag: ["@infra", "@tier1"] }, () => {
  const projects = withEnv({ E2E_BROKERS: undefined }, () => targetSetup("k8s")).projects;
  const project = projects.find((each) => each.name === "brokers-micro");
  expect(project && schedule(project)).toBe(
    "./specs/brokers except /broker-restart\\.spec\\.ts$/; " +
      "after brokers-seed, brokers-micro-seed; parallel",
  );
  expect(project?.grepInvert).toEqual(/@classic-only/);
  // The env fixture and brokerChain both read the brokers-micro domain, never the shared micro one.
  expect(project?.use).toEqual({ engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE });
  // Each pattern it leaves out matches one spec file of specs/brokers/.
  const files = fs.readdirSync(path.join(E2E_DIR, "specs", "brokers"));
  for (const pattern of project?.testIgnore as RegExp[]) {
    expect(files.filter((file) => pattern.test(file))).toHaveLength(1);
  }
  expect(projects.find((each) => each.name === "brokers-restart")?.dependencies).toEqual(["brokers", "brokers-micro", "global"]);
  // Compose has no micro engine, so brokers-restart there waits for brokers and global alone.
  const compose = composeProjects(undefined);
  expect(compose.map((each) => each.name)).not.toContain("brokers-micro");
  expect(compose.find((each) => each.name === "brokers-restart")?.dependencies).toEqual(["brokers", "global"]);
});

/** Every project `name` waits for, through every edge. */
function waitsFor(projects: readonly SuiteProject[], name: string): Set<string> {
  const byName = new Map(projects.map((each) => [each.name, each]));
  const after = new Set<string>();
  const visit = (from: string) => {
    for (const dependency of byName.get(from)?.dependencies ?? []) {
      if (after.has(dependency)) continue;
      after.add(dependency);
      visit(dependency);
    }
  };
  visit(name);
  return after;
}

test("brokers-restart runs after every service restart, and no restart project waits for a broker case but env-micro", { tag: ["@infra", "@tier1"] }, () => {
  const k8s = withEnv({ E2E_BROKERS: undefined }, () => suiteProjects(targetSetup("k8s")));
  const brokerProjects = ["brokers-seed", "brokers", "brokers-micro-seed", "brokers-micro", "brokers-restart"];
  for (const projects of [k8s, composeProjects(undefined)]) {
    expect([...waitsFor(projects, "brokers-restart")]).toEqual(expect.arrayContaining(["env", "global"]));
    // A red broker case would skip every case of a project that waits for it.
    for (const name of ["env", "global"]) {
      expect([...waitsFor(projects, name)].filter((each) => brokerProjects.includes(each)), name).toEqual([]);
    }
  }
  // env-micro restarts the catalog, which the restart cases' fixtures call.
  expect(k8s.find((each) => each.name === "env-micro")?.dependencies).toEqual(["global-micro", "runtime-micro", "brokers-restart"]);
});
