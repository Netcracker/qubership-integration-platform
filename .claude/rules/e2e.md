---
paths:
  - "e2e/**"
---

### Project overview

`e2e/` holds the end-to-end suite. It runs against one of two local targets, which `CIP_TARGET`
picks: `compose`, the default, is the stack from `infrastructure/docker/docker-compose.yml`, and `k8s` is
a local cluster where the suite installs `infrastructure/helm/cip-dev` into namespace `cip-e2e`. It
answers one question before a release: does the platform still work. It produces a report; a person
reads the report and decides what to fix.

Stack: `@playwright/test` as the runner, TypeScript, ESM. The `request` fixture drives HTTP without
a browser, so API specs and browser specs share one runner and one report.

The module sits **outside the npm workspaces** on purpose. `ui-build.yaml` runs
`npm ci --legacy-peer-deps` at the repository root, so a workspace entry would pull Playwright and
its browser into every UI job and for every contributor who never runs these tests. `e2e/` keeps its
own `package.json` and lockfile, and `npm install` runs inside the directory.

`e2e/README.md` covers running the suite and reading its result: every command, flag, and state
file, provisioning, residue, and troubleshooting. This file holds what any work under `e2e/` needs.

**Before you write or change a spec, a fixture, a registry row, or the code under `support/`, load
the `e2e-spec-authoring` skill.** It holds the rules for a spec, the coverage registries, the shared
corpus, residue, and frozen fixtures, and its `browser.md` holds the rules for `specs/ui/`.

### Commands

```bash
npm run check-types          # type-check
npm test -- --project=schema --project=harness  # the specs that need no stack
npm test -- --project=<name> --no-deps -g "…"  # one case, against a corpus kept with E2E_KEEP=1
npm test; npm run reconcile  # a full run, then the registries checked against it
npx playwright test --list   # count the cases and the projects; starts nothing
```

`--no-deps` belongs on every `--grep` and every file path: a filter selects tests and never
dependency projects, so without it one tagged `env` case drags `api`, `runtime`, `seed`,
`seed-teardown`, and `tooling` in whole. Every runtime case reruns against a kept corpus; a case that
cannot is a case to fix, not to document.

The VS Code extension's integration tests are a sibling suite with their own runner, not Playwright
specs. A case that needs the `vscode` API goes under `vscode-extension/src/web/test/suite/`, never
under `specs/`. The suites meet on `vscode-extension/src/web/test/golden/`, which
`specs/api/extension-output.spec.ts` imports into the catalog, and two suites write it: the eight
files at its top level come from the extension's own integration suite, and the twenty-one under
`golden/import/` from the Jest cases in `vscode-extension/tests/web/import/`, which the integration
bundle never loads. Regenerate a golden in the suite that writes it.

### CI

`.github/workflows/e2e-k8s.yaml` runs the full suite on a kind cluster, nightly on `main` and on
manual dispatch. No pull request check runs it. `npm run check-types` and
`npx playwright test --project=schema --project=harness` need no stack, so run both before pushing
a change to `e2e/` or to a tree the two projects read:
`schemas/src/main/resources/conf-model/`, `schemas/src/test/resources/samples/`, `infrastructure/`,
and the import-file migration classes under `integration-build-pipeline/`. `harness` also holds
`specs/harness/title-rules.spec.ts`, which checks the describes, titles, and tags of every spec.
The rest of the suite provisions a Compose stack or a cluster namespace and expects to own it for
the length of the run.

The job's step summary comes from `support/run-summary.mjs`, and `support/run-page.mjs` writes the
run page, which the job uploads unzipped beside Playwright's HTML report. Every scheduled run, and
every dispatch with the `email` input, which is on by default, mails an HTML report that
`support/run-email.mjs` writes through the org email action to `vars.EMAIL_TO`, passed or failed,
with the verdict in the subject. All three render one model from `support/run-model.mjs`: they name
a test by its describe path and title joined with ` › `, count the product, setup, and suite
project groups apart, mark each failed test as new, intermittent, or still failing from the
`e2e-outcomes` artifacts of earlier runs on the branch, which `support/run-history.mjs` reads, and
list a `test.fail()` case that passed apart, as a pin to remove. The email gives each failed test
the command that reruns it alone.
The summary also reads `coverage.json`, which `npm run reconcile` writes, and the `resource-peaks`
and `metaspace` attachments of `specs/env/process-envelope.spec.ts`.

### Provisioning

