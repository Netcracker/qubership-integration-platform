/**
 * The run's shape, decided **once**, here.
 *
 * A declared Playwright project runs by default and a `dependencies` entry forces its target into
 * the run, so "which projects exist" has to be one decision rather than three files disagreeing
 * about it.
 *
 * `CIP_TARGET` picks the infrastructure, `compose` by default and `k8s` for a local cluster, and any
 * other value fails the config at load. The common projects below run on both targets. The target
 * adds the projects only it declares through `env/target-setup.ts`, so the list below never names a
 * target.
 */

import {
  defineConfig,
  devices,
  type PlaywrightTestOptions,
  type PlaywrightWorkerOptions,
} from "@playwright/test";
import { proxyUrl, serviceUrl } from "./env/containers.js";
import { target } from "./env/target.js";
import { PROJECT_LANES } from "./support/lanes.mjs";
import { VSCODE_PORT } from "./support/vscode-server.js";
import {
  targetSetup,
  type SuiteProject,
  type SuiteWorkerOptions,
  type TargetSetup,
} from "./env/target-setup.js";

/**
 * Playwright derives its worker count from the **host's** cores, and the number that matters is
 * what the stack can absorb. Measured: 12 chains re-snapshotted and redeployed six at a time plus
 * 360 requests took the containers to 94% of 12 allocated cores — under a load lighter than a full
 * run, and with no browser. So the pool is pinned rather than inherited.
 *
 * 4 and 8 were both measured on this stack. Over the whole suite the two are indistinguishable
 * (1m37 against 1m34), because the wall time is dominated by the `env` project's two 31 s container
 * restarts, which run one worker whatever this says. Over `api` alone — the parallel mass, 127
 * specs — 8 is the clear winner: 12.1 s twice against 22.8 s and 15.9 s. Six runs at 8 produced no
 * failure, so it is inside the 5% false-failure budget, and `E2E_WORKERS` overrides it on a machine
 * that measures something better.
 */
const workers = Number(process.env.E2E_WORKERS ?? 8);

