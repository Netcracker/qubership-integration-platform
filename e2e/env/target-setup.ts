/**
 * What differs between the two targets, behind one interface.
 *
 * `env/target.ts` names the target and imports nothing; this module imports both adapters and
 * answers `targetSetup()`: the environment a worker gets, the provisioner `globalSetup` calls, the
 * observer that records the stack, and the projects the target adds: its own, and the broker
 * projects unless `E2E_BROKERS=0`. `playwright.config.ts` builds its project list from the common
 * projects plus `projects`.
 */
import type {
  PlaywrightTestOptions,
  PlaywrightWorkerOptions,
  Project,
} from "@playwright/test";
import { ComposeEnv } from "./compose.js";
import type { EngineKind, Env, ProcessEnvelope } from "./index.js";
import type { ProvisionMode, ProvisionReport } from "./provision.js";
import { BROKERS_MICRO_CORPUS_STATE_FILE, K8sEnv, MICRO_REDEPLOY_STATE_FILE, recordedMicroDomain } from "./k8s.js";
import { collectK8sHeader, k8sBaseline, topSample } from "./k8s-observer.js";
import { provisionCompose } from "./provision/compose.js";
import { provisionK8s } from "./provision/k8s.js";
import { composeBaseline, startResourceSampler } from "./resources.js";
import { brokersEnabled, target, type Target } from "./target.js";
import { collectStackHeader, type Absence, type StackFacts } from "../support/report.js";
import { PROJECT_LANES } from "../support/lanes.mjs";

/** The worker-scoped options a project can set, which `support/fixtures.ts` declares. */
export interface SuiteWorkerOptions {
  engineKind: EngineKind;
  microDomainState: string;
}

/** A project of the suite. Named, unlike Playwright's own type, because every dependency names one. */
export interface SuiteProject
  extends Project<PlaywrightTestOptions, PlaywrightWorkerOptions & SuiteWorkerOptions> {
  name: string;
}

/** The resource baseline, the sampler, and the facts the run header records. */
export interface StackObserver {
  /** The envelope of every role, taken before any project runs. */
  baseline(): Promise<ProcessEnvelope[]>;
  /** Starts sampling for the rest of the run, in a process that outlives `globalSetup`. */
  startSampler(run: string, baseline: readonly ProcessEnvelope[]): void;
  header(about: { run: string; workers: number; provision: ProvisionReport }): Promise<StackFacts>;
}

export interface TargetSetup {
  name: Target;
  /** A project's environment; a micro one addresses the domain `microDomainState` records. */
  createEnv(engineKind: EngineKind, microDomainState?: string): Env;
  /** `microProjects` are the projects of the run that run the micro engine. */
  provision(mode: ProvisionMode, microProjects: readonly string[]): Promise<ProvisionReport>;
  observer: StackObserver;
  /** Projects this target adds to the common ones. */
  projects: SuiteProject[];
  /** Projects left out on this target, printed in the report header. */
  absent: Absence[];
}

