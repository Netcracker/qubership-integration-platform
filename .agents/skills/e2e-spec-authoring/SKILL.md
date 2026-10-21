---
name: e2e-spec-authoring
description: Write or change a spec, a fixture, a registry row, or the support code of the end-to-end suite under e2e/. Covers where a spec goes, how it asserts, how it reaches the shared corpus, what it cleans up, and how coverage is recorded. Use before editing anything under e2e/specs/, e2e/fixtures/, e2e/registry/, or e2e/support/.
---

# Writing an end-to-end spec

The `e2e` instruction covers the suite's layout, commands, provisioning, and data isolation, and is
loaded whenever you work under `e2e/`. This skill holds the rules for changing the suite. The rules
for `specs/ui/` are in `browser.md` beside this file.

## Projects and dependencies

**A flag lands in the config together with the project that reads it, never ahead of one.**
`CIP_TARGET` meets that rule: `target()` in `env/target.ts` reads it, `targetSetup()` turns it into
projects, and `playwright.config.ts` writes it into `metadata`, which the report and
`npm run reconcile` read.

`dependencies` is resolved transitively: a bare `--project=global` fires `seed`, then `runtime`, then
`global`, and a setup project's `teardown` runs after every transitive dependent. So a project that
reaches the corpus through a project declaring `seed` needs no edge of its own.

`dependencies` also gates a project on the success of the ones it names. That is why `global` names
no broker project: one failing broker case would skip every `global` case. Where only the order
matters, name a shared `teardown` project instead. Where a project must run after `env` or
`global`, the edge goes from that project to `global`, as `brokers-restart` does, so a red `global`
case skips it rather than a red case of it skipping `global`. A gate in the other direction is
accepted only on purpose and named in the project's comment: `brokers-seed` waits for `seed`, and on
`k8s` `env-micro` waits for `brokers-restart` (`env/target-setup.ts`). A project gets one
`teardown`, so the seeds `global` does not depend on (`seed-micro`, `brokers-seed`,
`brokers-micro-seed`) share one: `suite-teardown` collects their `*.teardown.ts` files, and they and
`global` all name it. It then runs after `global` without gating either side. It runs on both targets and in any selection that
names one of them, so a teardown file it collects returns early unless its seed wrote the state
file in this run.

`seed` declares `teardown: "seed-teardown"`, which names **another project**. Setup and teardown
behind one project's `testMatch` would make the teardown an ordinary test of the setup project,
running before the corpus had been used once. `testMatch` is not optional on either: Playwright's
default does not collect `seed.setup.ts`, and a setup project that runs zero tests reports success
and satisfies its dependents.

## Coverage registries

Two registries under `registry/` hold what the suite claims to cover, and both are checked against
the run rather than believed. A spec that covers something new is not finished until its row says
so, and the row moves in the same commit as the spec. `e2e/README.md` describes how reconcile is run
and what it refuses.

**`registry/elements.ts`: one row per element family and per axis value.** Every row starts
`not-covered` with a required reason. A row moves to `covered` only together with a spec that
declares it:

```ts
covers("http-trigger", "accessControlType", "RBAC");
```

`specs/schema/coverage.spec.ts` reads the schemas: an element or an axis value with no row fails it,
and so does a row naming a value the schemas no longer declare. `npm run reconcile` reads the run: a
row marked `covered` with no passing `covers()` behind it fails, and so does a declaration naming a
row that does not exist. A row only one target can prove carries `target: "compose"` or
`target: "k8s"`.

**The rows are read against the schema sources, not against a copy.** `registry/discriminators.ts`
resolves `schemas/src/main/resources/conf-model/element/` (`schemas/assets/` is generated), so a new
element family, or a new value on a discriminator axis, turns `specs/schema/coverage.spec.ts` red
until a row exists for it. Point it at another tree with `E2E_ELEMENT_SCHEMA_DIR` to confirm the
check still goes red.

**An element's axes are covered as a sum, never as a product.** Each case varies one axis value and
holds the rest at their defaults, so an element with ten two-value axes costs twenty cases rather than
1024. What a case may set besides the axis under test is fixed:

- a default the schema needs spelled out, because its `if` branches match an **absent** property:
  every generated trigger writes `accessControlType: NONE` and `handleChainFailureAction: default`;
- a companion the value cannot run without and no default supplies: `roles` under RBAC, `uri` on
  `http-sender`, `idempotency/enabled: true` under an `actionOnDuplicate` value;
- a single-value precondition that makes the branch reachable, such as the `afterValidation` entry a
  `service-call` response handler handles. It has no discriminator, so it is not a second axis.

