/**
 * Target selection, pinned without a stack: which target a run gets, which projects it declares,
 * and which ports it addresses.
 *
 * `CIP_TARGET` is read at config load, so a value that is misread fails every project at once or,
 * worse, runs the Compose projects against a cluster. The project list is compared with the list
 * the config declared before the target seam existed, so moving a project behind a target cannot
 * drop it from a Compose run. The edges only the Kubernetes target adds are pinned in
 * `k8s-adapter.spec.ts`.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect, type FullConfig } from "@playwright/test";
import { suiteProjects } from "../../playwright.config.js";
import { needsStack } from "../../env/provision.js";
import {
  kafkaHostBrokers,
  proxyUrl,
  pubsubHostUrl,
  rabbitmqHostUrl,
  serviceUrl,
  sftpHostAddress,
} from "../../env/containers.js";
import { target } from "../../env/target.js";
import { targetSetup, type SuiteProject } from "../../env/target-setup.js";
import { URL_OVERRIDES, withEnv } from "../../support/with-env.js";
import { BROKERS_MICRO_CORPUS_STATE_FILE, MICRO_CORPUS_STATE_FILE, MICRO_REDEPLOY_STATE_FILE } from "../../env/k8s.js";
import { BROKER_PROJECTS, K8S_PROJECTS, MICRO_BROKER_PROJECTS } from "./projects.js";

const E2E_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * The projects the config declared on 2026-09-24, before the target seam, with brokers on, and with
 * `suite-teardown` in place of `brokers-seed-teardown`, `harness` split off `schema`,
 * `schemas-build` added ahead of `ui-server`, `vscode-server` with its teardown added, and `vscode`.
 */