const suite: SuiteProject[] = [
  {
    // Reads the product's tracked files off disk: the schemas, routes.conf, the Compose files, the
    // migrations. No stack, no network, seconds to run — so it goes first and a broken file costs
    // nothing to discover.
    //
    // `stack: false` is that fact in a form code can read. `env/provision.ts` returns early when every
    // selected project declares it, which is what lets `--project=schema --project=harness` run where
    // there is no Docker at all: Playwright runs `globalSetup` once per run, before any project and
    // whatever the selection, and offers no flag that skips it. Measured on 1.62.1: a config with
    // three projects invokes it once, and selecting a single project invokes it once as well, which is
    // the half that decides this. Only `--list` invokes it not at all. A project that says nothing
    // here is provisioned for.
    name: "schema",
    testDir: "./specs/schema",
    fullyParallel: true,
    metadata: { stack: false, group: "suite" },
  },
  {
    // The tests of the suite's own code: the report readers, the registries, the target adapters,
    // and the lint cases over the spec sources. No stack, for the reason `schema` gives; a project
    // of its own so that the run's counts leave these tests out.
    name: "harness",
    testDir: "./specs/harness",
    fullyParallel: true,
    metadata: { stack: false, group: "suite" },
  },
  {
    // `seed` is declared here rather than left to the specs that read the corpus. Playwright's
    // `dependencies` fixes only the *start* of a project's window; the corpus's end is already held
    // by `seed`'s `teardown`, which runs after every project depending on `seed`. Without this edge
    // a spec here reading a seeded chain can start before the seed has imported one, and the
    // failure lands on the spec rather than on the corpus.
    name: "api",
    testDir: "./specs/api",
    fullyParallel: true,
    metadata: { lanes: PROJECT_LANES.api },
    dependencies: ["seed"],
  },
  {
    // Imports and deploys the shared corpus. `testMatch` is not optional: Playwright's default is
    // `**/*.@(spec|test).?(c|m)[jt]s?(x)`, which collects `seed.setup.ts` not at all — and a setup
    // project that runs zero tests reports success and satisfies its dependents, so the failure
    // lands on every spec downstream instead of here.
    name: "seed",
    metadata: { group: "setup" },
    testDir: "./specs/seed",
    testMatch: /.*\.setup\.ts$/,
    teardown: "seed-teardown",
  },
  {
    // A project of its own, named by `seed`'s `teardown`. Playwright's `teardown` property names
    // **another project** (`playwright/types/test.d.ts:587`), and it runs after every project that
    // depends on the one declaring it — which is what gives the corpus its lifetime. Both files
    // behind one `testMatch` would make the teardown an ordinary test of the setup project.
    name: "seed-teardown",
    metadata: { group: "setup" },
    testDir: "./specs/seed",
    testMatch: /.*\.teardown\.ts$/,
  },
  {
    // The testing service as a dependency rather than as a subject: it runs before `runtime` so a
    // broken mock binder is one named failure instead of a field of red runtime specs.
    name: "tooling",
    metadata: { group: "setup" },
    testDir: "./specs/tooling",
    dependencies: ["seed"],
  },
  {
    name: "runtime",
    testDir: "./specs/runtime",
    fullyParallel: true,
    metadata: { lanes: PROJECT_LANES.runtime },
    dependencies: ["seed", "tooling"],
  },
  {
    // Specs here restart a service to change how it runs. A restart costs about 30 s against
    // 0.15 s for an API assertion, and it disturbs every other spec in flight, so the project runs
    // only after `api` and `runtime` have finished. Placement is decided by that scheduling need,
    // not by subject: a spec belongs here as soon as it calls env.restartWith.
    //
    // `workers: 1`, not `fullyParallel: false`. Measured: three trivial spec files under
    // `fullyParallel: false` alone reported "Running 3 tests using 3 workers" with all three starts
    // inside 16 ms. `fullyParallel` serializes within a file and says nothing about files, and two
    // workers issuing `docker compose up --force-recreate` at once fail for no reason the report
    // can name.
    name: "env",
    testDir: "./specs/env",
    workers: 1,
    // No broker project is named here, though this project recreates the engine and the catalog
    // (`specs/env/restart-resilience.spec.ts`, `service-type-roundtrip.spec.ts`), and a recreate
    // drops the broker consumers the engine holds. Playwright's `dependencies` is one mechanism for
    // two different things, ordering a project after another and gating it on that other project's
    // success, and there is no way to ask for only the first: naming a `teardown` project in
    // `dependencies` is refused outright ("Project env must not depend on a teardown project",
    // `common/index.js:721`). So naming a broker project here would let one failing broker case skip
    // this project's every case, the opposite of `support/brokers.ts`'s own design ("a broker outage
    // fails one project rather than every one of them").
    //
    // The overlap that was measured is ordered from the other side. In CI run 38651497943
    // `brokers-restart` ran beside this project, and the engine restart replaced the Kafka consumer
    // the restart case waited on, so `brokers-restart` now depends on `global`, and through it on this
    // project (`env/target-setup.ts`). `brokers` and `brokers-micro` restart nothing, and their
    // overlap with this project stays reasoned, never measured: only Playwright's scheduling, which
    // starts a project once every project of a lower dependency depth has finished, keeps them apart.
    //
    // `ui` is named, unlike the broker projects: a restart here mid-scenario fails a browser case
    // for a reason that has nothing to do with the UI. The cost is the one described above: a red
    // `ui` case skips this project.
    dependencies: ["api", "runtime", "ui"],
  },
  {
    // Builds `schemas/` when its sources are newer than its outputs, and `ui-server` bundles what it
    // built. It reads and writes files only, so it needs no stack.
    name: "schemas-build",
    metadata: { stack: false, group: "setup" },
    testDir: "./specs/schemas-build",
    testMatch: /.*\.setup\.ts$/,
  },
  {
    // Serves the UI bundle on 4200, where nginx sends every request outside `/api/`, and stops it
    // again through its `teardown`. `testMatch` is not optional, for the reason `seed`'s is not.
    name: "ui-server",
    metadata: { group: "setup" },
    testDir: "./specs/ui-server",
    testMatch: /.*\.setup\.ts$/,
    dependencies: ["schemas-build"],
    teardown: "ui-server-teardown",
  },
  {
    name: "ui-server-teardown",
    metadata: { group: "setup" },
    testDir: "./specs/ui-server",
    testMatch: /.*\.teardown\.ts$/,
  },
  {
    // Builds the UI library and the extension when their inputs are newer, then serves VS Code for
    // the web on 3917 with the extension and `fixtures/vscode/workspace/`. No stack, like
    // `schemas-build`, whose output both builds read.
    name: "vscode-server",
    metadata: { stack: false, group: "setup" },
    testDir: "./specs/vscode-server",
    testMatch: /.*\.setup\.ts$/,
    dependencies: ["schemas-build"],
    teardown: "vscode-server-teardown",
  },
  {
    name: "vscode-server-teardown",
    metadata: { stack: false, group: "setup" },
    testDir: "./specs/vscode-server",
    testMatch: /.*\.teardown\.ts$/,
  },
  {
    // The extension's editors inside VS Code for the web on 3917, through `support/vscode-guard.ts`.
    // One worker: each case starts a workbench and an extension host of its own, in a fresh browser
    // context. No stack, like the server it depends on.
    name: "vscode",
    testDir: "./specs/vscode",
    workers: 1,
    metadata: { stack: false },
    dependencies: ["vscode-server"],
    use: {
      // A Windows user agent on every host, so the workbench binds Ctrl rather than Cmd on a Mac.
      ...devices["Desktop Chrome"],
      baseURL: `http://localhost:${VSCODE_PORT}`,
      viewport: { width: 1600, height: 900 },
      // `VsCodePage.readAsText` copies a file's text and reads it back in the top frame.
      permissions: ["clipboard-read", "clipboard-write"],
    },
  },
  {
    // The browser layer: Chromium only, through nginx on 8080. `seed` holds the corpus open until
    // this project finishes, and `ui-server` holds the bundle up the same way.
    name: "ui",
    testDir: "./specs/ui",
    fullyParallel: true,
    metadata: { lanes: PROJECT_LANES.ui },
    dependencies: ["seed", "ui-server"],
    use: {
      ...devices["Desktop Chrome"],
      baseURL: proxyUrl(),
      // Pinned rather than inherited: the chain canvas renders only the nodes inside the viewport,
      // so `specs/ui/chain-graph.spec.ts` counts nodes against this size.
      viewport: { width: 1600, height: 900 },
    },
  },
  {
    // Reads the platform holds no worker can scope: an export of every chain, a total, a sorted
    // page. `e2e/AGENTS.md` rule 2 sends such a spec out of `specs/api/` and into a project of its
    // own, and this is that project.
    //
    // `workers: 1` alone would not be enough, because it bounds this project and not the run:
    // Playwright schedules every project into one pool, so a parallel worker deleting its folder
    // mid-export is what made `GET /v1/catalog/export` answer 500 for 3 of 120 calls. The
    // dependency list is what actually quiets the stack, and it names every project this one
    // collides with rather than relying on `env` to drag them in. `dependencies` orders what it
    // lists and never stops an unlisted project running alongside, so `api` and `runtime` are named
    // here directly: the specs landing in this directory wipe every session in OpenSearch, prune
    // every worker's undeployed non-current snapshots, and mutate `DeploymentService`'s static
    // deployment cache. The project still runs last, for the same reason `env` does rather than
    // because its subject belongs at the end.
    //
    // No broker project is named here, for the reason given at `env`; `brokers-restart` runs after this
    // project instead.
    //
    // `ui` is named, unlike `brokers`: the specs here delete every session in OpenSearch and prune
    // snapshots, both of which the browser scenarios read. The same gating cost applies.
    //
    // A project of its own, rather than these specs dropped into `specs/env/` where `workers: 1`
    // and the ordering after `api` and `runtime` already hold. Two reasons, and the first is the one
    // that decides it: inside `specs/env/` a spec would be scheduled *among* the restarts, so a
    // whole-catalog export could land while a container is being recreated. Here it runs strictly
    // after all of them. The second is that `specs/env/` is defined by a behavior — a spec belongs
    // there as soon as it calls `env.restartWith` — and nothing here restarts anything.
    //
    // `suite-teardown` waits for this project too, without gating either one; see its comment.
    name: "global",
    testDir: "./specs/global",
    workers: 1,
    dependencies: ["api", "runtime", "env", "ui"],
    teardown: "suite-teardown",
  },
  {
    // The teardowns of the seeds `global` does not depend on: `seed-micro`, `brokers-seed`, and
    // `brokers-micro-seed`. Each of those seeds names this project as its `teardown`, and so does
    // `global`, so it runs after all of them and after everything that depends on them. Playwright
    // gives a project one `teardown`, and `global` has to hold back two of these files. Measured:
    // the micro teardown ran during `env`'s catalog restart and three chain deletes failed with
    // `socket hang up`, and the broker teardown ran while `env` recreated the engine and the
    // catalog (`DEBUG=pw:test:task`). A `teardown` edge orders this project after `global` without
    // gating `global` on a broker or micro case, which naming `brokers` or `runtime-micro` in its
    // `dependencies` would do.
    //
    // It runs on both targets and whatever `E2E_BROKERS` says, so each file returns early unless
    // its seed wrote a state file in this run.
    name: "suite-teardown",
    metadata: { group: "setup" },
    testDir: "./specs",
    testMatch: /\/(seed-micro|brokers-seed|brokers-micro-seed)\/[^/]*\.teardown\.ts$/,
  },
];