A case setting two varying axes together names the defect that justifies it. Value unions
(`#{variable}` against a literal) are one class, covered once by `specs/runtime/placeholder.spec.ts`;
do not add a case per element.

The chains come from `fixtures/axis-generator.ts`. An `axisFixtures` entry names a family, an axis
path and a value, plus the `properties` template, and a `downstream` element where only a later step
reaches the branch. Generation refuses an axis or value the schemas do not declare and validates the
element and the chain against their schemas, so a missing companion fails there, naming the
property, and not at deploy. An axis that changes the chain's shape is written by hand under
`fixtures/chains/` and declared with `handWritten`; `writeAxisFixtures` refuses a hand-written
fixture that does not set the axis to the value. The element under test is named
`<axisPath>=<value>`, the `elementName` a session trace reports.

A runtime axis case asserts the response **and** the ordered `elementName` list of the session
trace, walked through `children[]`. A branch can answer the same status as the default: a handler
keeps the failure's status, and only the trace shows which handler ran. Children of a parallel
branch arrive in completion order, so compare them as a set. Find the session by a per-call token,
because `GET /v1/sessions/external-id/{id}` returns one session and the run token matches any
spec's. `byExternalId` waits for the number of steps, not for each step's status, so a case that
asserts step statuses or `failedElements()` reads the session through `Sessions.settled` first; a
message the broker can deliver more than once is read with `Sessions.earliestOf`. The exception is
a call that must not carry the correlation header, because the testing service would mock it, or
cannot, like an MCP tool call. Such a case finds the session through `Sessions.onlyOf` in
`support/sessions.ts`, by chain id and a filter that leaves out every session the case did not
cause (read `Sessions.idsOf` before the call), or by a marker the call carries in its body, so the
case still reruns against a kept corpus.

A row reads what a passing test proves, which gives three shapes besides `covered`:

- **Observable only on the compile.** When the engine ignores a value (`accessControlType` is
  `anyRequest().permitAll()` on the engine), assert the compiled `xmlDefinition` of the snapshot in
  the `api` project, tag it `@catalog`, and record the runtime gap in a comment above the rows.
  `GET /v2/catalog/snapshots/{id}/full` echoes the property back and proves nothing.
- **A defect.** A value that only a `test.fail()` case exercises stays `not-covered`, with the defect
  as its reason.
- **Superseded.** An uncovered row of an element the library flags `deprecated` carries
  `supersededReason()`, and `SUPERSEDED` maps it to its successor, which carries the case.

**`registry/operations.ts`: one row per API operation, and three states.** `not-reached` carries a
required reason, `reached` means a call to the operation was recorded, and `covered` means a named
assertion about its response shape, its side effect, or its failure path passed. The two numbers are
reported side by side and never summed: a sweep that calls every endpoint once proves only that the
platform still answers 200.

A row is one `METHOD /path` of one service, and the registry spans all four services the suite
addresses. A row cannot express a status per branch, so a branch a project cannot reach, such as the
`204` of `GET /v1/catalog/runtime-deployments`, is recorded in the spec's header and the row stays
whole. `npm run refresh-operations` reads four documents: the testing service serves Swagger 2.0 at
`/api/v1/swagger/doc.json`, where the three Java services serve OpenAPI at `/v3/api-docs`, and
`specs/schema/api-coverage.spec.ts` demands a cache key per service, so a refresh against a stack
missing one fails instead of dropping its rows.

`reached` is recorded by the run and never by the spec. `noteReached()` fires inside
`support/catalog.ts`, `support/sessions.ts`, `support/engine.ts` and `support/testing-service.ts`,
and inside the `request` fixture, which `recordingRequest` in `registry/reached.ts` wraps so that a
call a spec sends to a service's own port is matched the same way. `reconcile()` fails a
`not-reached` row a passing test reached. Go through the client anyway when the call is meant to
assert something, and give the client a `raw` method when what the case asserts is a status or a
failure body. Two kinds of call record nothing, and neither can carry a row's status: a request
through nginx, whose `/api/` path matches no row, and a call from `globalSetup` or `globalTeardown`,
where `test.info()` throws.

`specs/schema/api-coverage.spec.ts` diffs the registry against `registry/operations.cache.json` with
no stack, and `specs/api/api-docs-live.spec.ts` diffs that cache against what the services serve
right now, so a controller added to a service fails a run.

## Rules for a spec

1. **Assert over your own entities, never over a global list.** "The table holds three rows" breaks
   when a parallel worker adds a fourth. Filter the list by what this spec created.