`npx playwright test` brings the stack to this checkout before any project starts. It starts what is
missing or unhealthy, rebuilds a service whose sources changed since its image was built,
and never recreates a running container that is already current, because a developer may be
debugging on the same stack.

- **Rebuilding takes two commands.** The service Dockerfiles copy a jar the host already built
  (`runtime-catalog/Dockerfile:24`), so `mvn install` runs first and `docker compose up -d --build`
  second. `--build` on its own repackages whatever sits in `target/` and compiles no Java.
- **Configuration recreates rather than rebuilds.** A change to `infrastructure/docker/docker-compose.yml`
  or to an env file a service reads is compared against the **container's** creation time rather
  than the image's, and answered with `up -d --force-recreate`; the report names the two apart. A
  change under `infrastructure/docker/nginx/` is read by a proxy reload instead, since that directory is
  bind-mounted.
- **Every created or recreated container is followed by `Env.reloadProxy()`.** nginx resolves
  `proxy_pass http://engine:8080` once, at config load, with no `resolver` in scope, so a recreated
  backend leaves the proxy pointing at an address Docker has since handed to another container.
  `restartWith` reloads the proxy itself for the same reason.
- **A selection that needs no stack skips provisioning.** Playwright runs `globalSetup` once per run
  and has no flag that skips it, so `globalSetup` returns before it mints a run token when every
  selected project declares `metadata: { stack: false }` in `playwright.config.ts`. The selection
  is read off `config.argv`, because `config.grep` serializes to `{}` and `config.projects` lists
  every declared project. Without that return, `--project=schema --project=harness` could not run on
  a machine with no Docker.

On `k8s` the suite provisions the namespace, never the cluster; `e2e/k8s/bootstrap-cluster.sh`
prepares the cluster. The cluster versions are pinned in the script and in `e2e/k8s/README.md`.
`infrastructure/helm/cip-dev/README.md` also pins Gateway API and Istio, and
`.github/workflows/e2e-k8s.yaml` pins kind and istioctl. Bump them in all four places together.
`env/provision/k8s.ts` checks the kube-context, the Gateway API CRDs, Istio, the camel-k operator,
and metrics-server in that order, and fails on the first one missing, naming the section of
`e2e/k8s/README.md` that installs it. It then builds the images, each tagged with a hash over its
own sources, and runs
`helm upgrade --install cip infrastructure/helm/cip-dev -n cip-e2e -f e2e/k8s/values.e2e.yaml`. A chart
change the suite needs goes in the chart as an optional value with a default that renders what it
rendered before, and `e2e/k8s/values.e2e.yaml` sets it.

**On Compose, provisioning does not start the broker overlays.** `Env.ensureOverlay(overlay)` brings
one up, composed with the base file under the same project name, when the `brokers-seed` project
needs it, and leaves a healthy overlay alone. On `k8s` the release installs the brokers as `cip-dev`
subcharts unless `E2E_BROKERS=0`, `ensureOverlay` waits for their rollout and host port when
`brokers-seed` or `brokers-micro-seed` calls it, and `removeOverlay` does nothing.
`Env.restartOverlay(overlay)` restarts the overlay's own broker container without recreating it, and
only `broker-restart.spec.ts` calls it. A spec reaches all three through the `env` fixture, never by
calling `docker` or `kubectl`.

### Layout

```text
e2e/
  playwright.config.ts   the projects both targets run, and the project list for the run
  env/                   index.ts (the Env interface), target.ts (reads CIP_TARGET),
                         target-setup.ts (each target's Env, provisioner, and projects),
                         compose.ts and k8s.ts (the two Envs), provision.ts (the globalSetup),
                         containers.ts (host ports per target, the broker ones included),
                         overlays.ts (the broker readiness probes both Envs share)
  k8s/                   the chart values for cip-e2e, the cluster bootstrap script, and the page
                         that installs a cluster
  registry/              what the suite claims to cover, read off the tracked schemas
  specs/schema/          no stack needed: the product's routes, Compose files, migrations, schemas
  specs/harness/         no stack needed: the suite's own code, registries, and lint rules
  specs/api/             parallel-safe
  specs/seed/            imports and deploys the shared corpus, and removes it again
  specs/tooling/         the testing service works as a tool, before runtime leans on it
  specs/runtime/         calls the seeded chains and asserts what they did
  specs/env/             restarts a service; one worker, after api, runtime and ui
  specs/brokers-seed/    imports and deploys fixtures/brokers/, outside the shared corpus
  specs/brokers/         one file per transport, plus the AsyncAPI, trigger-idempotency,
                         Kafka security (kafka-security.spec.ts), missing-target, and
                         broker-restart cases
  specs/global/          reads over the whole platform, or over state it shares; one
                         worker, after api, runtime, env and ui
  specs/ui-server/       builds and serves the UI bundle on 4200, then stops it
  specs/ui/              the browser layer, Chromium through nginx on 8080; imports `test`
                         from support/page-guard.ts, which fails a case on a page error
  specs/seed-micro/      k8s only: the micro copy of the corpus, on a micro domain of the run
  specs/brokers-micro-seed/
                         k8s only: the micro copy of fixtures/brokers/, on a micro domain of its own
  specs/runtime-micro-redeploy/
                         k8s only: micro cases that deploy their own chains, each file on a micro
                         domain of its own, one file at a time
  specs/k8s/             k8s only: custom resources, gateway routes, discovery, /e2e/ locations,
                         and a micro domain that a broken chain takes down
  pages/                 page objects for the UI, plus the shared TableView
```