/** The broker projects of both targets; `brokers-restart` runs after `restartAfter` and `global`. */
function brokerProjects(restartAfter: string[] = ["brokers"]): SuiteProject[] {
  return [
    {
      // Imports and deploys `fixtures/brokers/`. A project of its own rather than a step hung
      // off `seed`: the engine's pre-deploy connectivity checks would stall the whole corpus
      // whenever a broker is down, so `support/brokers.ts` keeps these fixtures out of the
      // directory the `seed` project imports wholesale. `testMatch` is not optional, for the
      // reason `seed`'s is not: Playwright's default collects `*.setup.ts` not at all, and a
      // setup project that runs zero tests reports success and satisfies its dependents.
      // `suite-teardown` runs `brokers.teardown.ts` after `global` as well as after the broker
      // projects; `playwright.config.ts` says why.
      //
      // After `seed`, because both deploy to the classic engine, and the engine reports the state of
      // a batch of deployments only once the whole batch has loaded. Run beside `seed`, the broker
      // chains waited for the corpus batch and ran out of their deploy budget. The edge also gates,
      // so a red `seed` skips every broker project.
      name: "brokers-seed",
      metadata: { group: "setup" },
      testDir: "./specs/brokers-seed",
      testMatch: /.*\.setup\.ts$/,
      dependencies: ["seed"],
      teardown: "suite-teardown",
    },
    {
      // Kafka and RabbitMQ carry more production traffic than any other transport, so this runs
      // in the default run rather than as an opt-in. Its overlays come up through
      // `Env.ensureOverlay` when the project runs — a broker that fails to start fails the run
      // at setup with a clear message rather than in the middle of a spec.
      //
      // `broker-restart.spec.ts` is excluded and picked up by `brokers-restart` below instead:
      // it restarts a broker container in place, which disturbs every other file here the same
      // way a service restart disturbs `api` and `runtime` — see that project's own comment.
      name: "brokers",
      testDir: "./specs/brokers",
      testIgnore: /broker-restart\.spec\.ts$/,
      fullyParallel: true,
      metadata: { lanes: PROJECT_LANES.brokers },
      dependencies: ["brokers-seed"],
    },
    {
      // The one file `brokers` excludes above. A restart costs seconds and disturbs every
      // in-flight call against the broker it targets, so it runs alone, strictly after every
      // other broker spec — the same scheduling need `env` exists for, applied to a broker
      // rather than a platform service. `workers: 1` rather than `fullyParallel: false`, for the
      // measured reason `env`'s own comment gives: the latter only serializes within one file and
      // says nothing about files run alongside it. On `k8s` it also runs after `brokers-micro`, so a
      // red micro parity case there skips the classic restart cases.
      //
      // After `global` as well, so it never overlaps the restarts in `env`: in CI run 38651497943 the
      // two ran side by side, the engine restart replaced the consumer of `kafka-trigger-basic`, and
      // the restart case gave up 13 s before the new engine consumed its message. It waits for
      // `global` rather than `env`, because after `env` alone it would run beside the session wipe in
      // `global`. The edge points this way because `env` and `global` must not wait for a broker
      // project: one red broker case would skip both. The cost is the reverse, so a red `global`
      // case skips the restart cases. On `k8s`, `env-micro` waits for this project too.
      name: "brokers-restart",
      testDir: "./specs/brokers",
      testMatch: /broker-restart\.spec\.ts$/,
      workers: 1,
      dependencies: [...restartAfter, "global"],
    },
  ];
}

function composeSetup(): TargetSetup {
  const brokers = brokersEnabled();
  return {
    name: "compose",
    createEnv(engineKind) {
      if (engineKind !== "classic") {
        throw new Error(
          `The compose target runs the classic engine only, and a project asked for ` +
            `engineKind "${engineKind}". The micro engine exists only with CIP_TARGET=k8s.`,
        );
      }
      return new ComposeEnv();
    },
    provision: provisionCompose,
    observer: {
      baseline: composeBaseline,
      startSampler(run, baseline) {
        startResourceSampler(run, baseline);
      },
      header: collectStackHeader,
    },
    projects: brokers ? brokerProjects() : [],
    absent: brokers ? [] : brokersAbsent(),
  };
}

function brokersAbsent(projects: readonly SuiteProject[] = brokerProjects()): Absence[] {
  return projects.map(({ name }) => ({ name, reason: "E2E_BROKERS=0" }));
}

/** The broker projects only the Kubernetes target declares, since the micro engine runs only there. */
function microBrokerProjects(): SuiteProject[] {
  return [
    {
      // The micro copy of `fixtures/brokers/`, on a micro domain of its own, declared the way
      // `seed-micro` is. It needs no edge to `seed-micro`, because each domain runs its own pod.
      // Its teardown reads the catalog and the state file only, since `suite-teardown` runs with a
      // classic `env`.
      name: "brokers-micro-seed",
      metadata: { group: "setup" },
      testDir: "./specs/brokers-micro-seed",
      testMatch: /.*\.setup\.ts$/,
      teardown: "suite-teardown",
      use: { engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE },
    },
    {
      // `specs/brokers/` against the micro copy, which `brokerChain` returns on a micro project. The
      // specs read the classic corpus state first, so the project waits for both seeds.
      // `broker-restart.spec.ts` runs in `brokers-restart`, after this project.
      name: "brokers-micro",
      testDir: "./specs/brokers",
      testIgnore: [/broker-restart\.spec\.ts$/],
      grepInvert: /@classic-only/,
      fullyParallel: true,
      metadata: { lanes: PROJECT_LANES["brokers-micro"] },
      dependencies: ["brokers-seed", "brokers-micro-seed"],
      use: { engineKind: "micro", microDomainState: BROKERS_MICRO_CORPUS_STATE_FILE },
    },
  ];
}