2. **A spec that needs a global view, or reads global state, goes in `specs/global/`.** That project
   runs one worker after `api`, `runtime`, `env`, and `ui`: under a parallel `--project=api` run, an
   export of every chain answered 500 for 3 of 120 calls because another worker deleted a folder
   mid-export. One worker is **`workers: 1`, never `fullyParallel: false`**, which serializes the
   cases inside a file and still lets two files run side by side. The worker count bounds the project
   rather than the run, so the `dependencies` list is the half that empties the pool around it.
   These endpoint families are global by construction:

   | Endpoint | Why no spec can scope it |
   | --- | --- |
   | `GET /v1/library/elements/types` | the element types in use across every chain |
   | `GET/POST /v1/catalog/live-exchanges` | the top N exchanges on every engine, and `204` when there are none |
   | `GET /v1/catalog/runtime-deployments` | the deployment status of every chain on every engine pod |
   | `POST/GET/PATCH /v1/catalog/diagnostic/validations` | one run over the whole catalog, **destroying** every chain's alert rows and each rule's status row |
   | `GET /v1/catalog/events` | one change stream for the platform |
   | `DELETE /v1/sessions`, the bare form | removes every session; `POST /v1/sessions` takes a filter, so it is not global |
   | `POST /v1/catalog/domains/{domain}/deployments/update` | `DeploymentService` holds the deployment cache in `private static` fields |
   | `POST /v1/catalog/maintenance/snapshots/prune` | the delete carries no caller, chain, or run-token predicate |
   | `GET /v1/catalog/export` | takes no IDs, so it exports every chain |

   Global **state** counts the same as a global view.
3. **A spec that restarts a service belongs in `specs/env/`.** A restart costs about 30 s against
   0.15 s for an API assertion and disturbs everything in flight. Placement follows that scheduling
   need, not the spec's subject.
4. **Never name `docker` or `kubectl` in a spec, never construct an `Env` to reach a stack, and never
   name the target.** A spec asks for the `env` worker fixture and gets an `Env`, which
   `targetSetup().createEnv(engineKind)` in `env/target-setup.ts` creates for the target
   `CIP_TARGET` names. A fact that differs between the targets is an `Env` member the spec reads,
   such as `domainFacts()` or `generatedRequestId`, never a branch on `target()`; the difference
   lives in `env/compose.ts` and `env/k8s.ts`. Without that seam the suite cannot run on a cluster,
   and the micro engine exists only there. Two `specs/schema/` files construct an `Env` directly, and
   neither reaches a stack: `report.spec.ts` builds a `ComposeEnv` to read a URL off, and
   `k8s-adapter.spec.ts` builds `K8sEnv` instances to unit-test the adapter. `tokenedDomain` in
   `support/deployable.ts` builds the `K8sEnv` of a micro domain that a spec creates for itself
   (rule 20). `specs/k8s/` is the
   exception to the first half: its specs run on the Kubernetes target alone and read Integrations,
   ConfigMaps, HTTPRoutes, and Services through `support/kube.ts`, a `kubectl` reader that no spec
   outside `specs/k8s/` imports.
5. **Delete before importing.** An import over a live id is an update, so an assertion that the IDs
   are present passes whether the import created them, updated them, or did nothing.
6. **Import the original bytes.** `ArchiveWriter` builds `services/<id>/` unconditionally, and an
   archive whose entries sit at the ZIP root imports as 204 with an empty body. Re-zipping an
   unpacked tree turns a no-op import into a green run.
7. **No retries.** `retries: 0` is deliberate: a shared stack cannot absorb a re-run of a
   half-applied scenario. A spec that has to tolerate timing waits for a condition through
   `expect.poll` or `expect.toPass`.
8. **A spec asserts behavior, not the presence of a control.**
9. **A flaky spec is fixed or deleted, never skipped.** A skipped spec claims coverage it does not
   have. Fix it where it surfaces, whoever wrote the file, and fix it by asserting what the case is
   about rather than by polling harder.
