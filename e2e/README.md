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
- For the Kubernetes target, `kubectl`, `helm`, `istioctl` 1.31 or later, the `kind` or `k3d` CLI
  on those clusters, and a local cluster with Istio, the Gateway API CRDs, the camel-k operator,
  and metrics-server. [The Kubernetes target page](k8s/README.md) installs them.

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
outside `/api/` to that port. Before it, the `schemas-build` project builds `schemas/` when
`schemas/src/main` is newer than `schemas/assets`, `types`, or `dist`, because the UI imports
`schemas/dist` and `npm install` does not create it. The rebuild of the bundle takes about 75
seconds: `npm run build --ignore-scripts -w @netcracker/qip-ui` with `VITE_PRODUCTION_MODE=false`,
where `--ignore-scripts` skips the `prebuild` that would build the schemas again. The bundle is
stale when `ui/dist` was built outside the suite (the suite's own build leaves `ui/dist/.e2e-build`
behind), or when anything it is built from is newer: `ui/src`, `ui/public`, the `ui/.env*` files,
`schemas/src/main`, `help/docs`, or the lockfile.

A server already answering on 4200 is used as found and left running. A dev server started with
`npm -w @netcracker/qip-ui run dev` is a different artifact from the bundle, so the browser cases
then test the dev server. A preview an interrupted run left on 4200 is stopped first, because
`.e2e-ui-server.json` still records it; when the runner that started it is still alive, the setup
fails naming its pid instead.

