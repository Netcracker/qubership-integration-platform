/**
 * What differs between the two targets, behind one interface.
 *
 * `env/target.ts` names the target and imports nothing; this module imports both adapters and
 * answers `targetSetup()`: the environment a worker gets, the provisioner `globalSetup` calls, the
 * observer that records the stack, and the projects only one target declares.
 * `playwright.config.ts` builds its project list from the common projects passed through
 * `decorate`, plus `projects`.
 */
import type {
  PlaywrightTestOptions,
  PlaywrightWorkerOptions,
  Project,
} from "@playwright/test";
import { ComposeEnv } from "./compose.js";
import type { EngineKind, Env, ProcessEnvelope } from "./index.js";
import type { ProvisionMode, ProvisionReport } from "./provision.js";
import { K8sEnv } from "./k8s.js";
import { collectK8sHeader, k8sBaseline, topSample } from "./k8s-observer.js";
import { provisionCompose } from "./provision/compose.js";
import { provisionK8s } from "./provision/k8s.js";
import { composeBaseline, startResourceSampler } from "./resources.js";
import { target, type Target } from "./target.js";
import { collectStackHeader, type Absence, type StackFacts } from "../support/report.js";

/** The worker-scoped options a project can set, which `support/fixtures.ts` declares. */
export interface SuiteWorkerOptions {
  engineKind: EngineKind;
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
  createEnv(engineKind: EngineKind): Env;
  provision(mode: ProvisionMode): Promise<ProvisionReport>;
  observer: StackObserver;
  /** Projects declared only on this target. */
  projects: SuiteProject[];
  /** The target-specific edges on a common project, such as `global`'s `teardown` on Compose. */
  decorate(project: SuiteProject): SuiteProject;
  /** Projects and spec files left out on this target, printed in the report header. */
  absent: Absence[];
}

/**
 * Whether the `brokers` project runs, `E2E_BROKERS=0` being the one way to say no.
 *
 * On by default: Kafka and RabbitMQ carry more production traffic than any other transport, so a
 * release run without them does not answer the release question. The flag is for a faster local
 * run without the broker overlays. Playwright 1.62 has no per-project `default: false`, so leaving a
 * project out of a run means leaving it out of the project list, which is what `composeSetup` does.
 */
function brokersEnabled(): boolean {
  return process.env.E2E_BROKERS !== "0";
}