10. **Put a file's cases under one `test.describe` that names its subject, and tag the describe.**
    A file has exactly one top-level describe; nested describes group its sub-topics. Tags come from
    a closed list:

    | Kind | Tags |
    | --- | --- |
    | Component | `@catalog`, `@engine`, `@sessions`, `@testing-service`, `@ui`, `@infra`, `@schema`, `@harness` |
    | Budget | `@slow`, for a case that needs more than 60 s |
    | Engine | `@classic-only` (rule 20) |
    | Broker | `@kafka`, `@rabbitmq`, `@pubsub`, `@sftp` |
    | API version | `@v1`, `@v2`, `@v3` |

    `@infra` is for the stack's own infrastructure: the nginx proxy, Kubernetes resources,
    restarts, and metrics. `@schema` is for a stack-less case about a product file (`routes.conf`,
    a compose file, a migration, a schema), and `@harness` is for a case that tests the suite
    itself. Only the component tags are read: `--grep @engine` selects a component, and the case
    catalog groups by them. The other kinds are informational.

    Playwright copies a describe's tags into every case under it, so tags go on the top-level
    describe, and a nested describe or a case adds only the tags that differ. Every case inherits at
    least one component tag through its describe chain; a case that inherits none fails
    `npm run reconcile`. A case in the `ui` project carries `@ui` and never a backend tag, so a
    backend grep with `--no-deps` selects no case that needs Chromium. A computed tag such as
    `COMPONENT_TAG[role]` is allowed, and reconcile checks its value in the report.

    Where one file covers several components, the component goes in a nested describe and the
    cases are declared once in a function, so each title stays a literal:

    ```ts
    test.describe("actuator health", () => {
      function actuatorCases(role: ServiceRole) {
        test("reports UP with both probe groups", …);
        test("names no component as down", …);
      }
      test.describe("runtime-catalog", { tag: ["@catalog"] }, () => actuatorCases("runtime-catalog"));
      test.describe("engine", { tag: ["@engine"] }, () => actuatorCases("engine"));
      test.describe("testing-service", { tag: ["@testing-service"] }, () => {
        test("answers its own /health", …);
      });
    });
    ```

    `e2e/` has no formatter config, so keep the title, the tag object, and the fixture
    destructuring on the declaration line, however long it runs.
11. **Call a chain on the engine, never through the proxy.** `http://localhost:8092/routes/…`, not
    `http://localhost:8080/…`. No nginx `location` matches `/routes/`, so a request through the
    proxy falls through to the SPA and answers **200 with `index.html`** whatever the chain did. A
    chain also answers on `/cip-routes/{contextPath}`, and the suite addresses `/routes`.
12. **An endpoint that answers 202 is polled for its artifact, never for `done`.** A gRPC import
    reports `done: true` with a null library id, and a root-layout archive imports nothing and
    reports success. `Catalog.awaitSpecificationImport` stops on the specifications and their
    operations for that reason.
13. **Carry a known divergence as `test.fail()`, never as a red result the reader is told to
    expect.** The annotation inverts the verdict, so the run stays green while the defect stands and
    turns red the day it is fixed, which is the signal to remove the annotation. Every case carrying
    it names its defect at the annotation, by issue number where one is filed;
    `grep -rn "test\.fail(" specs/` lists them, so quote no count. Two limits come with the
    annotation:
    - It accepts **any** failure, so a pinned case narrows itself to the divergence it carries
      through `support/known-defect.ts`: `onlyTheKnownStatus` where the defect is one status and the
      fix another, `notTheKnownDefect` where the message has to be read too, and `outsideTheDefect`
      around the setup, since an inverted verdict turns a fixture that could not be built into a
      pass. `specs/global/export-all.spec.ts` and `specs/api/error-contract.spec.ts` show the
      message-reading form.
    - It is for a divergence a spec can reproduce on demand. Where no create path manufactures the
      offending state, such as an element of a type the library does not offer, the spec asserts the
      healthy contract and prints the query that finds the offender instead.
    - A defect that only one engine meets would turn a plain `test.fail()` red on the other, and a
      race would turn it red on the runs it misses. Wrap the step that meets it in `strikesAsKnown`
      with a `ConditionalDefect` from `support/known-defect.ts`, which names the engine that meets
      the defect, or `engine: "any"` for a race or a catalog or sessions defect either engine meets:
      the case becomes an expected failure only when the step throws that defect on that engine,
      and the defect's `matches` gets a case in `specs/schema/conditional-defects.spec.ts`. On the
      other engine the same message stays a real failure, because a regression of the same feature
      can produce it. A defect lives in `specs/<project>/<component>-defects.ts`, which Playwright
      does not collect. `matches` reads the message without color codes. It keys on an assertion
      message the case writes itself (`RESULT_AT_100`, `WIPE_ANSWERED`), and that assertion comes
      last, so the rest of the case still runs when the defect strikes. Reconcile reads the calls a
      struck case made, but not its `covers()`. A fixed defect leaves the case passing rather than
      red, so the pin is removed by hand.
14. **A `specs/schema/` spec imports `test` and `expect` from `@playwright/test`, never from
    `support/fixtures.js`.** That module installs `auto: true` fixtures which create a folder over
    HTTP and shell out to `docker logs`, so one wrong import turns the `schema` project into one that
    needs a stack, and CI has no Docker. `specs/schema/schema-imports.spec.ts` holds every sibling to
    it. Importing a *helper* from `support/` is fine: it is `test` and `expect` that carry the
    fixtures.