const COMPOSE_PROJECTS = [
  "schema",
  "harness",
  "api",
  "seed",
  "seed-teardown",
  "tooling",
  "runtime",
  "env",
  "schemas-build",
  "ui-server",
  "ui-server-teardown",
  "vscode-server",
  "vscode-server-teardown",
  "vscode",
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

/**
 * How the Compose run schedules each project: what it waits for, its teardown, which files it
 * collects, and how many workers it gets. A change to any of them changes the run, so it changes
 * this table in the same commit.
 */
const COMPOSE_SCHEDULE: Record<string, string> = {
  schema: "./specs/schema; parallel",
  harness: "./specs/harness; parallel",
  api: "./specs/api; after seed; parallel",
  seed: "./specs/seed /.*\\.setup\\.ts$/; teardown seed-teardown",
  "seed-teardown": "./specs/seed /.*\\.teardown\\.ts$/",
  tooling: "./specs/tooling; after seed",
  runtime: "./specs/runtime; after seed, tooling; parallel",
  env: "./specs/env; after api, runtime, ui; 1 worker",
  "schemas-build": "./specs/schemas-build /.*\\.setup\\.ts$/",
  "ui-server": "./specs/ui-server /.*\\.setup\\.ts$/; after schemas-build; teardown ui-server-teardown",
  "ui-server-teardown": "./specs/ui-server /.*\\.teardown\\.ts$/",
  "vscode-server": "./specs/vscode-server /.*\\.setup\\.ts$/; after schemas-build; teardown vscode-server-teardown",
  "vscode-server-teardown": "./specs/vscode-server /.*\\.teardown\\.ts$/",
  vscode: "./specs/vscode; after vscode-server; 1 worker",
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

/** A project's schedule as one line; without `edges`, what it waits for is left out. */
function schedule(project: SuiteProject, edges = true): string {
  const collects = [
    project.testDir,
    ...(project.testMatch ? [String(project.testMatch)] : []),
    ...(project.testIgnore ? [`except ${String(project.testIgnore)}`] : []),
  ].join(" ");
  return [
    collects,
    ...(edges && project.dependencies?.length ? [`after ${project.dependencies.join(", ")}`] : []),
    ...(edges && project.teardown ? [`teardown ${project.teardown}`] : []),
    ...(project.workers ? [`${project.workers} worker${project.workers === 1 ? "" : "s"}`] : []),
    ...(project.fullyParallel ? ["parallel"] : []),
  ].join("; ");
}

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

const k8sProjects = () => withEnv({ E2E_BROKERS: undefined }, () => suiteProjects(targetSetup("k8s")));
const k8sProject = (name: string) => k8sProjects().find((each) => each.name === name);

test.describe("target", () => {
  test("an unknown target fails, naming both valid values", () => {
    // Read as `compose`, a typo such as `kube` would run the Compose projects and report them as a
    // cluster run.
    for (const value of ["kube", "K8S", ""]) {
      expect(() => withEnv({ CIP_TARGET: value }, target)).toThrow(/"compose" or "k8s"/);
    }
  });

  test.describe("projects", () => {
    test("Compose declares the projects it had before the seam", () => {
      const projects = composeProjects(undefined);
      expect(projects.map((each) => each.name).sort()).toEqual([...COMPOSE_PROJECTS].sort());
      expect(Object.fromEntries(projects.map((each) => [each.name, schedule(each)]))).toEqual(COMPOSE_SCHEDULE);
    });

    test("both targets declare the harness project with no stack", () => {
      for (const projects of [composeProjects(undefined), k8sProjects()]) {
        const harness = projects.find((each) => each.name === "harness");
        expect(harness && schedule(harness)).toBe("./specs/harness; parallel");
        expect(harness?.metadata).toEqual({ stack: false, group: "suite" });
      }
    });

    test("both targets build the schemas, stack-free, before ui-server", () => {
      for (const projects of [composeProjects(undefined), k8sProjects()]) {
        const schemas = projects.find((each) => each.name === "schemas-build");
        expect(schemas?.metadata).toEqual({ stack: false, group: "setup" });
        expect([...waitsFor(projects, "ui-server")]).toEqual(["schemas-build"]);
      }
    });

    test("both targets serve VS Code stack-free, after schemas-build", () => {
      for (const projects of [composeProjects(undefined), k8sProjects()]) {
        for (const name of ["vscode-server", "vscode-server-teardown"]) {
          expect(projects.find((each) => each.name === name)?.metadata, name).toEqual({ stack: false, group: "setup" });
        }
        expect([...waitsFor(projects, "vscode-server")]).toEqual(["schemas-build"]);
      }
    });

    test("--project=vscode and what it waits for need no stack", () => {
      for (const projects of [composeProjects(undefined), k8sProjects()]) {
        const config = { argv: ["node", "playwright", "test", "--project=vscode"], projects } as unknown as FullConfig;
        expect(needsStack(config)).toBe(false);
        // Playwright runs the dependencies whatever the selection, and provisioning reads only the selection.
        const vscode = projects.find((each) => each.name === "vscode");
        expect(vscode?.metadata).toEqual({ stack: false });
        expect([...waitsFor(projects, "vscode")].sort()).toEqual(["schemas-build", "vscode-server"]);
        for (const name of waitsFor(projects, "vscode")) {
          expect(projects.find((each) => each.name === name)?.metadata?.stack, name).toBe(false);
        }
      }
    });

    // The run summary and the email count each group apart, so a seed that fails reads as setup.
    test("every setup and teardown project is grouped as setup", () => {
      for (const projects of [composeProjects(undefined), k8sProjects()]) {
        const lifecycle = projects.filter((each) => /setup|teardown/.test(String(each.testMatch)));
        expect(lifecycle.length).toBeGreaterThan(0);
        for (const each of lifecycle) expect(each.metadata?.group, each.name).toBe("setup");
        const groups = Object.fromEntries(projects.map((each) => [each.name, each.metadata?.group ?? "product"]));
        expect(groups).toMatchObject({ schema: "suite", harness: "suite", tooling: "setup", api: "product", ui: "product", vscode: "product" });
      }
    });

    test("suite-teardown collects only the micro and broker teardowns", () => {
      const testMatch = composeProjects(undefined).find((each) => each.name === "suite-teardown")?.testMatch;
      expect(testMatch).toBeInstanceOf(RegExp);
      const collects = (file: string) => (testMatch as RegExp).test(`/repo/e2e/${file}`);
      expect(collects("specs/seed-micro/seed-micro.teardown.ts")).toBe(true);
      expect(collects("specs/brokers-seed/brokers.teardown.ts")).toBe(true);
      expect(collects("specs/brokers-micro-seed/brokers-micro.teardown.ts")).toBe(true);
      // `seed`, `ui-server`, and `vscode-server` keep teardowns of their own, and `global` depends on the first two.
      expect(collects("specs/seed/seed.teardown.ts")).toBe(false);
      expect(collects("specs/ui-server/ui-server.teardown.ts")).toBe(false);
      expect(collects("specs/vscode-server/vscode-server.teardown.ts")).toBe(false);
      expect(collects("specs/brokers-seed/brokers.setup.ts")).toBe(false);
    });

    test("E2E_BROKERS=0 drops the broker projects as absent", () => {
      const dropped = { compose: BROKER_PROJECTS, k8s: [...BROKER_PROJECTS, ...MICRO_BROKER_PROJECTS] };
      for (const on of ["compose", "k8s"] as const) {
        withEnv({ E2E_BROKERS: "0" }, () => {
          const setup = targetSetup(on);
          expect(setup.absent, on).toEqual(dropped[on].map((name) => ({ name, reason: "E2E_BROKERS=0" })));
          // A dependency or a teardown naming a dropped project would fail the config.
          const projects = suiteProjects(setup);
          const names = projects.map((each) => each.name);
          const named = projects.flatMap((each) => [...(each.dependencies ?? []), ...(each.teardown ? [each.teardown] : [])]);
          expect(named.filter((name) => !names.includes(name)), on).toEqual([]);
        });
      }
      expect(composeProjects("0").map((each) => each.name).sort()).toEqual(
        COMPOSE_PROJECTS.filter((name) => !BROKER_PROJECTS.includes(name)).sort(),
      );
      expect(withEnv({ E2E_BROKERS: "0" }, () => targetSetup("k8s")).projects.map((each) => each.name)).toEqual(K8S_PROJECTS);
    });

    test("a project asks for the micro engine, which Compose refuses", () => {
      // Typed so that `tsc` checks the option on a project, as `runtime-micro` sets it.
      const micro: SuiteProject = { name: "runtime-micro", use: { engineKind: "micro" } };
      expect(targetSetup("k8s").projects.find((each) => each.name === micro.name)?.use).toEqual(micro.use);
      const compose = targetSetup("compose");
      expect(compose.createEnv("classic").name).toBe("compose");
      expect(() => compose.createEnv("micro")).toThrow(/classic engine only/);
    });

    test("runtime-micro-redeploy reads a state file of its own", () => {
      const project = k8sProject("runtime-micro-redeploy");
      expect(project && schedule(project, false)).toBe("./specs/runtime-micro-redeploy; 1 worker");
      expect(project?.grepInvert).toEqual(/@classic-only/);
      // Never the shared domain's file: a deploy there would roll the pod under every runtime-micro case.
      expect(project?.use).toEqual({ engineKind: "micro", microDomainState: MICRO_REDEPLOY_STATE_FILE });
      expect(MICRO_REDEPLOY_STATE_FILE).not.toBe(MICRO_CORPUS_STATE_FILE);
    });

    test("global-micro runs the engine files of specs/global", () => {
      const project = k8sProject("global-micro");
      expect(project && schedule(project, false)).toBe(
        "./specs/global /\\/(live-exchanges|runtime-deployments)\\.spec\\.ts$/; 1 worker",
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
    });

    test("env-micro restarts the micro domain after classic restarts", () => {
      const projects = withEnv({ E2E_BROKERS: "0" }, () => suiteProjects(targetSetup("k8s")));
      const project = projects.find((each) => each.name === "env-micro");
      expect(project && schedule(project, false)).toBe("./specs/env /restart-resilience\\.spec\\.ts$/; 1 worker");
      expect(project?.grepInvert).toEqual(/@classic-only/);
      // The shared domain's state file, the default, and the micro copy seed-micro deployed to it.
      expect(project?.use).toEqual({ engineKind: "micro" });
      const files = fs.readdirSync(path.join(E2E_DIR, "specs", "env"));
      expect(files.filter((file) => (project?.testMatch as RegExp).test(`/${file}`))).toEqual(["restart-resilience.spec.ts"]);
      // Through every edge, the classic restarts in `env` and the session wipe in `global` come first.
      expect([...waitsFor(projects, "env-micro")]).toEqual(expect.arrayContaining(["env", "global"]));
    });

    test("brokers-micro-seed deploys to a domain of its own", () => {
      const project = k8sProject("brokers-micro-seed");
      expect(project && schedule(project, false)).toBe("./specs/brokers-micro-seed /.*\\.setup\\.ts$/");
      expect(project?.use).toEqual({ engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE });
      expect([MICRO_CORPUS_STATE_FILE, MICRO_REDEPLOY_STATE_FILE]).not.toContain(BROKERS_MICRO_CORPUS_STATE_FILE);
      // The setup project collects its seed, and `suite-teardown` the teardown file beside it.
      const dir = path.join(E2E_DIR, "specs", "brokers-micro-seed");
      expect(fs.readdirSync(dir).filter((file) => (project?.testMatch as RegExp).test(file))).toEqual(["brokers-micro.setup.ts"]);
      expect(fs.existsSync(path.join(dir, "brokers-micro.teardown.ts"))).toBe(true);
    });

    test("brokers-micro runs the broker specs on the micro copy", () => {
      const project = k8sProject("brokers-micro");
      expect(project && schedule(project, false)).toBe("./specs/brokers except /broker-restart\\.spec\\.ts$/; parallel");
      expect(project?.grepInvert).toEqual(/@classic-only/);
      // The env fixture and brokerChain both read the brokers-micro domain, never the shared micro one.
      expect(project?.use).toEqual({ engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE });
      // Each pattern it leaves out matches one spec file of specs/brokers/.
      const files = fs.readdirSync(path.join(E2E_DIR, "specs", "brokers"));
      for (const pattern of project?.testIgnore as RegExp[]) {
        expect(files.filter((file) => pattern.test(file))).toHaveLength(1);
      }
    });

    // No restart project but env-micro waits for a broker case, so a red broker case skips none of them.
    test("brokers-restart runs after every service restart", () => {
      const brokerProjects = ["brokers-seed", "brokers", "brokers-micro-seed", "brokers-micro", "brokers-restart"];
      for (const projects of [k8sProjects(), composeProjects(undefined)]) {
        expect([...waitsFor(projects, "brokers-restart")]).toEqual(expect.arrayContaining(["env", "global"]));
        for (const name of ["env", "global"]) {
          expect([...waitsFor(projects, name)].filter((each) => brokerProjects.includes(each)), name).toEqual([]);
        }
      }
    });
  });

  test.describe("addresses", () => {
    test("the service ports follow the target", () => {
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

    test("the broker host addresses follow the target", () => {
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

    test("a CIP_* broker variable wins on either target", () => {
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

    test("Compose serves the engine and its meters on its own port", () => {
      withEnv({ CIP_TARGET: "compose", CIP_ENGINE_URL: undefined }, () => {
        const env = targetSetup("compose").createEnv("classic");
        expect(env.engineUrl()).toBe("http://localhost:8092");
        expect(env.engineMetricsUrl()).toBe("http://localhost:8092/actuator/prometheus");
        expect(env.chainUrl("e2e/echo")).toBe("http://localhost:8092/routes/e2e/echo");
      });
    });
  });
});