function brokerProjects(): SuiteProject[] {
  return [
    {
      // Imports and deploys `fixtures/brokers/`. A project of its own rather than a step hung
      // off `seed`: the engine's pre-deploy connectivity checks would stall the whole corpus
      // whenever a broker is down, so `support/brokers.ts` keeps these fixtures out of the
      // directory the `seed` project imports wholesale. `testMatch` is not optional, for the
      // reason `seed`'s is not: Playwright's default collects `*.setup.ts` not at all, and a
      // setup project that runs zero tests reports success and satisfies its dependents.
      name: "brokers-seed",
      testDir: "./specs/brokers-seed",
      testMatch: /.*\.setup\.ts$/,
      teardown: "brokers-seed-teardown",
    },
    {
      // A project of its own, named by `brokers-seed`'s `teardown` property — the same split
      // `seed` and `seed-teardown` use, for the same reason: both setup and teardown behind one
      // `testMatch` would make the teardown an ordinary test of the setup project, running
      // before a single broker spec had used the corpus.
      //
      // `global` also names this project as its own `teardown` target, through `decorate` — a second use of
      // the same mechanism, for a problem `brokers`/`brokers-restart`'s `dependencies` on
      // `brokers-seed` cannot reach. Measured with `DEBUG=pw:test:task`: before that second
      // edge, this project's own phase held nothing back from `env`'s, so `teardownBrokers` —
      // `deleteLoggingProperties`, `undeployAll`, chain deletes, then a 15 s Kafka
      // delete-and-confirm loop that assumes the engine's consumers are stopping — ran
      // concurrently with `restart-resilience.spec.ts` and `service-type-roundtrip.spec.ts`
      // force-recreating the engine and the catalog in `env`. A recreated engine re-attaches its
      // consumers and re-creates the very topics that loop is deleting, and a catalog down for
      // 30 s pushes onto `teardownBrokers`'s own failure list. `global` picking up this project's
      // `teardown` closes that gap without either project naming the other in `dependencies` —
      // see `global`'s own comment for the mechanism and the trade-off it carries instead.
      name: "brokers-seed-teardown",
      testDir: "./specs/brokers-seed",
      testMatch: /.*\.teardown\.ts$/,
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
      dependencies: ["brokers-seed"],
    },
    {
      // The one file `brokers` excludes above. A restart costs seconds and disturbs every
      // in-flight call against the broker it targets, so it runs alone, strictly after every
      // other broker spec — the same scheduling need `env` exists for, applied to a broker
      // rather than a platform service. `workers: 1` rather than `fullyParallel: false`, for the
      // measured reason `env`'s own comment gives: the latter only serializes within one file and
      // says nothing about files run alongside it.
      name: "brokers-restart",
      testDir: "./specs/brokers",
      testMatch: /broker-restart\.spec\.ts$/,
      workers: 1,
      dependencies: ["brokers"],
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
            `engineKind "${engineKind}". The micro engine exists only with QIP_TARGET=k8s.`,
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
    decorate(project) {
      // Declaring `brokers-seed-teardown` as `global`'s own `teardown` target orders that teardown
      // after `global` without gating either one on the other's success. More than one project may
      // name the same `teardown` target — Playwright collects every project that does and holds the
      // teardown back until all of them, and everything that depends on any of them, have run — so
      // `brokers-seed` and `global` can both point at `brokers-seed-teardown` at once. (A
      // *teardown* project cannot declare `dependencies` of its own — `common/index.js:716`,
      // checked against this config directly — but that restriction falls on `brokers-seed-
      // teardown`, not on the projects naming it.) `global`'s own `deps` array is untouched by
      // this: the phase-ordering check that decides whether `brokers-seed-teardown` may start reads
      // which projects have already *run*, and the pass/fail gate that decides whether `global`
      // itself runs reads only `global`'s own `dependencies` — two different reads over two
      // different fields, both confirmed directly in `node_modules/playwright/lib/runner/index.js`.
      // So a failing broker case still cannot skip `global`, the property this suite chose over
      // naming `brokers`/`brokers-restart` in `global`'s `dependencies`, and `teardownBrokers` now
      // waits for `global` to finish the way it used to before that edge was removed, closing the
      // race `brokers-seed-teardown`'s own comment describes. Restricted to a run with brokers:
      // `brokers-seed-teardown` does not exist as a project when brokers are dropped, and naming an
      // unknown `teardown` project fails the config outright.
      return brokers && project.name === "global"
        ? { ...project, teardown: "brokers-seed-teardown" }
        : project;
    },
    absent: brokers ? [] : brokerProjects().map(({ name }) => ({ name, reason: "E2E_BROKERS=0" })),
  };
}

/**
 * The `specs/runtime/` files `runtime-micro` leaves out. Each deploys chains of its own through the
 * classic deployment API, calls the classic engine directly, or starts chains through the testing
 * service, whose engine address is the classic engine's.
 */
export const CLASSIC_ONLY_RUNTIME_FILES: readonly string[] = [
  "checkpoint-retry.spec.ts",
  "http-trigger-system-type.spec.ts",
  "logging-level.spec.ts",
  "metrics.spec.ts",
  "misc-elements.spec.ts",
  "placeholder.spec.ts",
  "script-failures.spec.ts",
  "service-call-axes.spec.ts",
  "service-call-sync.spec.ts",
  "testing-service-cases.spec.ts",
  "testing-service-runs.spec.ts",
];

function k8sProjects(): SuiteProject[] {
  return [
    {
      // What exists only on a cluster: the custom resources, the gateway routes, discovery, and
      // the e2e proxy locations. Like the broker projects, it is not named in the dependencies of
      // `env` and `global`, so a failure here skips neither.
      name: "k8s",
      testDir: "./specs/k8s",
      fullyParallel: true,
      dependencies: ["seed"],
    },
    {
      // The micro copy of the corpus, on a micro domain of the run. A project apart from `seed`,
      // declared the way `brokers-seed` is: one micro chain that fails to load takes the whole pod
      // down, and here that fails `runtime-micro` alone.
      name: "seed-micro",
      testDir: "./specs/seed-micro",
      testMatch: /.*\.setup\.ts$/,
      teardown: "seed-micro-teardown",
      use: { engineKind: "micro" },
    },
    {
      name: "seed-micro-teardown",
      testDir: "./specs/seed-micro",
      testMatch: /.*\.teardown\.ts$/,
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
      testIgnore: CLASSIC_ONLY_RUNTIME_FILES.map((file) => `**/${file}`),
      fullyParallel: true,
      dependencies: ["seed-micro", "tooling"],
      use: { engineKind: "micro" },
    },
  ];
}

function k8sSetup(): TargetSetup {
  return {
    name: "k8s",
    createEnv: (engineKind) => new K8sEnv({ engineKind }),
    provision: provisionK8s,
    observer: {
      baseline: k8sBaseline,
      startSampler(run, baseline) {
        startResourceSampler(run, baseline, topSample());
      },
      header: collectK8sHeader,
    },
    projects: k8sProjects(),
    decorate(project) {
      // The micro teardown deletes the micro copy's chains through the catalog, and `env`
      // restarts the catalog. Measured: with nothing ordering the two, the teardown ran during
      // that restart and three chain deletes failed with `socket hang up`. `global` runs after
      // `env`, and naming the teardown here holds it back without gating either project, as
      // `brokers-seed-teardown` is held back on Compose.
      return project.name === "global" ? { ...project, teardown: "seed-micro-teardown" } : project;
    },
    absent: [
      ...brokerProjects().map(({ name }) => ({
        name,
        reason: "the brokers have no chart on the k8s target",
      })),
      ...CLASSIC_ONLY_RUNTIME_FILES.map((file) => ({
        name: `runtime-micro: specs/runtime/${file}`,
        reason: "classic-only: it deploys its own chains, calls the classic engine, or uses the testing service",
      })),
    ],
  };
}

/** The setup of `name`, which defaults to the target `QIP_TARGET` names. */
export function targetSetup(name: Target = target()): TargetSetup {
  return name === "compose" ? composeSetup() : k8sSetup();
}