15. **Move the registry row in the same commit as the spec.** A covered element row needs a
    `covers(family, axis, value)` declaration in a passing test, or `npm run reconcile` fails naming
    the row; an operation row moves to `reached` or `covered` once a passing test calls or asserts
    it. A tag makes a spec visible; a registry row is what makes it count.
16. **Read the platform over HTTP, never over SQL.** The suite has no database client: a second way
    of seeing the platform sits outside the transports, so nothing it reads counts as `reached`.
17. **Prove a guard by breaking what it guards.** An assertion nobody has watched fail is a claim, so
    make one edit at a time, each turning its own case red and no other. A transport that drops a
    parameter, a header, or a body field is a failure no spec-side edit can reach, so some mutations
    belong in `support/`.
18. **An assertion on a log is two-sided, and scoped to a window the spec opened.** `env.logs` takes
    a `since` for that reason: an unscoped read is satisfied by an unrelated earlier line, and an
    absence-only assertion is equally green when the mechanism never ran. Assert the line that
    proves it ran **and** the line that would show it failing, and scope the positive half to
    something only this case could have caused. `specs/runtime/script-external-library.spec.ts` is
    the shape: `Resetting groovy script cache` read after the case's own `Saved library` line for its
    model id, and `Failed to reset groovy script cache` absent.
19. **Correlate a broker or file trigger by transport, never by the shared run token.**
    `Properties.TESTING_SESSION_ID` is set in exactly one place, `HttpTriggerProcessor.java:85`, so a
    chain triggered by Kafka, RabbitMQ, pub/sub, SFTP, or the scheduler is never marked as a
    test-case run, and its outbound calls reach real endpoints, un-mocked. Kafka and RabbitMQ
    triggers, and every sender (Kafka, RabbitMQ, pub/sub), carry a per-call
    `external-session-cip-id` header the producing side sets, and the spec finds the session with
    `GET /v1/sessions/external-id/{token}?includeDetails=true`. That endpoint answers **404 with an
    error body** while the session is not yet written, so a poll against it tolerates a non-2xx.
    `pubsub-trigger` cannot carry the header: the emulator delivers its attributes as one
    `GooglePubsubConstants.ATTRIBUTES` header, and `SessionsService.startSession` reads the
    correlation header before any route element could unpack it. So `pubsub-trigger`,
    `sftp-trigger-2`, and `quartz-scheduler` correlate through `POST /v1/sessions/chains/{chainId}`
    filtered on the window the spec opened, then `GET /v1/sessions/{sessionId}` for the element
    trace.
20. **A spec reads `engineKind` in one place only.** On `k8s` a micro project runs the files of a
    classic one against the micro engine: `runtime-micro` runs `specs/runtime/`, `brokers-micro`
    runs `specs/brokers/`, `global-micro` runs the two engine files of `specs/global/`, and
    `env-micro` runs `specs/env/restart-resilience.spec.ts`. A spec reads its corpus through
    `seedChain` or `projectCorpus` in `support/corpus.ts`, or `brokerChain` in
    `support/brokers.ts`, which return the copy the project's engine runs, and never branches on
    `engineKind`. Only `engine-identity.spec.ts` reads it, because its subject is the engine kind. A
    micro project names the state file that records its domain through `use: { microDomainState }`.
    A difference between the two engines is a finding, and only the run on one engine meets it: pin
    it with `strikesAsKnown` and a `ConditionalDefect` that names that engine (rule 13), name its
    issue, and never loosen the assertion for one engine. A case the micro engine cannot run by
    design, such as a library replaced under a deployed chain, a deployment parked for a retry, or a
    chain that fails to load and so takes its micro domain down, carries the `@classic-only` tag and
    the annotation `{ type: "classic-only", description: "<reason>" }` on its declaration line; the
    micro projects leave it out through `grepInvert: /@classic-only/`. A case that deploys a chain
    of its own is one of them, since a deploy into the shared micro domain rolls the pod under every
    `runtime-micro` case; every other case reads a chain both seeds deploy. The micro case of such a
    deploy goes in `specs/runtime-micro-redeploy/`, whose project runs one file at a time: the file
    deploys its chains in `beforeAll` to a micro domain of its own through `tokenedDomain` and
    `deployToDomain` in `support/deployable.ts` (`dispatchToDomain` for a chain that is not meant
    to serve), deletes it in `afterAll`, and calls the chains through the `env` the helper returns.
    `specs/schema/target-rules.spec.ts` checks this rule, that the tag and the annotation come
    together, and the `support/kube.ts` half of rule 4.