The `vscode` project needs the same root `npm install` and the same Chromium, and no stack. Its
`vscode-server` setup builds the UI library and the extension when they are stale, then serves VS
Code for the web on port 3917 with the extension of this checkout. The first run downloads the
pinned VS Code build, a 54 MB archive, into the gitignored `vscode-extension/.vscode-test-web/`, so
it needs network access. [The `vscode` project](#the-vscode-project) covers the rest.

The two setups build one at a time: the bundle build of `ui-server` and the library and extension
builds of `vscode-server` each hold the lockfile `e2e/.e2e-build.lock`. Run together beside the
seed, they pushed the `vscode-server` setup past its 10-minute timeout on a 4-core CI runner.
Starting and waiting for the servers needs no lock. When the run includes `ui-server` and the
bundle is stale, `globalSetup` reserves the lock for `ui-server` before any worker starts, so the
bundle that the `ui` project waits on builds first. A setup that waits on a lock whose holder pid
has exited removes it and goes on.

`schema`, `harness`, `api`, `tooling`, `runtime`, `runtime-micro`, `runtime-micro-redeploy`, `k8s`,
`brokers`, and `brokers-micro` run with no browser installed. `env`, `global`, `global-micro`,
`env-micro`, and `brokers-restart` depend on `ui`, directly or through `global`, so `--project=env`,
`--project=global`, and `--project=brokers-restart` build the bundle and launch Chromium too, and
`vscode` launches Chromium as well. Set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` if your environment
downloads a browser you do not want.

The default Compose run also starts the four broker overlays and binds their host ports: `9092`
(Kafka), `5672` and `15672` (RabbitMQ), `8085` (the pub/sub emulator), and `2222` (SFTP), and
removes them when the run ends. The default Kubernetes run installs the same four brokers with the
release, on host ports `30992`, `30672`, `30085`, and `30022`. `E2E_BROKERS=0` skips all of them on
either target.

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
npm run test:compose   # the Docker Compose stack from infrastructure/docker/docker-compose.yml
npm run test:k8s       # a local Kubernetes cluster, namespace cip-e2e
```

`npm test` runs the Compose target when `CIP_TARGET` is unset. Every command below takes the variable
the same way, for example `CIP_TARGET=k8s npm test -- --project=runtime`.

Both targets run the same spec files, and no spec names its target. `env/target-setup.ts` gives each
target its `Env`, its provisioner, and the projects only it declares:

| Project | `compose` | `k8s` |
| --- | --- | --- |
| `schema`, `harness`, `api`, `tooling`, `ui`, `env`, `global` | yes | yes |
| `seed`, `seed-teardown` | the corpus, deployed to the classic engine | the same |
| `schemas-build` | build `schemas/` when its sources are newer, with no stack | the same |
| `ui-server`, `ui-server-teardown` | build and serve the UI bundle on 4200, then stop it | the same |
| `vscode-server`, `vscode-server-teardown` | build the UI library and the extension when their inputs are newer, serve VS Code for the web on 3917, then stop it, with no stack | the same |
| `vscode` | the extension's editors in VS Code for the web on 3917, one worker, with no stack | the same |
| `runtime` | `specs/runtime/` against the classic engine | the same |
| `suite-teardown` | the teardowns of `seed-micro`, `brokers-seed`, and `brokers-micro-seed`, after `global` | the same |
| `seed-micro` | not declared | a second copy of the corpus, deployed to a micro domain named `e2e-<run>-micro` |
| `runtime-micro` | not declared | `specs/runtime/` against the micro engine, minus the cases tagged `@classic-only` |
| `runtime-micro-redeploy` | not declared | `specs/runtime-micro-redeploy/`, one file at a time: micro cases that deploy their own chains to a micro domain of their own |
| `global-micro` | not declared | `live-exchanges.spec.ts` and `runtime-deployments.spec.ts` of `specs/global/` against the micro engine, one worker after `global` |
| `env-micro` | not declared | `restart-resilience.spec.ts` of `specs/env/` against the micro engine, whose pod the engine restart deletes, one worker after `global-micro` and `runtime-micro`, and after `brokers-restart` unless `E2E_BROKERS=0` |
| `brokers-seed`, `brokers`, `brokers-restart` | yes, unless `E2E_BROKERS=0`; `brokers-seed` runs after `seed`, since both deploy to the classic engine, and `brokers-restart` after `global` | the same |
| `brokers-micro-seed` | not declared | unless `E2E_BROKERS=0`, a second copy of `fixtures/brokers/` with addresses of its own, deployed to a micro domain named `e2e-<run>-brokers-micro` |
| `brokers-micro` | not declared | unless `E2E_BROKERS=0`, `specs/brokers/` against that copy, minus `broker-restart.spec.ts` and the cases tagged `@classic-only` |
| `k8s` | not declared | `specs/k8s/`: camel-k custom resources, public gateway routes, service discovery, the proxy's `/e2e/` locations, and a micro domain that a broken chain takes down |

That is twenty projects on Compose and twenty-eight on Kubernetes, three fewer on Compose and five
fewer on Kubernetes with `E2E_BROKERS=0`. The run header records the target and lists every project
the target leaves out, with the reason. It does not list the cases a micro project leaves out; see
"The classic-only cases" below.

**The Kubernetes target.** You install the cluster and what runs cluster-wide once, as
[the Kubernetes target page](k8s/README.md) describes, and the suite owns namespace `cip-e2e`. It
builds the images from the checkout, installs `infrastructure/helm/cip-dev` with
`e2e/k8s/values.e2e.yaml`, and waits until every service answers. Each service answers on a fixed
host port: 30080 for the proxy, 30091 for runtime-catalog, 30092 for the classic engine, 30093 for
sessions-management, and 30095 for the testing service. The brokers answer on 30992 (Kafka), 30672
(RabbitMQ), 30085 (pub/sub), and 30022 (SFTP).

Run one target at a time: both stacks together do not fit in 15.6 GiB. Stop the Compose stack and
its broker overlays before a Kubernetes run, and run `helm uninstall cip -n cip-e2e --wait` before a
Compose run. Each target keeps its own state files. The Kubernetes ones start with `.e2e-k8s-`
(`.e2e-k8s-corpus.json`, `.e2e-k8s-brokers-corpus.json`, `.e2e-k8s-runs.json`,
`.e2e-k8s-overrides.json`, `.e2e-k8s-micro-corpus.json`, `.e2e-k8s-micro-redeploy.json`,
`.e2e-k8s-brokers-micro-corpus.json`, `.e2e-k8s-micro-failure`, `.e2e-k8s-resources.json`,
`.e2e-k8s-samples.jsonl`), so a
sweep or a settings revert never reaches the other target.
`.e2e-ui-server.json` is shared, because the UI bundle is the same on both.

The chart's Consul keeps no volume. When its pod restarts, a corpus kept with `E2E_KEEP=1` loses its
logging properties and records no sessions, so seed it again:

```bash
CIP_TARGET=k8s E2E_KEEP=1 npx playwright test --project=seed
```

**The classic-only cases.** Every micro project (`runtime-micro`, `runtime-micro-redeploy`,
`brokers-micro`, `global-micro`, and `env-micro`) leaves out the cases tagged `@classic-only`. Each
such case carries an annotation saying why the micro engine cannot run it, for example that it
deploys a chain into the shared micro domain, whose pod then rolls. The micro case of such a deploy
goes in `specs/runtime-micro-redeploy/`, which deploys each file's chains to a micro domain of its
own through `tokenedDomain` and `deployToDomain` in `support/deployable.ts`. The run header does not
list these cases; `grep -rn '"@classic-only"' specs/` finds them, and
`npx playwright test --list --project=runtime-micro` lists what a micro project runs.

**Parity findings.** A case that passes on a classic project and fails on its micro twin is a
finding, pinned with `strikesAsKnown` and a `ConditionalDefect` that names the micro engine (rule 13
of the `e2e-spec-authoring` skill) and its issue.

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
npm test -- --project=schema          # the product's tracked files, no stack needed
npm test -- --project=harness         # the suite's own code and registries, no stack needed
npm test -- --project=api             # the specs that run in parallel
npm test -- --project=tooling         # the testing service, before runtime leans on it
npm test -- --project=runtime         # the seeded chains, called and asserted
npm test -- --project=env             # the specs that restart a service, one worker
npm test -- --project=global          # the reads no worker can scope, one worker, last
npm test -- --project=brokers         # kafka, rabbitmq, pub/sub and sftp
npm test -- --project=brokers-micro   # the same on the micro engine, k8s only
npm test -- --project=brokers-restart # the one spec that restarts a broker mid-run, after brokers and global, so api, runtime, ui, and env run first
npm test -- --project=ui              # the browser layer, against the bundle it serves on 4200
npm test -- --project=vscode          # the extension's editors in VS Code for the web on 3917, no stack
npm test -- --grep @engine --no-deps  # one backend component's cases, no browser
npm run report                        # open the HTML report of the last run
npm run release-check                 # everything, then the extension's integration tests
```

These commands sit beside the suite:

```bash
npm run reconcile           # write test-results/cases.md, then diff the registries against the run
npm run reconcile -- --only=vscode-messages  # the message registry alone, after --project=vscode
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

`.github/workflows/e2e-k8s.yaml` runs the full suite on a kind cluster, nightly on `main` and on
manual dispatch from the Actions tab. The dispatch input `workers` sets `E2E_WORKERS`; left empty,
the run uses the nightly value. The dispatch input `email-to` takes comma-separated addresses, and a
manual run mails the run email to those addresses only; left empty, the default, it sends no email.

Each project belongs to one of three groups, which every count below keeps apart. Product projects
run the product's cases. Setup and teardown projects seed, build, serve, or remove what the product
cases need: `seed`, `seed-micro`, `brokers-seed`, `brokers-micro-seed`, `schemas-build`,
`ui-server`, `vscode-server`, `tooling`, and every teardown project, `suite-teardown` included. Suite checks are
`schema` and `harness`, which test the suite's own files and code. A project declares its group as
`metadata: { group: "setup" }` or `metadata: { group: "suite" }` in `playwright.config.ts` or
`env/target-setup.ts`; a project that declares none is a product project. A failed case of any
group fails the run.

Start with the job's step summary, which `support/run-summary.mjs` writes. From the top, it holds:

- a verdict paragraph: the outcome, the counts of each group, and the run against the previous one,
  such as `Product cases: 751 passed, 1 failed, 61 did not run. Setup and teardown: 11 passed. Suite
  checks: 649 passed.` A failed setup project reads as an infrastructure failure that names the
  project and the cases it kept from running, rather than as product failures;
- how long the run and its provisioning took, and on how many workers;
- links to the run, the run page, the HTML report, the report with traces, and the run artifacts;
- up to 50 failures, grouped as New, Still failing, Intermittent, or, without history, Failed, and
  then the tests fixed since the previous run. Each failure is a collapsed block titled with its full
  name, its project, and its first error line. It opens on the first lines of the error, the
  `file:line`, and the command that reruns it;
- the pins to remove, and the errors raised outside any test;
- the failed product cases and the duration of the last ten runs, as two charts;
- collapsed: the coverage `npm run reconcile` recorded, the time each project took by group, the
  slowest tests, the peak memory, CPU, and Metaspace of each service against its band, and the
  projects left out on this target.

A full name is the describe path and the case title joined with ` › `, such as
`actuator health › engine › names no component as down`. The coverage line divides the covered
element rows by the rows in scope and counts the out-of-scope and shared rows beside them (see
[Coverage registries](#coverage-registries)), then gives the API operations covered and reached and
the VS Code message types covered out of those the extension handles. A pin to remove is a
`test.fail()` case that passed in a product project: the defect it pins no longer reproduces, so
remove the annotation and close the defect's issue. A summary that would pass 900 KiB drops the
slowest tests first, then the project tables, then the bodies of the failures.

Every run with a report uploads its outcomes as the `e2e-outcomes` artifact, kept for 30 days: the
failed and skipped tests, the run status, the product counts, the duration, when the run finished,
and the commit. Before it writes the summary, the job downloads that artifact from up to ten earlier
runs on the same branch. A failed test is new when no earlier run failed it, intermittent when an
earlier run failed it and the latest run that ran it passed, and otherwise failing since a given
run. The charts leave out a run whose outcomes were written before the status and the counts
existed.

The job uploads four reports. Two of them are single HTML files, uploaded unzipped, so neither
needs unpacking:

- `e2e-run-<run number>-<attempt>.html`, the run page that `support/run-page.mjs` writes: the
  verdict, the failures with their errors, rerun commands, and screenshots, the trend, the projects
  of each group with their times, the coverage, and the slowest tests. It loads nothing and follows
  the system's light or dark theme. It embeds at most 10 MiB of screenshots and names the ones it
  leaves out;
- `e2e-report-<run number>-<attempt>.html`, Playwright's HTML report, titled with the branch and the
  commit. On CI the suite writes a blob report, and `npx playwright merge-reports` builds the HTML
  report from it, which adds the run's timeline to the Speedboard tab. This single file holds no
  traces or attachments;
- the `e2e-playwright-report` ZIP archive: the same report with its traces and attachments, at the
  archive's root, so `npx playwright show-report e2e-playwright-report.zip` opens the downloaded
  file as it is;
- the `e2e-report` ZIP archive: `test-results/`, the `.e2e-k8s-` state files,
  `.e2e-ui-server.log`, and `.e2e-vscode-server.log`.

An unzipped artifact takes its name from the file, and a second upload under one name fails, so the
run number and the attempt are part of each file name.

Every scheduled run, passed or failed, mails a report to the addresses in the repository variable
`EMAIL_TO`. `support/run-email.mjs` writes it. The addresses reach `_e2e-kind.yaml` as a secret,
and `support/email-recipients.mjs` masks each one in the log before the setup steps run. It fails
the job when an entry is not an address, which needs one `@`, no spaces, and a dot in its domain.
The subject opens with the verdict, such as
`E2E nightly failed: 1 new, 1 still failing in api (main @ 7a393a8)`, and the inbox preview line
holds the verdict sentence. The message is 600 px wide and names every status in
words beside its color. It holds the verdict and how the failures compare with the previous run, the
product counts with a line for setup and teardown and one for suite checks, and links to the run,
the run page, the HTML report, the report with traces, the run artifacts, the cluster dump, and the
commit. A strip of up to ten cells shows how the last runs ended. Up to 20 failed tests follow, each
with its full name, how long it has been failing, the first lines of its error, and the command that
reruns it, and then the pins to remove. The message shows fewer failed tests when that keeps the
HTML part under 100 KB, where Gmail clips a message. A green run lists its projects by group
instead. The command names the project, the `file:line`, and a `-g` pattern built from the full
name:

```bash
npm run test:k8s -- --project=runtime --no-deps specs/runtime/http-echo.spec.ts:16 -g "seeded http-echo chain.*outlives the seed project and answers on its route"
```

The `file:line` alone is not enough, because the cases a loop or a function declares share one
line, and the title alone can repeat under another describe. Run the command against a kept corpus,
as [Reproducing one case](#reproducing-one-case) describes. A case of a project that needs no stack,
such as `vscode`, reruns without `--no-deps`, so its setup builds and starts the VS Code server.

A run whose only failures are pins to remove is titled "found fixed defects". The email also lists
the projects a failed project kept from running and the errors raised outside any test. When every
failed test also failed the previous run, the subject reads "still failing" instead of "failed".
A failed or cancelled run also uploads `e2e-cluster`: the workloads and events
of every namespace, a description of each pod in `cip-e2e`, the camel-k Integrations, and the logs
of those pods' containers, istiod, and the camel-k operator. The dump runs after the suite, so it
holds a micro domain's pod only because a failed micro case keeps the run's micro domains (see
[Reading a failure](#reading-a-failure)).

`.github/workflows/e2e-pr.yaml` runs three jobs on every pull request:

- `stack-free` runs `npm run check-types` and the `schema` and `harness` projects, and annotates
  each failed case at its line.
- `changes` lists the files the pull request's merge commit changes against its first parent, the
  base branch, with rename detection off, so a moved file lists its old path too.
  `support/pr-changes.mjs` matches them against the paths in its `JOB_PATHS` and prints
  `vscode=true` or `vscode=false`. The paths are `vscode-extension/`, `ui/`, `schemas/`, the root
  `package.json` and lockfile, the workflow itself, the package files of `e2e/`, and every file
  under `e2e/` that the `vscode` project's specs and setups load, which
  `specs/harness/pr-changes.spec.ts` checks.
- `vscode` runs when `changes` printed `vscode=true`: `--project=vscode --max-failures=1`, then
  `npm run reconcile -- --only=vscode-messages` after a green run. It caches
  `vscode-extension/.vscode-test-web/` under a key built from the hash of `support/vscode-server.ts`,
  so a new pin starts with an empty cache. A failed run uploads `e2e-playwright-report-vscode` and
  `e2e-report-vscode`, which holds `test-results/` and `.e2e-vscode-server.log`. The step summary
  ends with the result of the last scheduled `e2e-k8s.yaml` run on `main`.

A skipped `vscode` job counts as passed, which is why the workflow sets no `paths` filter: a
required check that never starts blocks the merge. A failed `changes` job skips `vscode` too, so a
branch protection that requires `vscode` requires `changes` as well. The nightly `e2e-k8s.yaml`
runs every project, `vscode` included, and the rest of the suite runs only there. Run the checks
before pushing, as `AGENTS.md` describes.

**Selecting cases.** Each spec file holds one top-level `test.describe` named after its subject, and
every tag comes from a closed list:

- a component: `@catalog`, `@engine`, `@sessions`, `@testing-service`, or `@infra`. The cases of
  the `ui`, `vscode`, `schema`, and `harness` projects carry none: the project is their component,
  and the HTML report already names it;
- `@slow`, for a case that needs more than 60 s;
- `@classic-only`, for a case the micro projects leave out;
- a broker: `@kafka`, `@rabbitmq`, `@pubsub`, or `@sftp`;
- an API version: `@v1`, `@v2`, or `@v3`.

A case inherits the tags of every describe above it, so `--grep @engine` selects one component's
cases. A file path or a `file:line` selects one file or one case:

```bash
npm test -- specs/api/health.spec.ts --no-deps
npm test -- --project=api -g "part of the title" --no-deps
```

Playwright matches `-g` against the describe titles and the case title, with each describe's tags
written after its title. A pattern that spans a describe and its case therefore joins the two with
`.*`: `-g "runtime-catalog.*reports UP"` selects a case of `specs/api/health.spec.ts`, and
`-g "runtime-catalog reports UP"` or `-g "runtime-catalog › reports UP"` selects nothing.

Put `--no-deps` on every `--grep` and every file path. A filter selects tests, never dependency
projects, so without the flag one matching `env` case drags `api`, `runtime`, `seed`,
`seed-teardown`, and `tooling` in whole. On `k8s`, name the project too: a file under
`specs/runtime/` matches both `runtime` and `runtime-micro`. Run the browser layer with
`--project=ui`. The `vscode` project is the exception: its dependencies need no stack and start
its server, so run it without `--no-deps` (see [The `vscode` project](#the-vscode-project)).

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

### The `vscode` project

`specs/vscode/` drives the extension's editors in VS Code for the web. Three projects run in a
chain, none of them needs a stack, and provisioning skips a selection of only these:

- `schemas-build` builds `schemas/` when `schemas/src/main` is newer than `schemas/assets`, `types`,
  or `dist`. `ui-server` depends on it too.
- `vscode-server` rebuilds what is stale, then serves the workbench on 3917. The UI library is
  stale when the marker `ui/dist-lib/.e2e-build`, which the setup writes after its two library
  builds, is missing or older than what the library is built from (`ui/src`, `ui/.env`,
  `ui/package.json`, the library's Vite and TypeScript configs, `schemas/dist`, and the root
  lockfile), and `build:lib:bundled` and `build:lib:types` rebuild it. `build:lib` empties
  `ui/dist-lib`, the marker included, and leaves no bundled file. The extension is stale when
  the library was rebuilt, or when one of `vscode-extension/src`, `package.json`,
  `webpack.config.js`, `media`, `ui/dist-lib`, `schemas/dist`, and `schemas/assets` is newer than
  the marker `vscode-extension/dist/.e2e-build`, which the setup writes after `compile-web`. The
  files in `dist` cannot date the build, because webpack leaves an unchanged asset unwritten. The server
  logs to `e2e/.e2e-vscode-server.log`, and `vscode-server-teardown` stops it.
- `vscode` runs the cases on one worker, each in a browser context of its own. The workspace is
  `fixtures/vscode/workspace/`. The server mounts it read-only and the workbench keeps every write
  in memory, so each case starts from the committed files and a run leaves them unchanged.

Run the project with its dependencies, never with `--no-deps`, which finds no server on 3917:

```bash
npm test -- --project=vscode
npm test -- --project=vscode specs/vscode/chain-editor.spec.ts -g "part of the title"
```

With nothing stale the setup takes about 3 s, a rebuild of the extension alone about 17 s, and a
cold start with both builds and the download 1.7 minutes. The whole project then takes about 7.5
minutes. Port 3917 is fixed, so run one `vscode` run at a time. The setup fails on a server that
another run still owns, naming its runner's pid, and on any server it did not start, which may
serve another checkout or another workspace. It stops a server an interrupted run left behind,
because `.e2e-vscode-server.json` still records it.

**The host pin.** `VSCODE_COMMIT` in `support/vscode-server.ts` pins the insiders build the project
runs against, and `E2E_VSCODE_COMMIT=<sha>` overrides it for one run. A new build is adopted by
changing the constant, which also changes the key of the pull request workflow's cache. The
workbench gives Quick Open and its editors no role a locator could use, so `pages/VsCodePage.ts`
finds them by their classes, and a build that renames them needs a change there only. The
extension's own integration suite keeps following the latest insiders build.

**A mutation check** (rule 17 of the `e2e-spec-authoring` skill) edits the extension under
`vscode-extension/src/web/response/`, or the UI under `ui/src/`, and runs the spec file with its
dependencies. `vscode-server` finds the edited source newer than its output and rebuilds: a UI edit
runs both library builds and then `compile-web`, and an extension edit runs `compile-web` alone.
The next page loads the new bundle. Undo the edit and run the file again: the setup rebuilds once
more, and the case passes.

## Debugging

- `npm run report` opens the HTML report of the last run, from `playwright-report/`. A failed case
  carries its trace there.
- `npx playwright show-trace test-results/<case directory>/trace.zip` opens one trace without the
  report. `test-results/` holds a directory per failed case, and also `report.json`, `stack.json`,
  and the `cases.md` that `npm run reconcile` writes.
- `npm run run-summary [-- <dir>]` writes the CI step summary to `summary.md` in `<dir>`, by
  default `test-results/`, so you can read it for a local run or a downloaded `e2e-report`.
  `npm run run-email [-- <dir>]` writes the run email beside it, to `email.html` and
  `email.txt`, and `npm run run-page [-- <dir>]` writes the run page, to `run-page.html`. Without
  the `GITHUB_*` variables the workflow sets, they carry no links. Set `E2E_HISTORY_DIR` to a
  directory holding one `<run id>/outcomes.json` per earlier run to see the history and the trend.
- `--debug` runs a case under the Playwright Inspector, one step at a time; `--headed` shows the
  browser of a `ui` case; `--ui` opens Playwright's UI mode. Combine each with `--no-deps` and a kept
  corpus, as above, so the seed does not run again.
- Set `E2E_KEEP=1` to skip teardown and pick a failure apart on the stack. Whatever the run created
  stays behind, named with the run token the failing spec prints.
- On `k8s`, a failed micro case does the same on its own: its run keeps the micro domains and every
  other entity, so `kubectl logs -n cip-e2e deploy/cip-engine-<domain>-v1 -c integration` still
  reads the pod. The next run's sweep removes them.

## Flags

`CIP_PROXY_URL` and the four service URLs point the suite at a stack that is not on localhost. The
defaults below are the Compose ports; on `k8s` they are the fixed host ports from
[Targets](#targets), the broker ports included.

| Variable                  | Default                              | What it does                                                                        |
| ------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------- |
| `E2E_PROVISION`           | `auto`                               | `never` checks health only and fails naming whatever is down. Any other value is rejected. |
| `E2E_WORKERS`             | `8`                                  | The worker pool, pinned rather than inherited from your cores.                      |
| `E2E_KEEP`                | unset                                | `1` skips teardown and leaves the corpus and every created entity behind.            |
| `E2E_SWEEP`               | unset                                | `never` stops a run from collecting a previous run's residue at its start.           |
| `E2E_RUN`                 | minted per run                       | The token entities are named after. Pass one to pin it; the shape is validated.      |
| `E2E_ELEMENT_SCHEMA_DIR`  | the tracked schemas                  | Points the coverage checks at another element-schema tree, which is how they are proved to still go red. |
| `E2E_VSCODE_COMMIT`       | `VSCODE_COMMIT` in `support/vscode-server.ts` | The VS Code insiders build `vscode-server` serves, by its commit (see [The `vscode` project](#the-vscode-project)). |
| `E2E_BROKERS`             | unset, meaning `1`                   | `0` drops `brokers-seed`, `brokers`, and `brokers-restart` from the run, and `brokers-micro-seed` and `brokers-micro` on `k8s`, for a faster local pass: Compose starts no broker overlay, and on `k8s` the release is installed without the brokers. |
| `E2E_BUILD`               | unset, meaning `all`                 | On `k8s`, the images built from the checkout: a comma list of `runtime-catalog`, `engine`, `sessions-management`, `testing-service`, and `micro-engine`, or `all`, or `none`. Every other image runs as its published `:latest` from GHCR, and the run header names each by the digest its pod runs. `all` beside other names is still `all`, `none` beside them adds nothing, and an unknown name fails the run. A run that selects a micro project fails unless the list builds `micro-engine`: the published micro image is a `prod` build the chart cannot start. On Docker Desktop, `docker pull` those `:latest` images first, because the node runs whatever copy the host already holds. |
| `E2E_BOOTSTRAP_LOG`       | unset                                | On `k8s`, the log of a cluster bootstrap still running in the background, which `_e2e-kind.yaml` starts under its `background-bootstrap` input. The run builds the images first, then waits for the exit code the workflow writes to `<log>.exit`, and checks the cluster only after that. A non-zero code fails the run with the log. |
| `CIP_TARGET`              | `compose`                            | `k8s` runs the suite against the local cluster in the current kube-context (see [Targets](#targets)). Any other value fails the run at config load. |
| `CIP_REPO_ROOT`           | the parent directory                 | The repository root everything else is resolved against.                             |
| `CIP_COMPOSE_FILE`        | `infrastructure/docker/docker-compose.yml`  | The compose file provisioning drives on the Compose target.                          |
| `CIP_PROXY_URL`           | `http://localhost:8080`              | The nginx front door, and the whole `/api/` surface.                                 |
| `CIP_CATALOG_URL`         | `http://localhost:8091`              | runtime-catalog, direct.                                                             |
| `CIP_ENGINE_URL`          | `http://localhost:8092`              | The engine, direct, and where a seeded chain is called.                              |
| `CIP_SESSIONS_URL`        | `http://localhost:8093`              | sessions-management, direct.                                                         |
| `CIP_TESTING_SERVICE_URL` | `http://localhost:8095`              | The testing service, direct.                                                         |
| `CIP_KAFKA_URL`           | `localhost:9092`, `localhost:30992` on `k8s` | Where a broker spec's own `kafkajs` client connects from the host. The chain fixtures address the in-network `kafka:29092` instead. |
| `CIP_RABBITMQ_URL`        | `amqp://guest:guest@localhost:5672`, port `30672` on `k8s` | Where a broker spec's own `amqplib` client connects from the host. |
| `CIP_PUBSUB_URL`          | `http://localhost:8085`, port `30085` on `k8s` | The pub/sub emulator, from the host.                                   |
| `CIP_SFTP_HOST`           | `localhost`                          | The SFTP server, from the host.                                                      |
| `CIP_SFTP_PORT`           | `2222`, `30022` on `k8s`             | The SFTP server's port, from the host.                                               |

Six more exist for the suite's own tests rather than for a person: `E2E_RUN_MANIFEST`,
`E2E_OVERRIDES_FILE`, `E2E_RESOURCES_FILE`, `E2E_SAMPLES_FILE`, `E2E_SAMPLE_INTERVAL_MS` and
`E2E_SAMPLE_MAX_MS` move the state files and retune the resource sampler.

## Reading the result

`test-results/cases.md`, written by `npm run reconcile`, opens with the run header: what the run
tested, what it left out, and how it ended. Then comes one table per component tag, with a row per
test under its full name, what it declared it covers, and the trace of anything that failed. An
illustrative header of a Kubernetes run:

```text
- Target: k8s
- Not run on this target: brokers-seed (E2E_BROKERS=0), brokers (E2E_BROKERS=0), …
- Run token: `fk9dz2`
- Commit: `3f1c2a9b7d10` on `main`
- Started: 2026-09-24T09:12:40.118Z
- Workers: 8
- Helm release: cip in cip-e2e, revision 4, deployed, chart cip-k8s-dev-…, kube-context docker-desktop
- Provisioning: mode=auto, 81.2 s, rebuilt qip-engine

| Service | Version | Built | Image | Image created |
…

Wall time 768.4 s: 935 passed, 0 failed, 0 skipped.
```

"Not run on this target" is the `absent` list: a project this target leaves out, with the reason.
A case that did not run is not a case that passed, so read the passed, failed, and skipped counts
off the last line. Unlike the step summary, which counts each project group apart, this line counts
every project in one sum.
`test-results/report.json` holds the same run in machine-readable form.

### Reading a failure

A failed spec attaches these beyond the assertion diff, in the HTML report under the test:

- **`trace`**: every request and response the spec made. It is kept on failure, because
  `retries: 0` makes the usual `on-first-retry` trigger unreachable.
- **`engine.log`**: the engine's own lines for the chain the spec addressed, since the moment the
  test started, plus the unfiltered tail. It separates "the platform regressed" from "the route
  never started". A case on a micro project, `seed-micro` included, attaches `micro-engine.log`
  instead.
- **The micro domain's own state**, on a micro project only, read while the pod still exists:
  `micro-pod.log` holds the first 256 KiB (262144 bytes) of the pod's log, where the engine logs
  its startup, and its last 1500 lines; `micro-pod.previous.log` holds the previous container's
  log after a restart; `micro-pod.describe.txt` is the pod's description with its events; and
  `micro-integration.yaml` is the Integration with its status.
- **`session-<id>.json`** and a link into the UI, for the trace a person reads as a diagram. When
  the spec found no session, the attachment says which token it waited for; that absence is itself
  the finding.

When the routes of a deploy never all answer, the failure lists what each route still failing
answered over the whole wait, as a count per status, such as `503 x12, 500 x31, 405 x29`. A route
that flips between a serving and a failing status shows both, where the last poll shows only one.

A failed micro case also writes its run token to `.e2e-k8s-micro-failure`. The `seed-micro` and
`brokers-micro-seed` teardowns and the end-of-run sweep then keep the run's entities, as
`E2E_KEEP=1` does, so the CI cluster dump still finds the micro pods.

A browser case also holds a DOM snapshot and a screenshot for each step. A failure from the page
guard names the route the page was on and the first problem it caught: an uncaught exception, a
`console.error`, or an `/api/` request that answered 4xx or 5xx. When the `ui-server` setup itself
fails, `e2e/.e2e-ui-server.log` holds the whole `vite preview` log.

A `vscode` case fails on an uncaught exception or a `console.error` in any frame: a webview, the
workbench, or the extension host, whose errors the workbench prints in the top frame as
`[Extension Host] …`. The failure lists every such problem, and also every error the case declared
with `expectConsoleError` that never arrived. An error allowed with `allowConsoleError` passes
whether or not it arrives. The one error every page load reports, the 404 for
`/static/devextensions/package.nls.json`, is allowed in `support/vscode-guard.ts`. The guard also
answers every request to a host other than `localhost` and `*.localhost` with an empty 200, since
the workbench asks the internet for its experiment assignments and its chat and Marketplace
manifests, and a closed connection would log an error. `e2e/.e2e-vscode-server.log` holds the
server's log, and a setup whose workbench never answers quotes its last 20 lines.

### Known defects pinned with `test.fail()`

A known divergence is pinned with `test.fail()` and a comment naming the defect, so the run stays
green while the defect stands. `grep -rn "test\.fail(" specs/` lists them. A pinned case
turns red the day its defect is fixed, and that is the signal to remove the annotation.

A fix removes the defect's pin, or the `expectConsoleError` call that declares its error, since the
case that then passes is the regression case.
A handler added for an `unhandled` type also changes its row, and
`specs/harness/vscode-messages.spec.ts` fails until the row matches the code: a handled row when
the extension answers the type, and no row when the UI stops sending it.

A defect only one engine meets, or a race, is pinned with `strikesAsKnown` and a `ConditionalDefect`
instead, defined in `specs/<project>/<component>-defects.ts`; `grep -rn strikesAsKnown specs/` lists
them. Such a case passes once its defect is fixed rather than turning red, so removing the pin is a
manual check against the open findings.

The `list` reporter marks a pinned case with ✘ when it fails as expected, the same mark a real
failure gets, and without color the two look identical. The summary line tells them apart: an
expected failure counts as passed, and a real one is counted as failed and gets a numbered entry
below the list.

### Red runs that were not regressions

Three kinds of red have been seen without a regression behind them. The first two are platform
races, and the cause of the third is not known. Check here before you bisect.

- **`specs/api/import-export.spec.ts`, three times in eleven full runs.**
  `GET /v1/catalog/import-instructions` answers 500 with `Cannot load from object array because
  "values" is null` when a sibling case deletes a row while the listing is mapping it. The endpoint
  reproduces it under concurrent load alone, so if a different spec reading the same listing goes
  red, it is this entry and not a new finding.
- **`specs/api/deployments.spec.ts`, twice.** One sample in the redeploy window answered
  `500 CIP-0001`, once in three local full runs, and one answered 500 with the request body echoed
  back, in CI run 39771885404. The case tolerates up to ten samples of either shape and refuses
  every other non-200, so a red there means a status the window does not produce, or a window far
  wider than the one measured.
- **`specs/brokers/files.spec.ts` on `brokers-micro`, twice.** The case `sftp › sftp-trigger-2 › a
  dropped file records a session found by chain id` found no session within 20 s, in CI runs
  39720893673 and 39777664543. It passed on the classic engine in both runs, and on the micro engine
  in the run after the first.

A red that is none of these three is a finding. Read it with the attachments above rather than by
re-running.

## Troubleshooting

- **The run is slow, or `specs/env/process-envelope.spec.ts` reports a restarted service.** Docker
  has less memory or fewer CPUs than the stack needs, and a service was killed and restarted. Give
  Docker more, or run fewer workers with `E2E_WORKERS=4`.
- **A container cannot start because a port is taken.** The Compose stack binds 8080, 8091, 8092,
  8093, 8095, 5432, 8500, and 9200, the broker overlays bind the ports under
  [Prerequisites](#prerequisites), `ui-server` binds 4200, and `vscode-server` binds 3917. Stop
  whatever holds the port. On
  kind, a broker port that does not answer was not mapped into `kind-qip` when it was created;
  recreate the cluster as [the Kubernetes target page](k8s/README.md) describes.
- **`[provision] mvn install` fails.** Fix the build first: run
  `mvn -pl <module> -am install -Dgpg.skip=true` at the repository root with JDK 21 and read its
  error. The run tests nothing until the jar builds.
- **A Kubernetes run fails before any case, naming a prerequisite.** The message names the section
  of [the Kubernetes target page](k8s/README.md) that installs it.

- **The browser cases test the wrong UI.** A dev server was already answering on 4200, and the suite
  used it as found. Stop it and run again.
- **`vscode-server` fails because port 3917 already answers.** The `vscode-test-web` of another
  run holds the port, or a server the suite did not start. Wait for the other run or stop the
  server, then run again.

## Provisioning

This section describes the Compose target. On `k8s`, provisioning builds the images and runs
`helm upgrade --install` instead, and the "What a run does in `cip-e2e`" section of
[the Kubernetes target page](k8s/README.md) lists its steps.

Before any project starts, the run brings the stack to this checkout and prints what it did:

```text
[provision] mode=auto, 128.2s
[provision]   mvn install: runtime-catalog, engine
[provision]   rebuilt: qip-runtime-catalog, qip-engine
[provision]   nginx reloaded
```

It starts what is missing or unhealthy and rebuilds a service whose sources changed since its image
was built. Each build tags the image with a hash over the contents of the service's sources, as in
`infrastructure-qip-engine:e2e-<hash>`, so a file whose content did not change rebuilds nothing. The
build then removes the hash tags of that service's earlier images, and Docker deletes an image left
with no tag and no container. A running container that is already current is never recreated, so a
stack you are debugging survives a run. An image you build by hand with `docker compose up --build`
carries no hash tag, so the next run rebuilds that service once. A change to
`infrastructure/docker/docker-compose.yml` or to an env file a service reads recreates the container
instead of rebuilding it, which costs seconds:

```text
[provision]   recreated for a configuration change: qip-engine
```

Every container the run creates, recreates, or starts is followed by an nginx reload, because the
proxy resolves `proxy_pass http://engine:8080` once, at config load. A change under
`infrastructure/docker/nginx/` reloads it too. `test-results/stack.json` records the commit, the version
and build time each service reports, the image every container runs, and what provisioning did; the
run header is rendered from it.

```bash
E2E_PROVISION=never npm test   # check health only, and fail naming whatever is down
```

Use it against a stack somebody else provisioned, or one you are debugging: nothing is built,
started, or recreated. A run whose `--project` selection needs no stack skips provisioning and
prints `[provision] skipped: the selected projects need no stack (<the selected projects>)`. Such a
selection names only `schema`, `harness`, `schemas-build`, `vscode-server`, `vscode`, or the
teardown of `vscode-server`, which declare `metadata: { stack: false }`.

## Broker overlays

Four optional Compose overlays sit beside the base stack: `docker-compose.kafka.yml` (a single
KRaft `kafka`; the file's `akhq` UI is not started), `docker-compose.rabbitmq.yml`,
`docker-compose.pubsub.yml`, and `docker-compose.sftp.yml`. The `brokers-seed` project brings up
the ones its fixtures need, under the base stack's project name so the containers share its
network, then imports and deploys `fixtures/brokers/`. A running overlay is left alone, and a broker
that fails to start fails the run at setup, naming the overlay. There is no compose command to run
by hand.

The broker fixtures are a corpus of their own, outside the shared one, because the engine's
pre-deploy connectivity checks would stall the whole corpus whenever a broker is down. The seed
imports the AsyncAPI services under `fixtures/broker-services/` before the chains that name them, and
creates the run's topics, queues, exchanges, dead-letter bindings, and pub/sub subscriptions. Teardown
deletes the run's topology and its `/upload/<run>` SFTP tree, then stops and removes the overlay
containers. A kept corpus (`E2E_KEEP=1`) keeps them, and so does a teardown that left residue.

On `k8s`, `brokers-micro-seed` deploys a micro copy of the same fixtures, whose topics, groups,
queues, exchanges, and subscriptions end in `-micro` and whose SFTP tree is `/upload/<run>-micro`,
so the two copies never consume each other's messages. A name a broker spec mints itself, such as
a verification consumer group, is built from the chain's own address.

On `k8s` the brokers are subcharts of `infrastructure/helm/cip-dev`, and provisioning installs them with
the release unless `E2E_BROKERS=0`. `brokers-seed` then only waits for each broker's rollout and
host port, and teardown leaves the brokers running, because they belong to the release. The broker
pods keep their data in an `emptyDir`, so a new pod starts empty. `brokers-restart` restarts the
Kafka and RabbitMQ containers in place by killing their PID 1, which keeps the pod and its data.

The engine runs with `CAMEL_PUBSUB_PREDEPLOY_CHECK_ENABLED=false` in
`infrastructure/docker/docker-compose.yml` and in `e2e/k8s/values.e2e.yaml`, because the pub/sub pre-deploy
check always reaches the real Google endpoint rather than the emulator. So
`specs/brokers/missing-target.spec.ts` covers a missing Kafka topic and a missing RabbitMQ queue and
exchange, and a missing pub/sub topic is an uncovered gap. Its cases are `@classic-only`. The micro
engine runs its checks, the pub/sub one included, when its pod starts (#1086), and a failed check
stops the pod, so a missing target keeps the whole micro domain from starting instead of parking one
chain. `brokers-micro-seed` therefore creates every topic, queue, exchange, and subscription before
the deploy, as `brokers-seed` does.

`brokers-restart` holds `broker-restart.spec.ts` alone and runs one worker after `brokers`, and on
`k8s` after `brokers-micro` too, because restarting a broker container disturbs every broker spec in
flight. A red `brokers-micro` case therefore skips the restart cases. It also runs after `global`,
and so after `env`, whose engine restart replaces the consumer a restart case waits on: in CI run
38651497943 the two overlapped and the Kafka case timed out. `env` and `global` wait for no broker
project, so the edge points from `brokers-restart`, and a red `global` case skips the restart cases.
On `k8s`, `env-micro` runs after `brokers-restart`, since it restarts the catalog.

`E2E_BROKERS=0` drops the broker projects for a faster local run. A release run keeps them.
`npm run reconcile` refuses a run narrowed that way rather than reporting every broker registry row
as having lost its test.

## Residue from a previous run

Three endings leave a corpus behind: `E2E_KEEP=1`, a failed micro case on `k8s`, and a run that
dies without finishing, such as a `kill -9`, a closed terminal, or a second Ctrl-C. One Ctrl-C still runs the teardown. The next run
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
- **Broker residue.** The sweep does not read `.e2e-brokers-corpus.json` or
  `.e2e-k8s-brokers-corpus.json`. A run's topics, queues, exchanges, subscriptions, and
  `/upload/<run>` SFTP tree come down only in the teardown of the run that created them, so a
  `brokers-seed` run that dies before its teardown leaves them to be cleared by hand. The same holds
  for the micro copy that `brokers-micro-seed` records in `.e2e-k8s-brokers-micro-corpus.json`,
  which a failed micro case also keeps, and whose micro domain the sweep does delete.
- **A `vite preview` on 4200.** The next run's `ui-server` setup stops it, finding it in
  `.e2e-ui-server.json`.
- **A `vscode-test-web` on 3917.** The next run's `vscode-server` setup stops it, finding it in
  `.e2e-vscode-server.json`.

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
docker compose -f ../infrastructure/docker/docker-compose.yml up -d --force-recreate qip-runtime-catalog
rm .e2e-overrides.json
```

## Layout

```text
env/        the seam between a spec and whatever runs the platform: target.ts reads CIP_TARGET,
            target-setup.ts gives each target its Env, provisioner, and projects, and
            compose.ts and k8s.ts are the two Env implementations
k8s/        the e2e chart values, the cluster bootstrap script, and the page that installs a
            local cluster
fixtures/   the chains the seed imports, the axis chain generator, the API documents a spec
            imports as a specification, and the frozen import corpus; services/ holds one
            directory per service the seeds import, and the catalog export
            specs/api/service-roundtrip.spec.ts imports; broker-services/ holds the AsyncAPI
            services the broker seeds import; vscode/ holds the workspace VS Code opens and
            the goldens of the files the extension writes
registry/   what the suite claims to cover, and the checks that keep the claim honest
support/    the service clients, the corpus assembler, the chain builders, the report, the page
            guard every browser spec imports, the UI bundle server, the schemas build, the VS
            Code server, the guard and message recorder every vscode spec imports, the paths
            the pull request workflow reads, and the release-check runner
specs/
  schema/       no stack: the product's routes, Compose files, migrations, and schemas
  harness/      no stack: the suite's own code, the registries, and the lint rules over the specs
  api/          parallel-safe specs
  seed/         imports and deploys the shared corpus, and removes it again
  tooling/      the testing service works as a tool, before runtime leans on it
  runtime/      calls the seeded chains and asserts what they did
  env/          specs that restart a service, one worker, after api, runtime, and ui
  brokers-seed/ waits for the brokers, starting the Compose overlays, deploys fixtures/brokers/,
                then removes it
  brokers/      kafka, rabbitmq, pub/sub and sftp, called and asserted
  global/       reads over the whole platform or over state it shares, one worker, after env
  schemas-build/
                builds schemas/ when its sources are newer
  ui-server/    builds and serves the UI bundle on 4200, then stops it
  vscode-server/
                builds the UI library and the extension, serves VS Code for the web on 3917,
                then stops it
  ui/           the browser layer, Chromium through nginx on 8080
  vscode/       the extension's editors in VS Code for the web; imports `test` from
                support/vscode-guard.ts, which fails a case on an error in any frame
  seed-micro/   k8s only: deploys the micro copy of the corpus to a micro domain, then removes it
  brokers-micro-seed/
                k8s only: deploys the micro copy of fixtures/brokers/ to a micro domain, then
                removes it
  runtime-micro-redeploy/
                k8s only: micro cases that deploy and redeploy, each file in a micro domain of its
                own, one file at a time
  k8s/          k8s only: custom resources, gateway routes, discovery, the /e2e/ locations, a
                micro domain that a broken chain takes down, and one redeployed under load
pages/      page objects for the UI and the shared TableView; selectors by role, test id, then title;
            VsCodePage for the workbench around the extension's editors
```

The split into projects is about scheduling rather than subject. `schema` and `harness` read files
off disk and finish in seconds, so a broken registry costs nothing to discover. `harness` is the one
split by subject: its tests check the suite rather than the product, so the run summary and the
email count it, with `schema`, among the suite checks. `env` restarts services, which
disturbs every spec in flight, so it runs after `api`, `runtime`, and `ui`. `global` runs one worker
after `api`, `runtime`, `env`, and `ui`, because its reads cover every worker's data and four of its
endpoints change state the others read. Both are `dependencies`, so a failed `ui` case skips `env`
and `global`. The broker projects are not dependencies of `env` or `global`: the teardown ordering
holds the broker corpus until `global` has finished, and `brokers-restart` depends on `global`, so a
failing broker case skips neither.
`runtime-micro`, `brokers-micro`, `global-micro`, and `env-micro` have no directory of their own;
each runs files of its classic counterpart against the micro engine.

The seed imports the services under `fixtures/services/` that the corpus chains call, context and
MCP services included, then every fixture under `fixtures/chains/`, `fixtures/axes/`, and
`fixtures/script/` as one archive, raises each chain's session logging so there is a trace to read
(except the large-body fixtures in `UNRECORDED_FIXTURES`, which stay at `OFF`), deploys them in
one batch, and waits until the routes answer. What it deployed lands in
`.e2e-corpus.json`, and the projects that depend on it read the chains from there.
`fixtures/axes/` is gitignored and rewritten on every seed.

## Coverage registries

Three registries under `registry/` hold what the suite claims to cover, and `npm run reconcile` checks
them against the run rather than believing them:

- `registry/elements.ts` has one row per element family and per axis value the schemas declare,
  and a `behavior` row for what an element does that no axis value selects, such as the retry of
  `service-call`. A row is `covered` only while a passing test declares it with `covers(...)` or
  `coversBehavior(...)`, and `not-covered` otherwise, with a reason. A `not-covered` row in one of
  a few named groups carries a scope and leaves the denominator: `out-of-scope`, as for JMS, which
  has no broker in `infrastructure/`, or `shared`, as for an idempotency mode of a
  broker trigger, whose shared template the `http-trigger` case proves. Reconcile and the run
  summary report the covered rows out of the rows in scope, with the other two counted beside them:
  `Elements: 204 of 290 registry rows in scope covered on k8s (151 out of scope, 15 shared).`
- `registry/operations.ts` has one row per API operation of the four services. A row is `reached`
  when a call to it was recorded, `covered` when a named assertion about it passed, and
  `not-reached` otherwise, with a reason. The two numbers are reported side by side and never
  summed.
- `registry/vscode-messages.ts` has one row per message type of the VS Code webview bridge. A row is
  `covered` when a passing `vscode` case recorded the type answered without an error, `not-covered`
  otherwise, with a reason, and `unhandled` for a type the UI sends and the extension has no handler
  for (#1214). `specs/harness/vscode-messages.spec.ts` reads the rows against the extension and UI
  sources, in both directions: a type `getApiResponse` handles with no row fails it, so does a type
  `ui/src` sends that no handler takes and no `unhandled` row names, and so does a row for a type
  neither side knows. A handled type nothing in `ui/src` sends stays `not-covered`, with a reason
  that starts `no UI sender`. Reconcile ignores what a case recorded for an `unhandled` row, since
  the extension answers it with no error and no payload. The run summary counts the covered rows
  out of the handled types, with the `unhandled` rows beside them:
  `VS Code messages: 64 of 70 handled message types covered, 2 unhandled.`

Run reconcile after the suite, never instead of it:

```bash
npm test; npm run reconcile
```

It reads a whole run or none. A run narrowed by `--project` or `--grep` is refused, and a run given
`--reporter=line` writes no `test-results/report.json` for it to read. A row only one target can
prove carries that target, so covering the whole registry takes a reconcile after a full run on
each target. `npm run reconcile -- --only=vscode-messages` checks the message registry alone, after a
run of exactly `--project=vscode`, and writes neither `cases.md` nor `coverage.json`. Execution
options such as `--workers` and `--max-failures` select no case, so the check accepts them too.

`npm run refresh-operations` rewrites `registry/operations.cache.json` from a running stack, and it
is the only thing that writes it. Point it elsewhere with `CIP_CATALOG_URL`, `CIP_ENGINE_URL`,
`CIP_SESSIONS_URL`, and `CIP_TESTING_SERVICE_URL`.

`fixtures/archives/frozen/` pins import compatibility, and nothing regenerates it. Run
`npm run frozen-checksums` only after a deliberate change to an archive, never to turn
`specs/harness/frozen-corpus.spec.ts` green.

## The extension's integration tests

The VS Code extension has its own integration suite, outside `e2e/`. Its specs are in
`vscode-extension/src/web/test/suite/`, and they run under mocha inside `@vscode/test-web`, because
they call the `vscode` API and need the extension's webpack build. They need no stack: the extension
reads and writes local files and calls no backend. They send the router the message a webview would
send, so the editors' webviews, and what they send, are covered by the `vscode` project instead.

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
  needs no display. It pins the host quality with `--quality insiders` in
  `vscode-extension/package.json`, which the `customEditorDiffs` proposal forces, and no commit, so
  every run resolves the current insiders build. The first run downloads it into the gitignored
  `vscode-extension/.vscode-test-web/`, which needs network access; later runs reuse that download
  until the insiders build rotates, and a rotation means a fresh download. A run takes about 25
  seconds. Re-pin `--commit <sha>` if a download fails. The `vscode` project pins its own build
  instead (see [The `vscode` project](#the-vscode-project)).
- It opens `vscode-extension/src/web/test/workspace/` as the workspace. The test host keeps every
  write in memory, so a run leaves those files unchanged.
- `vscode-extension-build.yaml` runs it in the `npm-build` job, after the Jest suite. `npm run
  release-check` runs it again locally, so a release check covers it without a pull request.

The suites share one contract: the catalog imports what the extension writes.
`specs/api/extension-output.spec.ts` imports the files in `vscode-extension/src/web/test/golden/`
into the catalog, and two suites write them — the eight at the top level come from this integration
suite, and the twenty-one under `golden/import/` from the Jest cases in
`vscode-extension/tests/web/import/`, which this suite never loads. So a change on either side of
the contract fails one of the suites, and a golden is regenerated in the suite that writes it.