/** The projects a run on `setup`'s target declares: the common ones, then its own. */
export function suiteProjects(setup: TargetSetup): SuiteProject[] {
  return [...suite, ...setup.projects];
}

export default defineConfig<PlaywrightTestOptions, PlaywrightWorkerOptions & SuiteWorkerOptions>({
  testDir: "./specs",
  // Provisioning brings the stack to this checkout before any project starts. A cold service costs
  // tens of seconds and a Maven build costs minutes, so neither may be charged to a test's timeout.
  // `E2E_PROVISION=never` reduces it to a health check, for a stack somebody else is debugging.
  globalSetup: "./env/provision.ts",
  // The run-token sweep, after every worker has finished. It removes what the per-worker folder
  // cascade cannot reach — services, specification groups, environments through their system, and
  // common and secured variables — and it cannot run inside a worker, because the token is shared.
  globalTeardown: "./support/fixtures.ts",
  // The JSON report carries this, and `npm run reconcile` reads each registry row against a run on
  // the row's own target.
  metadata: { target: target() },
  // A shared stack cannot absorb a retry that re-runs a half-applied scenario. A spec that needs to
  // tolerate timing waits for a condition instead, through expect.poll or expect.toPass.
  retries: 0,
  workers,
  timeout: 120_000,
  // 30 s rather than Playwright's 5 s or the 15 s this suite started with, for a 6-9 s gap between
  // `DEPLOYED` and a consumer attaching. A poll that needs more than this says so at its call site,
  // the way the session lookup does. The `ui` project needs no budget of its own: against the
  // bundle, a warm `/chains` shows its first row in 1.1-3.1 s at one worker and 2.3-6.2 s at eight.
  // The 27 s an earlier draft measured was the Vite dev server transforming modules on demand.
  expect: { timeout: 30_000 },
  // On CI the workflow builds the HTML report from the blob with `merge-reports`, which adds the
  // run's timeline to the Speedboard tab.
  reporter: [
    ["list"],
    process.env.CI ? ["blob"] : ["html", { outputFolder: "playwright-report", open: "never" }],
    ["json", { outputFile: "test-results/report.json" }],
    process.env.GITHUB_ACTIONS ? ["github"] : ["null"],
  ],
  use: {
    // Off the one table every addressed service is read from, so a port that moves moves once.
    baseURL: serviceUrl("runtime-catalog"),
    // Every request and response of a failed spec, and nothing at all on a green run. It replaces
    // `on-first-retry`, which `retries: 0` makes unreachable: without it a red spec yields the
    // assertion diff and the source line, and no request URL, response body, or engine log.
    trace: "retain-on-failure",
    // What a browser case showed when it failed, which the run page embeds.
    screenshot: "only-on-failure",
  },
  // The JSON report leaves `dependencies` out, and the run email names the project that kept another from running.
  projects: suiteProjects(targetSetup()).map((project) => ({
    ...project,
    metadata: { ...project.metadata, dependencies: project.dependencies ?? [] },
  })),
});