21. **Name a case by its subject and the outcome it observes, in at most 60 characters.** The
    describe path supplies the context, so under `testing-service` the title is
    `answers its own /health`, not `the testing service answers its own /health`. A title never
    carries `because`, `which is why`, `rather than`, or `so that`: the reason a case exists goes in
    a comment above it. A title is a string literal, or a template string in a loop over
    identifiers, an imported list, or a call such as `idempotencyModes(...)`; only the literal part
    of a template counts toward the 60. A title taken whole from a variable, such as
    `test(each.title, …)`, has no length limit.
22. **Merge cases only along a shared path, and make each stage a `test.step`.** Create, export,
    delete, and import of one archive are one path, so they are one case with a named step per
    stage: the report shows which stage failed, and the case pays for its setup once. Cases that
    observe different outcomes of one feature, such as the four idempotency modes, stay separate
    cases, so a red result names the mode that broke.

## Assertions that catch a reported success

Most defects this product has produced report success: a green deployment consuming nothing, a
completed import with no artifact, a 200 that discarded the message. Beside rule 12, three specs
exist for that shape, and each is invisible from inside the file that carries it:

- **Creation-path equivalence**, `specs/api/creation-path-equivalence.spec.ts`. A chain built through
  the API and the same chain restored from its own export are deployed, driven with one request, and
  their session traces compared. Every fixture spells out its full property set, which makes every
  other spec blind to a schema-default defect. Its micro half is in `specs/runtime-micro-redeploy/`;
  both drive and compare through `support/equivalence.ts`.
- **Process envelope**, `specs/env/process-envelope.spec.ts`. Every service is still the process the
  run started, its Metaspace is inside a stated share of its ceiling, and its peak memory and CPU are
  inside stated bands, sampled over the run.
- **Negative configuration**, `specs/brokers/missing-target.spec.ts`,
  `specs/brokers/broker-restart.spec.ts`, and the negative cases of
  `specs/brokers/kafka-security.spec.ts`. Every other fixture is correct by construction, so these
  assert the pre-deploy refusal of a missing Kafka topic, RabbitMQ queue, and exchange, the
  platform's reconnect after a broker restart, and the refusal of a wrong Kafka password and of a
  TLSv1.2 client on a TLSv1.3-only listener. The missing-target and kafka-security negatives are
  `@classic-only`: the micro engine runs its checks when its pod starts, and a failed one stops the
  pod, so such a chain keeps its whole micro domain from starting rather than being refused on its
  own. A negative broker fixture whose client logs on every retry stays spec-owned
  (`SPEC_OWNED_FIXTURES`) rather than seeded, so its log lines end with its case.

## The shared corpus

`specs/seed/seed.setup.ts` writes the generated axis chains into the gitignored `fixtures/axes/`, then
imports every fixture under `fixtures/chains/`, `fixtures/axes/` and `fixtures/script/` as one
archive, deploys them in one batch, and writes what it imported to `.e2e-corpus.json`, which is how
the dependent projects address them. The file sits outside `test-results/`, which Playwright clears
at the start of every run, because a corpus kept with `E2E_KEEP=1` is meant to outlive one. The three
directories are `CORPUS_FIXTURE_DIRS` in `fixtures/templating.ts`, and they hold two layouts: a
directory per chain, or, under `fixtures/script/`, whose chains ship no companion file, a **file**
per chain, whose name carries the `.yaml` and is what a spec asks `seedChain` for. A fixture a spec
creates and destroys itself is named in `SPEC_OWNED_FIXTURES` and the seed leaves it alone, because
an import over a live id is an update and the two would fight over the same chain.
`support/corpus.ts` is the one assembler, and every seed project calls it: a chain deployed alone
pays the engine's deployment pickup on its own, about 2 s each against 0.49 s inside a batch.

On `k8s`, `seed-micro` deploys a micro copy of every corpus chain to a micro domain of the run, so a
fixture added to the corpus runs on both engines. `microCopy` in `fixtures/templating.ts` remaps the
copy's IDs and trigger paths. A chain that calls back into the engine calls `ENGINE_LOOPBACK`
(`http://localhost:8080/routes/…`), in its own URI or as a service's address, and `microCopy`
rewrites that call to the callee's micro copy. The micro engine resolves `#{…}` once, when the domain
loads, so a corpus chain reads no common variable except the one both seeds write
(`placeholderVariable`). `specs/schema/corpus-copies.spec.ts` checks the loopback rewrite and the
variables.

Three steps happen in an order that is not negotiable:

- **Delete before importing.** The fixture documents carry fixed IDs, so an import over the last
  run's corpus is an update and every assertion downstream would hold without anything having been
  created.
- **Raise the session logging level between import and deploy.** `DeploymentRuntimeProperties`
  defaults to `SessionsLoggingLevel.OFF` and `SessionsService` writes nothing at that level, so a
  corpus deployed without `POST /v1/chains/{id}/properties/logging` records no trace at all. The
  properties travel with the deployment, so raising them afterwards leaves the first one silent.