`runtime-micro`, `brokers-micro`, `global-micro`, and `env-micro` have no directory of their own:
each runs files of its classic counterpart against the micro engine. Every micro project leaves out
the cases tagged `@classic-only`, and each such case names its reason in a `classic-only`
annotation; `e2e/README.md` lists the projects and explains the tag.

`playwright.config.ts` declares the projects both targets run, and `env/target-setup.ts` declares
the ones only one target runs and the broker projects `E2E_BROKERS` turns off, through
`TargetSetup.projects`. The project list and every `dependencies` list come from those two places
and nowhere else. The common list never names a target. A project or a spec file one target leaves
out goes in that target's `absent` list, which the report header prints with the reason.

### Isolation on a shared stack

One Postgres and one engine serve every worker, so specs split their data two ways.

- **Shared and read-only.** Fixture chains a seed step imports and deploys once per run. Templates
  carry `{{RUN}}` so two runs cannot collide on an HTTP trigger `contextPath`: the deploy is refused
  with 409 naming the chain that holds the path (#840), so a run without the token fails its own
  seed.
- **Mutable and per worker.** Anything a spec creates. Chains and folders go under a per-worker
  folder; deleting the folder cascades. Entities with no folder carry the run token in their name.

**Size a deploy poll on about 5 s.** `TasksScheduler.java:130` is `@Scheduled(fixedDelay = 2500)`,
but the body blocks inside `ConsulService.waitForDeploymentsUpdate()` for up to 20 s
(`ConsulService.java:50`), and Consul returns the moment the KV index moves. Measured over 15
deploys, the pickup took a median of 2.165 s and a maximum of 3.113 s, so 2.5 s is too short and
20 s over-provisions sixfold.

### Reading a failure

`retries: 0` also removes `on-first-retry`, the trigger trace capture is conventionally hung on, so
`trace: "retain-on-failure"` replaces it. Four attachments go with it, and `support/diagnostics.ts`
owns all four:

- the engine's own lines for the chain under test, selected by **chain id out of the MDC**
  (`[chain_id=…] [chain=…]`) rather than by a grep for the name, plus the unfiltered tail: a route
  that never started logs nothing under its own id, and the reason sits in the lines around it;
- what `Env.engineEvidence()` returns. On a micro project that is the domain pod's log from its
  start and its last lines, the previous container's log after a restart, the pod's description
  with its events, and the Integration, read while the pod still exists. A failed micro case also
  records its run in `.e2e-k8s-micro-failure`, and the micro teardowns and the end-of-run sweep then
  keep the run's entities, so the CI cluster dump still finds the micro pods;
- the session JSON, and a UI link at `/chains/{chainId}/sessions/{sessionId}`, since the session
  route is nested inside the chain in `ui/src/App.tsx` and `/sessions/{id}` alone opens nothing;
- when the spec found no session, an attachment saying which token it waited for.

`support/report.ts` runs **after** the run, loaded by `node --experimental-strip-types`, where a
`.js` specifier pointing at a `.ts` file resolves to nothing. Its runtime imports therefore name the
`.ts` file itself, and `allowImportingTsExtensions` in `tsconfig.json` lets `tsc` check it. Anything
holding a `TestInfo` belongs in `diagnostics.ts`.

The run header is written in `globalSetup`, the only place that survives Playwright clearing
`test-results/` at the start of a run. A report that does not say what it tested cannot be trusted.

### Related

- Design and rollout: issue #710.
- `specs/env/service-type-roundtrip.spec.ts` is the live check for the per-type service file format
  (#553).
