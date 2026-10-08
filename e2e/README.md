# End-to-end tests

Run the platform's end-to-end suite against a local stack, Docker Compose or a Kubernetes cluster,
and read the report it produces. The suite decides nothing on its own: it reports what passed and
what failed, and a person decides what to do about it.

This page covers running the suite and reading its result. The rules for writing or changing a spec
are in [`AGENTS.md`](AGENTS.md), generated from `.apm/instructions/e2e.instructions.md`.

- [Prerequisites](#prerequisites)
- [What a run changes on the stack](#what-a-run-changes-on-the-stack)
- [Targets](#targets)
- [Run](#run)
- [Debugging](#debugging)
- [Flags](#flags)
- [Reading the result](#reading-the-result)
- [Troubleshooting](#troubleshooting)
- [Provisioning](#provisioning)
- [Broker overlays](#broker-overlays)
- [Residue from a previous run](#residue-from-a-previous-run)
- [Layout](#layout)
- [Coverage registries](#coverage-registries)
- [The extension's integration tests](#the-extensions-integration-tests)

## Prerequisites

- Docker. Every number on this page was measured on one host with 12 CPUs and 15.6 GiB allocated
  to Docker; a smaller allocation makes a run longer without anything having regressed.
- Maven and JDK 21. Provisioning runs `mvn install` for a service whose image is stale, because the
  service Dockerfiles copy a jar the host has already built.
- Node 22 or newer.
- For the Kubernetes target, `kubectl`, `helm`, `istioctl`, the `kind` or `k3d` CLI on those
  clusters, and a local cluster with Istio, the Gateway API CRDs, the camel-k operator, and
  metrics-server. [The Kubernetes target page](k8s/README.md) installs them.

Install the suite inside `e2e/`, which sits outside the npm workspaces:

```bash
npm install
```

The `ui` project drives a real browser, which adds two steps. Download Chromium once, about 115 MB,
and again after a Playwright version bump:

```bash
npx playwright install chromium
```

Then run `npm install` at the repository root as well, because the `ui-server` project builds and
serves the UI bundle itself. It rebuilds the production bundle when it is stale, starts
`vite preview` on port 4200, and stops it at the end of the run; nginx on 8080 sends every request
outside `/api/` to that port. The rebuild takes about 75 seconds: it builds `schemas/dist` first,
because the UI imports it and `npm install` does not create it, then runs
`npm run build -w @netcracker/qip-ui` with `VITE_PRODUCTION_MODE=false`. The bundle is stale when
`ui/dist` was built outside the suite (the suite's own build leaves `ui/dist/.e2e-build` behind), or
when anything it is built from is newer: `ui/src`, `ui/public`, the `ui/.env*` files,
`schemas/src/main`, `help/docs`, or the lockfile.

A server already answering on 4200 is used as found and left running. A dev server started with
`npm -w @netcracker/qip-ui run dev` is a different artifact from the bundle, so the browser cases
then test the dev server. A preview an interrupted run left on 4200 is stopped first, because
`.e2e-ui-server.json` still records it; when the runner that started it is still alive, the setup
fails naming its pid instead.

`schema`, `api`, `tooling`, `runtime`, `brokers`, and `brokers-restart` run with no browser
installed. `env` and `global` depend on `ui`, so `--project=env` and `--project=global` build the
bundle and launch Chromium too. Set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` if your environment
downloads a browser you do not want.

The default Compose run also starts the four broker overlays and binds their host ports: `9092`
(Kafka), `5672` and `15672` (RabbitMQ), `8085` (the pub/sub emulator), and `2222` (SFTP), and
removes them when the run ends. `E2E_BROKERS=0` skips all of them.

## What a run changes on the stack

Run the suite only against a stack that is yours. Four endpoints the `global` project covers change
state the whole platform shares, and none of them takes an argument that narrows it:

- `POST /v1/catalog/maintenance/snapshots/prune` removes **every undeployed, non-current snapshot on
  the stack**, whoever built it. A deployed snapshot and a chain's current snapshot survive, so every
  chain still deploys and serves after a run, without the versions behind the one it is on.
- `DELETE /v1/sessions` removes every recorded session in OpenSearch.
- `POST /v1/catalog/domains/{domain}/deployments/update` rewrites the deployment cache the engine
  shares.
- `PATCH /v1/catalog/diagnostic/validations` re-runs the built-in rules over the whole catalog, so
  every chain's alert rows are deleted and saved again and each rule's last-run status is
  overwritten. The rules themselves are untouched.

Run one suite per stack at a time. The `env` project restarts shared containers and flips catalog
environment flags, so two runs against one stack cannot both be correct, and nothing enforces it.

## Targets

`CIP_TARGET` picks what the suite runs against: `compose`, the default, or `k8s`. Any other value
fails the run at config load, and the message names both.

```bash
npm run test:compose   # the Docker Compose stack from infrastructure/docker-compose.yml
npm run test:k8s       # a local Kubernetes cluster, namespace qip-e2e
```

`npm test` runs the Compose target when `CIP_TARGET` is unset. Every command below takes the variable
the same way, for example `CIP_TARGET=k8s npm test -- --project=runtime`.

Both targets run the same spec files, and no spec names its target. `env/target-setup.ts` gives each
target its `Env`, its provisioner, and the projects only it declares:

| Project | `compose` | `k8s` |
| --- | --- | --- |
| `schema`, `api`, `tooling`, `ui`, `env`, `global` | yes | yes |
| `seed`, `seed-teardown` | the corpus, deployed to the classic engine | the same |
| `ui-server`, `ui-server-teardown` | build and serve the UI bundle on 4200, then stop it | the same |
| `runtime` | `specs/runtime/` against the classic engine | the same |
| `seed-micro`, `seed-micro-teardown` | not declared | a second copy of the corpus, deployed to a micro domain named `e2e-<run>-micro` |
| `runtime-micro` | not declared | `specs/runtime/` against the micro engine, minus the classic-only files |
| `brokers-seed`, `brokers-seed-teardown`, `brokers`, `brokers-restart` | yes, unless `E2E_BROKERS=0` | not declared: the brokers have no chart |
| `k8s` | not declared | `specs/k8s/`: camel-k custom resources, public gateway routes, service discovery, and the proxy's `/e2e/` locations |

That is fifteen projects on either target, or eleven on Compose with `E2E_BROKERS=0`. The run header
records the target and lists every project and spec file the target leaves out, with the reason.

**The Kubernetes target.** You install the cluster and what runs cluster-wide once, as
[the Kubernetes target page](k8s/README.md) describes, and the suite owns namespace `qip-e2e`. It
builds the images from the checkout, installs `infrastructure/qip-dev` with
`e2e/k8s/values.e2e.yaml`, and waits until every service answers. Each service answers on a fixed
host port: 30080 for the proxy, 30091 for runtime-catalog, 30092 for the classic engine, 30093 for
sessions-management, and 30095 for the testing service.

Run one target at a time: both stacks together do not fit in 15.6 GiB. Stop the Compose stack and
its broker overlays before a Kubernetes run, and run `helm uninstall qip -n qip-e2e --wait` before a
Compose run. Each target keeps its own state files. The Kubernetes ones start with `.e2e-k8s-`
(`.e2e-k8s-corpus.json`, `.e2e-k8s-runs.json`, `.e2e-k8s-overrides.json`,
`.e2e-k8s-micro-corpus.json`, `.e2e-k8s-resources.json`, `.e2e-k8s-samples.jsonl`), so a sweep or a
settings revert never reaches the other target. `.e2e-ui-server.json` is shared, because the UI
bundle is the same on both.

The chart's Consul keeps no volume. When its pod restarts, a corpus kept with `E2E_KEEP=1` loses its
logging properties and records no sessions, so seed it again:

```bash
CIP_TARGET=k8s E2E_KEEP=1 npx playwright test --project=seed
```

**The classic-only runtime files.** `runtime-micro` leaves out 11 of the 26 files in
`specs/runtime/`, listed in `CLASSIC_ONLY_RUNTIME_FILES` in `env/target-setup.ts`. Each deploys
chains of its own through the classic deployment API, calls the classic engine directly, or starts
chains through the testing service, which calls the classic engine only.

**Parity findings.** A case that passes under `runtime` and fails under `runtime-micro` is a
finding, pinned with `strikesAsKnown` and a `ConditionalDefect` (rule 13 of `AGENTS.md`) and
recorded in `docs/product-defects.md`. The pinned micro-engine defect is that a step inside a
container is recorded with no parent, so an async branch's step can land under another step.

**What each target costs**, measured with the other target stopped:

| | `compose` | `k8s` |
| --- | ---: | ---: |
| Cases | 904 | 935 |
| Full run, against a stack already up | 12.4 min | 12.8 min |
| Full run, installing the release first | | 15.5 min |
| Installing the release, images already built | | 75.6 s |
| Micro readiness, `deploy-chains` until all 48 micro routes answer | | 62.7 to 75.9 s over nine runs |

The case counts date the table. `npx playwright test --list` starts nothing and prints the current
total and the projects it is spread over; give it the target, as in
`CIP_TARGET=k8s npx playwright test --list`.

## Run

```bash
npm test                              # everything
npm test -- --project=schema          # the registries and the fixtures, no stack needed
npm test -- --project=api             # the specs that run in parallel
npm test -- --project=tooling         # the testing service, before runtime leans on it
npm test -- --project=runtime         # the seeded chains, called and asserted
npm test -- --project=env             # the specs that restart a service, one worker
npm test -- --project=global          # the reads no worker can scope, one worker, last
npm test -- --project=brokers         # kafka, rabbitmq, pub/sub, sftp and the scheduler
npm test -- --project=brokers-restart # the one spec that restarts a broker mid-run
npm test -- --project=ui              # the browser layer, against the bundle it serves on 4200
npm test -- --grep @engine --no-deps  # one backend component's cases, no browser
npm run report                        # open the HTML report of the last run
npm run release-check                 # everything, then the extension's integration tests
```

Four more commands sit beside the suite:

```bash
npm run reconcile           # write test-results/cases.md, then diff the registries against the run
npm run refresh-operations  # rebuild registry/operations.cache.json from a running stack
npm run frozen-checksums    # rewrite fixtures/archives/frozen/CHECKSUMS after a deliberate change
npm run check-types         # type-check
```

Follow a full run with `npm run reconcile`, which writes the readable report and checks the
registries (see [Coverage registries](#coverage-registries)).

Run `npm run release-check` before a release. It runs `playwright test` with whatever arguments you
pass it, then the VS Code extension's integration tests. The extension suite runs even when the
first one failed, and the command exits non-zero when either did. The extension's results appear in
the console output only, and the command does not run `npm run reconcile`.

No CI job runs the suite. `npm run check-types` and `--project=schema` need no stack; run them
before pushing, as `AGENTS.md` describes.

**Selecting cases.** Every test carries a component tag (`@catalog`, `@engine`, `@sessions`,
`@testing-service`, `@ui`, `@infra`) and a tier: `@tier1` is the release floor and `@tier2` the
rest. `--grep` takes either, and a file path or a `file:line` selects one file or one case:

```bash
npm test -- specs/api/health.spec.ts --no-deps
npm test -- --project=api -g "part of the title" --no-deps
```

Put `--no-deps` on every `--grep` and every file path. A filter selects tests, never dependency
projects, so without the flag one matching `env` case drags `api`, `runtime`, `seed`,
`seed-teardown`, and `tooling` in whole. On `k8s`, name the project too: a file under
`specs/runtime/` matches both `runtime` and `runtime-micro`. Run the browser layer with
`--project=ui`, never with `--grep @ui --no-deps`, which selects the `ui-server` setup and teardown as
ordinary cases in no fixed order.

The worker count is pinned at 8 rather than derived from your CPU count, because what matters is the
load the stack can absorb. `E2E_WORKERS=4 npm test` overrides it.

### Reproducing one case

`--grep` without `--no-deps` re-runs the seed, then the teardown that destroys the corpus you wanted
to look at. Keep the corpus, then run the one case against it:

```bash
E2E_KEEP=1 npm test                                     # leaves the corpus deployed
E2E_SWEEP=never npm test -- --project=runtime --no-deps -g "part of the title"
```

`E2E_SWEEP=never` stops the next run from collecting the kept corpus at its start. Drop the flag when
you are done, and the next run collects it. Every runtime case reruns against a kept corpus.

A browser case needs the bundle on 4200 as well, which `--no-deps` does not start. After one run has
built the bundle, start a preview at the repository root, then run the case:

```bash
npm run preview -w @netcracker/qip-ui -- --port 4200 --strictPort   # at the repository root
E2E_SWEEP=never npm test -- --project=ui --no-deps -g "part of the title"
```

## Debugging

- `npm run report` opens the HTML report of the last run, from `playwright-report/`. A failed case
  carries its trace there.
- `npx playwright show-trace test-results/<case directory>/trace.zip` opens one trace without the
  report. `test-results/` holds a directory per failed case, and also `report.json`, `stack.json`,
  and the `cases.md` that `npm run reconcile` writes.
- `--debug` runs a case under the Playwright Inspector, one step at a time; `--headed` shows the
  browser of a `ui` case; `--ui` opens Playwright's UI mode. Combine each with `--no-deps` and a kept
  corpus, as above, so the seed does not run again.
- Set `E2E_KEEP=1` to skip teardown and pick a failure apart on the stack. Whatever the run created
  stays behind, named with the run token the failing spec prints.

## Flags

`CIP_PROXY_URL` and the four service URLs point the suite at a stack that is not on localhost. The
defaults below are the Compose ports; on `k8s` they are the fixed host ports from
[Targets](#targets).

| Variable                  | Default                              | What it does                                                                        |
| ------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------- |
| `E2E_PROVISION`           | `auto`                               | `never` checks health only and fails naming whatever is down. Any other value is rejected. |
| `E2E_WORKERS`             | `8`                                  | The worker pool, pinned rather than inherited from your cores.                      |
| `E2E_KEEP`                | unset                                | `1` skips teardown and leaves the corpus and every created entity behind.            |
| `E2E_SWEEP`               | unset                                | `never` stops a run from collecting a previous run's residue at its start.           |
| `E2E_RUN`                 | minted per run                       | The token entities are named after. Pass one to pin it; the shape is validated.      |
| `E2E_ELEMENT_SCHEMA_DIR`  | the tracked schemas                  | Points the coverage checks at another element-schema tree, which is how they are proved to still go red. |
| `E2E_BROKERS`             | unset, meaning `1`                   | `0` drops `brokers`, `brokers-seed`, `brokers-seed-teardown` and `brokers-restart` from the run, for a faster local pass that skips the broker overlays. |
| `CIP_TARGET`              | `compose`                            | `k8s` runs the suite against the local cluster in the current kube-context (see [Targets](#targets)). Any other value fails the run at config load. |
| `CIP_REPO_ROOT`           | the parent directory                 | The repository root everything else is resolved against.                             |
| `CIP_COMPOSE_FILE`        | `infrastructure/docker-compose.yml`  | The compose file provisioning drives on the Compose target.                          |
| `CIP_PROXY_URL`           | `http://localhost:8080`              | The nginx front door, and the whole `/api/` surface.                                 |
| `CIP_CATALOG_URL`         | `http://localhost:8091`              | runtime-catalog, direct.                                                             |
| `CIP_ENGINE_URL`          | `http://localhost:8092`              | The engine, direct, and where a seeded chain is called.                              |
| `CIP_SESSIONS_URL`        | `http://localhost:8093`              | sessions-management, direct.                                                         |
| `CIP_TESTING_SERVICE_URL` | `http://localhost:8095`              | The testing service, direct.                                                         |
| `CIP_KAFKA_URL`           | `localhost:9092`                     | Where a broker spec's own `kafkajs` client connects from the host. The chain fixtures address the container-internal `kafka:29092` instead. |
| `CIP_RABBITMQ_URL`        | `amqp://guest:guest@localhost:5672`  | Where a broker spec's own `amqplib` client connects from the host.                   |
| `CIP_PUBSUB_URL`          | `http://localhost:8085`              | The pub/sub emulator, from the host.                                                 |
| `CIP_SFTP_HOST`           | `localhost`                          | The SFTP overlay, from the host.                                                     |
| `CIP_SFTP_PORT`           | `2222`                               | The SFTP overlay's port, from the host.                                              |

Six more exist for the suite's own tests rather than for a person: `E2E_RUN_MANIFEST`,
`E2E_OVERRIDES_FILE`, `E2E_RESOURCES_FILE`, `E2E_SAMPLES_FILE`, `E2E_SAMPLE_INTERVAL_MS` and
`E2E_SAMPLE_MAX_MS` move the state files and retune the resource sampler.

## Reading the result

`test-results/cases.md`, written by `npm run reconcile`, opens with the run header: what the run
tested, what it left out, and how it ended. Then comes one table per component tag, with a row per
test, what it declared it covers, and the trace of anything that failed. An illustrative header of a
Kubernetes run:

```text
- Target: k8s
- Not run on this target: brokers (the brokers have no chart on the k8s target), …,
  runtime-micro: specs/runtime/metrics.spec.ts (classic-only: it deploys its own chains, …), …
- Run token: `fk9dz2`
- Commit: `3f1c2a9b7d10` on `main`
- Started: 2026-09-24T09:12:40.118Z
- Workers: 8
- Helm release: qip in qip-e2e, revision 4, deployed, chart qip-dev-…, kube-context docker-desktop
- Provisioning: mode=auto, 81.2 s, rebuilt qip-engine

| Service | Version | Built | Image | Image created |
…

Wall time 768.4 s: 935 passed, 0 failed, 0 flaky, 0 skipped.
```

"Not run on this target" is the `absent` list: a project or spec file this target leaves out, with
the reason. A case that did not run is not a case that passed, so read the passed, failed, and
skipped counts off the last line. `test-results/report.json` holds the same run in machine-readable
form.

### Reading a failure

A failed spec attaches three things beyond the assertion diff, in the HTML report under the test:

- **`trace`**: every request and response the spec made. It is kept on failure, because
  `retries: 0` makes the usual `on-first-retry` trigger unreachable.
- **`engine.log`**: the engine's own lines for the chain the spec addressed, since the moment the
  test started, plus the unfiltered tail. It separates "the platform regressed" from "the route
  never started".
- **`session-<id>.json`** and a link into the UI, for the trace a person reads as a diagram. When
  the spec found no session, the attachment says which token it waited for; that absence is itself
  the finding.

A browser case also holds a DOM snapshot and a screenshot for each step. A failure from the page
guard names the route the page was on and the first problem it caught: an uncaught exception, a
`console.error`, or an `/api/` request that answered 4xx or 5xx. When the `ui-server` setup itself
fails, `e2e/.e2e-ui-server.log` holds the whole `vite preview` log.

### Known defects pinned with `test.fail()`

A known divergence is pinned with `test.fail()` and its entry in `docs/product-defects.md`, so the
run stays green while the defect stands. `grep -rn "test\.fail(" specs/` lists them. A pinned case
turns red the day its defect is fixed, and that is the signal to remove the annotation.

The `list` reporter marks a pinned case with ✘ when it fails as expected, the same mark a real
failure gets, and without color the two look identical. The summary line tells them apart: an
expected failure counts as passed, and a real one is counted as failed and gets a numbered entry
below the list.

### Red runs that were not regressions

Three kinds of red have been seen without a regression behind them. The first two are platform races
filed in `docs/product-defects.md`; the third is a race in the suite's own code. Check here before
you bisect.

- **`specs/api/import-export.spec.ts`, three times in eleven full runs.**
  `GET /v1/catalog/import-instructions` answers 500 with `Cannot load from object array because
  "values" is null` when a sibling case deletes a row while the listing is mapping it. The endpoint
  reproduces it under concurrent load alone, so if a different spec reading the same listing goes
  red, it is this entry and not a new finding.
- **`specs/api/deployments.spec.ts`, once in three full runs.** One sample in the redeploy window
  answered `500 QIP-0001`. The case tolerates up to ten such samples and refuses every other
  non-200, so a red there means a status the window does not produce, or a window far wider than
  the one measured.
- **`specs/runtime/plain-elements.spec.ts`, once.** The `split-async-2` case read one async branch
  step as `IN_PROGRESS` where it asserts `COMPLETED_NORMALLY`: the lookup waits for eight elements
  rather than for their statuses. It was green on the next run and has not reproduced since.

A red that is none of these three is a finding. Read it with the attachments above rather than by
re-running.

## Troubleshooting

- **The run is slow, or `specs/env/process-envelope.spec.ts` reports a restarted service.** Docker
  has less memory or fewer CPUs than the stack needs, and a service was killed and restarted. Give
  Docker more, or run fewer workers with `E2E_WORKERS=4`.
- **A container cannot start because a port is taken.** The Compose stack binds 8080, 8091, 8092,
  8093, 8095, 5432, 8500, and 9200, the broker overlays bind the ports under
  [Prerequisites](#prerequisites), and `ui-server` binds 4200. Stop whatever holds the port.
- **`[provision] mvn install` fails.** Fix the build first: run
  `mvn -pl <module> -am install -Dgpg.skip=true` at the repository root with JDK 21 and read its
  error. The run tests nothing until the jar builds.
- **A Kubernetes run fails before any case, naming a prerequisite.** The message names the section
  of [the Kubernetes target page](k8s/README.md) that installs it.

- **The browser cases test the wrong UI.** A dev server was already answering on 4200, and the suite
  used it as found. Stop it and run again.

## Provisioning

This section describes the Compose target. On `k8s`, provisioning builds the images and runs
`helm upgrade --install` instead, and the "What a run does in `qip-e2e`" section of
[the Kubernetes target page](k8s/README.md) lists its steps.

Before any project starts, the run brings the stack to this checkout and prints what it did:

```text
[provision] mode=auto, 128.2s
[provision]   mvn install: runtime-catalog, engine
[provision]   rebuilt: qip-runtime-catalog, qip-engine
[provision]   nginx reloaded
```

It starts what is missing or unhealthy and rebuilds a service whose sources are newer than the image
its container runs. A running container that is already current is never recreated, so a stack you
are debugging survives a run. A change to `infrastructure/docker-compose.yml` or to an env file a
service reads recreates the container instead of rebuilding it, which costs seconds:

```text
[provision]   recreated for a configuration change: qip-engine
```

Every container the run creates, recreates, or starts is followed by an nginx reload, because the
proxy resolves `proxy_pass http://engine:8080` once, at config load. A change under
`infrastructure/nginx/` reloads it too. `test-results/stack.json` records the commit, the version
and build time each service reports, the image every container runs, and what provisioning did; the
run header is rendered from it.

```bash
E2E_PROVISION=never npm test   # check health only, and fail naming whatever is down
```

Use it against a stack somebody else provisioned, or one you are debugging: nothing is built,
started, or recreated. A run whose `--project` selection needs no stack, which today means `schema`
alone, skips provisioning and prints `[provision] skipped: schema needs no stack`.

## Broker overlays

Four optional Compose overlays sit beside the base stack: `docker-compose.kafka.yml` (a single
KRaft `kafka`; the file's `akhq` UI is not started), `docker-compose.rabbitmq.yml`,
`docker-compose.pubsub.yml`, and `docker-compose.sftp.yml`. The `brokers-seed` project brings up the ones its fixtures need, under the
base stack's project name so the containers share its network, then imports and deploys
`fixtures/brokers/`. A running overlay is left alone, and a broker that fails to start fails the run
at setup, naming the overlay. There is no compose command to run by hand.

The broker fixtures are a corpus of their own, outside the shared one, because the engine's
pre-deploy connectivity checks would stall the whole corpus whenever a broker is down. The seed also
creates the run's topics, queues, exchanges, dead-letter bindings, and pub/sub subscriptions. Teardown
deletes the run's topology and its `/upload/<run>` SFTP tree, then stops and removes the overlay
containers. A kept corpus (`E2E_KEEP=1`) keeps them, and so does a teardown that left residue.

The engine runs with `CAMEL_PUBSUB_PREDEPLOY_CHECK_ENABLED=false` in
`infrastructure/docker-compose.yml`, because the pub/sub pre-deploy check always reaches the real
Google endpoint rather than the emulator. So `specs/brokers/missing-target.spec.ts` covers a missing
Kafka topic and a missing RabbitMQ queue and exchange, and a missing pub/sub topic is an uncovered
gap.

`brokers-restart` holds `broker-restart.spec.ts` alone and runs one worker after `brokers`, because
restarting a broker container disturbs every broker spec in flight.

`E2E_BROKERS=0` drops the four broker projects for a faster local run. A release run keeps them.
`npm run reconcile` refuses a run narrowed that way rather than reporting every broker registry row
as having lost its test.

## Residue from a previous run

Two endings leave a corpus behind: `E2E_KEEP=1`, and a run that dies without finishing, such as a
`kill -9`, a closed terminal, or a second Ctrl-C. One Ctrl-C still runs the teardown. The next run
sweeps whatever is left at its start and reports what it removed:

```text
[sweep] run fk9dz2 left 16 entities, removed: chain e2e-fk9dz2-loop, …
```

`e2e/.e2e-runs.json` records each run's token and the process that minted it, and a run is collected
only once that process is gone, so a second run started by mistake never deletes the first one's
entities. A delete that failed is reported, and the run stays in the manifest so the next run tries
again. `E2E_SWEEP=never` turns the sweep off. The sweep also removes the sessions a run recorded in
OpenSearch and the endpoint mocks it created.

Some residue is not swept:

- **Test cases and test runs** on the testing service. The specs that create them delete them in
  each case's own `finally`, so only a run killed mid-case leaves `e2e-<run>-…` test cases behind.
  Delete them on the chain's Testing tab. The ones
  `specs/api/testing-service-portability.spec.ts` leaves reference invented chains that no Testing
  tab shows, so delete those with `DELETE /api/v1/test-cases`, which takes the IDs in its body.
- **Files in the engine container's `/tmp/chain_tmp`**, which the `file-write` and `xslt` fixtures
  write. A recreated engine container starts without them.
- **Broker residue.** The sweep does not read `.e2e-brokers-corpus.json`. A run's topics, queues,
  exchanges, subscriptions, and `/upload/<run>` SFTP tree come down only in the teardown of the run
  that created them, so a `brokers-seed` run that dies before its teardown leaves them to be cleared
  by hand.
- **A `vite preview` on 4200.** The next run's `ui-server` setup stops it, finding it in
  `.e2e-ui-server.json`.

### A service left running settings no file carries

`specs/env/service-type-roundtrip.spec.ts` restarts runtime-catalog with
`CIP_EXPORT_LEGACY_FORMAT=true` through a temporary Compose override. A run killed before its
teardown leaves the flag in force with nothing on disk saying so, and the image and configuration
files are both current. `e2e/.e2e-overrides.json` records such a restart, and the next run recreates
the service from the committed configuration and says what it cleared:

```text
[provision] clearing CIP_EXPORT_LEGACY_FORMAT, left on qip-runtime-catalog by run fk9dz2 at 2026-09-01T12:41:07.882Z
```

The run printing that line has already put the service back. To clean the stack by hand instead,
recreate the service first and delete the file second; deleting the file alone leaves the flag
running with nothing naming it:

```bash
docker compose -f ../infrastructure/docker-compose.yml up -d --force-recreate qip-runtime-catalog
rm .e2e-overrides.json
```

## Layout

```text
env/        the seam between a spec and whatever runs the platform: target.ts reads CIP_TARGET,
            target-setup.ts gives each target its Env, provisioner, and projects, and
            compose.ts and k8s.ts are the two Env implementations
k8s/        the e2e chart values and the page that installs a local cluster
fixtures/   the chains the seed imports, the axis chain generator, the API documents a spec
            imports as a specification, the frozen import corpus, and services/, the catalog
            export specs/api/service-roundtrip.spec.ts imports
registry/   what the suite claims to cover, and the checks that keep the claim honest
support/    the service clients, the corpus assembler, the chain builders, the report, the page
            guard every browser spec imports, the UI bundle server, and the release-check runner
specs/
  schema/       no stack: the registries against the tracked schemas
  api/          parallel-safe specs
  seed/         imports and deploys the shared corpus, and removes it again
  tooling/      the testing service works as a tool, before runtime leans on it
  runtime/      calls the seeded chains and asserts what they did
  env/          specs that restart a service, one worker, after api, runtime, and ui
  brokers-seed/ brings up the broker overlays and deploys fixtures/brokers/, then removes it
  brokers/      kafka, rabbitmq, pub/sub, sftp and the scheduler, called and asserted
  global/       reads over the whole platform or over state it shares, one worker, after env
  ui-server/    builds and serves the UI bundle on 4200, then stops it
  ui/           the browser layer, Chromium through nginx on 8080
  seed-micro/   k8s only: deploys the micro copy of the corpus to a micro domain, then removes it
  k8s/          k8s only: custom resources, gateway routes, discovery, and the /e2e/ locations
pages/      page objects for the UI and the shared TableView; selectors by role, test id, then title
```

The split into projects is about scheduling rather than subject. `schema` reads files off disk and
finishes in seconds, so a broken registry costs nothing to discover. `env` restarts services, which
disturbs every spec in flight, so it runs after `api`, `runtime`, and `ui`. `global` runs one worker
after `api`, `runtime`, `env`, and `ui`, because its reads cover every worker's data and four of its
endpoints change state the others read. Both are `dependencies`, so a failed `ui` case skips `env`
and `global`. The broker projects are not dependencies of `global`: its teardown ordering holds the
broker corpus until `global` has finished, without one failing broker case skipping it.
`runtime-micro` has no directory of its own; it runs `specs/runtime/` again.

The seed imports every fixture under `fixtures/chains/`, `fixtures/axes/`, and `fixtures/script/`
as one archive, raises each chain's session logging so there is a trace to read, deploys them in one
batch, and waits until the routes answer. What it deployed lands in `.e2e-corpus.json`, and the
projects that depend on it read the chains from there. `fixtures/axes/` is gitignored and rewritten
on every seed.

## Coverage registries

Two registries under `registry/` hold what the suite claims to cover, and `npm run reconcile` checks
them against the run rather than believing them:

- `registry/elements.ts` has one row per element family and per axis value the schemas declare. A
  row is `covered` only while a passing test declares it with `covers(...)`, and `not-covered`
  otherwise, with a reason.
- `registry/operations.ts` has one row per API operation of the four services. A row is `reached`
  when a call to it was recorded, `covered` when a named assertion about it passed, and
  `not-reached` otherwise, with a reason. The two numbers are reported side by side and never
  summed.

Run reconcile after the suite, never instead of it:

```bash
npm test; npm run reconcile
```

It reads a whole run or none. A run narrowed by `--project` or `--grep` is refused, and a run given
`--reporter=line` writes no `test-results/report.json` for it to read. A row only one target can
prove carries that target, so covering the whole registry takes a reconcile after a full run on
each target.

`npm run refresh-operations` rewrites `registry/operations.cache.json` from a running stack, and it
is the only thing that writes it. Point it elsewhere with `CIP_CATALOG_URL`, `CIP_ENGINE_URL`,
`CIP_SESSIONS_URL`, and `CIP_TESTING_SERVICE_URL`.

`fixtures/archives/frozen/` pins import compatibility, and nothing regenerates it. Run
`npm run frozen-checksums` only after a deliberate change to an archive, never to turn
`specs/schema/frozen-corpus.spec.ts` green.

## The extension's integration tests

The VS Code extension has its own integration suite, outside `e2e/`. Its specs are in
`vscode-extension/src/web/test/suite/`, and they run under mocha inside `@vscode/test-web`, because
they call the `vscode` API and need the extension's webpack build. They need no stack: the extension
reads and writes local files and calls no backend.

The build needs the npm workspaces installed at the repository root and the upstream bundles the
extension copies in, `ui/dist-lib/index.bundled.es.js` and `schemas/assets/`. On a fresh checkout,
and again after a change under `ui/` or `schemas/`, build them first; without them the extension leg
of `npm run release-check` fails with a build error rather than a test result:

```bash
npm install                                                    # from the repository root
npm run prepare-deps -w @netcracker/qip-vscode-extension       # schemas, then the ui library
npm run test:integration -w @netcracker/qip-vscode-extension
```

- The script compiles the extension, then starts VS Code for the web in headless Chromium, so it
  needs no display. It pins the VS Code build with `--quality insiders --commit <sha>` in
  `vscode-extension/package.json`. The first run downloads that build into the gitignored
  `vscode-extension/.vscode-test-web/`, which needs network access; later runs reuse it. A run
  takes about 25 seconds.
- It opens `vscode-extension/src/web/test/workspace/` as the workspace. The test host keeps every
  write in memory, so a run leaves those files unchanged.
- No CI workflow runs it. `vscode-extension-build.yaml` runs the extension's Jest suite only, so
  `npm run release-check` runs the integration suite after this one.

The two suites share one contract: the catalog imports what the extension writes. The extension
suite compares what it writes with the files in `vscode-extension/src/web/test/golden/`, and
`specs/api/extension-output.spec.ts` imports those files into the catalog, so a change on either
side fails one of the two.