function k8sProjects(brokers: boolean): SuiteProject[] {
  return [
    {
      // What exists only on a cluster: the custom resources, the gateway routes, discovery, and
      // the e2e proxy locations. Like the broker projects, it is not named in the dependencies of
      // `env` and `global`, so a failure here skips neither.
      name: "k8s",
      testDir: "./specs/k8s",
      fullyParallel: true,
      metadata: { lanes: PROJECT_LANES.k8s },
      dependencies: ["seed"],
    },
    {
      // The micro copy of the corpus, on a micro domain of the run. A project apart from `seed`,
      // declared the way `brokers-seed` is: one micro chain that fails to load takes the whole pod
      // down, and here that fails `runtime-micro` alone.
      name: "seed-micro",
      metadata: { group: "setup" },
      testDir: "./specs/seed-micro",
      testMatch: /.*\.setup\.ts$/,
      teardown: "suite-teardown",
      use: { engineKind: "micro" },
    },
    {
      // `specs/runtime/` again, against the micro engine. Not named in the dependencies of `env`
      // and `global`: Playwright runs a dependent project only when its dependencies pass, so a
      // parity finding here would skip both, the trade `brokers` is kept out of them for. The cost
      // is an overlap nobody has measured: `env` restarts the engine and the catalog, and `global`
      // deletes every session in OpenSearch, and either can happen while a case here is calling the
      // micro domain or reading its session.
      name: "runtime-micro",
      testDir: "./specs/runtime",
      // A case that cannot apply to the micro engine carries `@classic-only` and says why.
      grepInvert: /@classic-only/,
      fullyParallel: true,
      metadata: { lanes: PROJECT_LANES["runtime-micro"] },
      dependencies: ["seed-micro", "tooling"],
      use: { engineKind: "micro" },
    },
    {
      // Micro cases that deploy and redeploy chains of their own. Each file deploys to a micro
      // domain of its own and deletes it afterwards, and every deploy rolls that domain's pod, so
      // `workers: 1` keeps one such domain alive at a time. After `seed-micro`, so its first JVM
      // start never overlaps the shared domain's. The domain a file deployed is recorded in its own
      // state file, which the `env` fixture reads, so a failed case attaches that pod's log.
      name: "runtime-micro-redeploy",
      testDir: "./specs/runtime-micro-redeploy",
      grepInvert: /@classic-only/,
      workers: 1,
      metadata: { lanes: PROJECT_LANES["runtime-micro-redeploy"] },
      dependencies: ["seed-micro"],
      use: { engineKind: "micro", microDomainState: MICRO_REDEPLOY_STATE_FILE },
    },
    {
      // The two `specs/global/` files that read the engine, against the shared micro domain. After
      // `global`, because `global` deletes every session in OpenSearch and the kill cases here read
      // the sessions they caused. The cost is the trade `env` makes with `ui`: a red `global` case
      // skips this project.
      name: "global-micro",
      testDir: "./specs/global",
      testMatch: /\/(live-exchanges|runtime-deployments)\.spec\.ts$/,
      grepInvert: /@classic-only/,
      workers: 1,
      dependencies: ["global", "seed-micro"],
      use: { engineKind: "micro" },
    },
    {
      // The engine and catalog restarts of `specs/env/`, against the shared micro domain, whose pod
      // the engine restart deletes. After `global-micro`, which runs after `global` and so after
      // `env`, so no micro JVM start overlaps the classic restarts, and after `runtime-micro`, whose
      // cases call the pod this project deletes. With the brokers on, after `brokers-restart` too,
      // which would otherwise run beside this project's catalog restart. The cost: a red `global`,
      // `runtime-micro`, or broker case skips this project.
      name: "env-micro",
      testDir: "./specs/env",
      testMatch: /restart-resilience\.spec\.ts$/,
      grepInvert: /@classic-only/,
      workers: 1,
      dependencies: ["global-micro", "runtime-micro", ...(brokers ? ["brokers-restart"] : [])],
      use: { engineKind: "micro" },
    },
  ];
}

function k8sSetup(): TargetSetup {
  const brokers = brokersEnabled();
  return {
    name: "k8s",
    createEnv: (engineKind, microDomainState) =>
      new K8sEnv({ engineKind, microDomain: () => recordedMicroDomain(microDomainState) }),
    provision: provisionK8s,
    observer: {
      baseline: k8sBaseline,
      startSampler(run, baseline) {
        startResourceSampler(run, baseline, topSample());
      },
      header: collectK8sHeader,
    },
    projects: [
      ...k8sProjects(brokers),
      ...(brokers ? [...brokerProjects(["brokers", "brokers-micro"]), ...microBrokerProjects()] : []),
    ],
    absent: brokers ? [] : brokersAbsent([...brokerProjects(), ...microBrokerProjects()]),
  };
}

/** The setup of `name`, which defaults to the target `CIP_TARGET` names. */
export function targetSetup(name: Target = target()): TargetSetup {
  return name === "compose" ? composeSetup() : k8sSetup();
}