- **Gate on the route, not on the status.** `DEPLOYED` means the catalog accepted and dispatched
  the deployment. A chain whose listener never connects reports `DEPLOYED` indefinitely, and a
  healthy one serves seconds after the status flips, so the seed follows the status poll with a
  poll of the routes themselves. A chain with no HTTP trigger, such as `scheduler-basic`, has no
  route to poll, and `waitForRoutes` skips it: on the classic engine the status poll alone gates
  it, and on the micro engine the other routes of the same Integration do.

`scheduler-basic` fires every 3 s on both engines for the whole run. A case that reads sessions it
did not scope by chain or by a window it opened leaves out the `*-scheduler-basic` ones, as
`specs/global/sessions-destructive.spec.ts` does in its search filter.

A chain that calls a service is seeded too. `fixtures/services/` holds one directory per service,
with its specification group, its specification and the specification's source below it, and
both seeds import every one through `POST /v1/import/system` before their chains, the micro seed
its micro copy. A context service or an MCP service a chain names sits there too, and goes
through the import of its own kind (`SERVICE_IMPORTS` in `support/corpus.ts`). A chain names an
operation by the fixed IDs the service fixture carries: `serviceCallTo` in
`fixtures/axis-generator.ts` reads them for a generated chain, and `specs/schema/fixtures.spec.ts`
checks the IDs a hand-written chain spells out.

A runtime case deploys a chain of its own, in the worker folder, only when the corpus cannot hold
it: it would carry a reference the export cannot resolve (`contextServiceId`), the seed would refuse
it because it does not go live, a shared chain would race the change the case makes, or the case
undeploys it.
Such a case builds through the builders in `support/deployable.ts` and holds what it created in
`withBuilt` from `support/cleanup.ts`, which undeploys the chains and deletes what they use outside
the worker folder. A describe that builds its chains once in `beforeAll` hands them to `release` in
`afterAll` instead. After `waitForRoutes` the case calls `waitForRecording` from the same module; a
chain with no route, like the MCP one, goes through `waitForFirstRecording` with a warm-up of its
own. A call sent the moment a route answers can record no session, so the
gate sends warm-up calls until one records, and fails once more of them are lost than
`UNRECORDED_WARM_UP_LIMIT` allows.

`fixtures/brokers/` is a second corpus, outside `CORPUS_FIXTURE_DIRS`, imported and deployed by the
`brokers-seed` project rather than `seed`, because the classic engine's pre-deploy connectivity
checks would stall the whole shared corpus whenever a broker is down. `support/brokers.ts` is its
assembler, and it also creates the run-token topics, queues, exchanges, dead-letter bindings, and
pub/sub topology each fixture needs before deploying, since nothing on either side of a broker chain
declares its own. What it imported is written to `.e2e-brokers-corpus.json` (`.e2e-k8s-brokers-corpus.json` on
`k8s`) and read back with `readBrokersCorpusState`.

On `k8s`, `brokers-micro-seed` deploys a micro copy of the same fixtures to a micro domain of its
own. `microCopy` appends `-micro` to every topic, group, queue, exchange, and subscription, and moves
the SFTP tree to `/upload/<run>-micro`, because both copies run at once on one set of brokers.
`brokerChain` returns the copy the project's `engineKind` runs, as `seedChain` does. A name a broker
spec mints itself, such as a verification consumer group or a pull subscription, is built from the
chain's own address (`${topic}-consumer`), never from the run token alone, so the two copies never
share it.

A Kafka consumer group turns `Stable` before its consumer looks up the offset, so a trigger left at
`autoOffsetReset: latest` can skip a message published right after the group settles (CI run
38443020207). A spec whose Kafka trigger reads a topic the spec itself creates sets `earliest`; the
seeded Kafka triggers keep `latest`, because that value is the axis they cover.

`fixtures/specifications/` holds the API documents a spec imports as a service specification
(OpenAPI, GraphQL, AsyncAPI), read with `readSpecificationFixture`. The seed never touches them: a
spec imports the one it needs into a group it created and deletes it again. A broker chain that
names an AsyncAPI operation (`async-api-trigger`, a kafka or amqp `service-call`) is seeded like any
other broker chain: its service sits under `fixtures/broker-services/`, never under
`fixtures/brokers/`, whose every top-level entry `corpusFixtureNames` reads as a chain, and each
broker seed imports its own copy of the services before its chains. The operation's topic or
exchange is the element's `integrationOperationPath`, so the seed creates it and `microCopy` renames
it.

## Residue from a previous run

`e2e/README.md` describes what the sweep collects. The rules behind it:

- **Ownership never comes from the shape of a token.** Every run's token has the same shape, so a
  shape match lets a sweep delete entities that are not its to delete. `e2e/.e2e-runs.json` records
  each run's token, pid, and start time; a run is collectable only once the process that recorded it
  is gone. `E2E_SWEEP=never` is an opt-out, because the default has to be the safe one.
- **A `restartWith` that outlived its run is residue nothing on disk shows.** The Compose override
  is deleted as soon as Compose has read it, so a catalog left running
  `CIP_EXPORT_LEGACY_FORMAT=true` passes every staleness check. `env/compose.ts` records the
  settings in `e2e/.e2e-overrides.json` **before** the recreate that applies them and drops the
  record after the restart that puts the committed settings back; `env/provision/compose.ts`
  recreates whatever is still recorded. A file that will not parse is read for the service names it
  still mentions, never as empty, because empty hands provisioning a stack it wrongly believes is
  clean.
- **A delete path that exists is the line between an exemption and a gap.** Audit-log rows carrying
  the run token and secrets are exempt from the sweep because the catalog offers no delete for them.
  Broker topology has a delete path, `teardownBrokers`, which the sweep does not reach, and that is a
  gap.
- **A run never names a secret after itself**, because `SecretControllerV2` has no delete.
  `specs/api/secret-name.spec.ts` posts one committed fixture name (`SECRET_FIXTURE_NAME`),
  `secretsCarryingRunToken` in `support/fixtures.ts` is the reading, and
  `specs/api/v2-v3-controllers.spec.ts` fails on a non-empty answer. It stays out of `findResidue`,
  whose list `sweepRunToken` deletes and `sweptClean` counts, since a row nothing can delete would
  keep every run in the manifest.
- **Sessions and endpoint mocks are swept through the `OutsideCatalog` client pair** in
  `support/fixtures.ts`. Sessions are found with `POST /v1/sessions` filtered on `CHAIN_NAME`
  `CONTAINS` (the chain name is the only session field carrying the run token) and removed with
  `DELETE /v1/sessions/chains?chainIds=…`. A mock is keyed on a chain id and an element id the
  fixture freezes, so one that outlives its case answers in place of the real endpoint on every later
  run. Name a mock with `tokenized(run, …)`, never with `callToken()`, whose value carries no run
  token. The clients are a parameter because `specs/schema/sweep.spec.ts` runs `sweepRunToken`
  against a stub catalog with no stack.
- **Test cases and test runs are deleted by the spec that creates them**, in the case's own
  `finally`, through `withTestCases` in `support/testing-service.ts` or by hand. The sweep does not
  collect them.

## Sampling what the run costs

A peak lasts seconds and is roughly twice the idle value, so a resource claim comes from a sampler
over the run and never from a single reading. CPU is watched beside memory because it runs out
first: the stack reached 94% of its twelve allocated cores under a load lighter than a full run
while using 29% of its memory. The worker count is pinned for the same reason: Playwright derives it
from the **host's** cores, which is not the number the system under test has.

## Frozen fixtures

Archives under `e2e/fixtures/archives/frozen/` pin import compatibility and are **never
regenerated**. A checksum file sits beside them, so changing one means changing its checksum in the
same commit and putting that in front of a reviewer. Regenerating the corpus from today's exporter
turns it into a copy of today's exporter, which proves nothing.

Compatibility is versioned by the migration list a document declares (`migrations: "[100, 101,
102]"`), not by a release date. Freeze one document per migration floor.

Each archive is a fixture directory holding one document, zipped into `chains/<id>/` or
`services/<id>/` at import time, so what the checksum guards is reviewable text rather than a binary
blob. `npm run frozen-checksums` rewrites `CHECKSUMS` after a deliberate change; running it to turn
`specs/schema/frozen-corpus.spec.ts` green is the failure the guard exists to catch.

`fixtures/services/service-roundtrip.zip` is the one archive committed as bytes, and it sits
outside the frozen set. `specs/api/service-roundtrip.spec.ts` imports the catalog's export
unchanged, because a re-zipped tree is not the export under test, and it lists every entry, so a
regenerated archive shows up in review. The spec's header names the three services and how they were
made; regenerate the archive by creating them again and exporting them with `GET /v1/export/system`.
The extension's test workspace copies the internal service
(`vscode-extension/src/web/test/workspace/services/3b2e9742-…`), so replace that copy in the same
commit. The committed archive is a `qip` export (`*.qip.yaml` names, `qubership.org` `$schema`
values), and it is the only archive the suite imports under `*.qip.yaml` names. Exporting it again writes
the `cip` format and drops that coverage, so regenerate it only together with another `qip`-format
case.
