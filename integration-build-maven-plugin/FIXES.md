# Maven plugin review: fix ledger

Status of every finding in [REVIEW.md](REVIEW.md). Update a row when its status changes, and record the
commit that changed it.

Statuses: `open`, `fixed`, `partial`, `accepted`, `postponed`, `won't fix`, `out of scope`.

| ID | Finding | Severity | Status | Commit |
| --- | --- | --- | --- | --- |
| F1 | AtlasMap custom actions unregistered, so mapper chains abort the build | blocker | fixed | `bcdb973dc` |
| F2 | Route resources never generated; `${spring.application.cloud_service_name}` leaks into output | blocker | fixed | `5ef3c4185` |
| F3 | DTO library location hardwired to runtime-catalog, no codegen on the plugin side | major | out of scope | |
| F4 | Nested elements duplicated in the snapshot element graph | major | fixed | `86b24ed33` |
| F5 | No equivalence test against the runtime-catalog pipeline | major | out of scope | |
| F6 | Module missing from every CI workflow and from `scripts/modules.sh` | major | fixed | `c17abe0d8` |
| F7 | Generated resource contents differ between runs | medium | postponed | |
| F8 | Dead `qip.cr.build` block in the plugin's `application.yml` | medium | fixed | `bcdb973dc` |
| F9 | Service file filter accepts context and MCP services, reader handles only integration systems | medium | fixed | `28833e817` |
| F10 | Shared-module changes alter runtime-catalog behavior | medium | accepted | |
| F11 | Smaller items, see below | minor | partial | `bcdb973dc`, `1366eaa3a`, `bc7b32c22`, `8858d3aad`, `fe61c79be`, `7bd3fef96` |
| F12 | Chain version fallback answers for service exports too | major | won't fix | |
| F13 | Active environment selection diverges from runtime-catalog | major | open | |
| F14 | Smaller items from the second round, see below | medium | partial | `1366eaa3a` |

## F11 breakdown

| Item | Status | Commit |
| --- | --- | --- |
| `IntegrationServiceCatalogImpl.findAllByIds` returns `null` entries for unknown ids | fixed | `bcdb973dc` |
| `AssumeActualChainVersion` has no `@Order` | fixed | `bc7b32c22` |
| `MavenPluginYamlMapperConfiguration` relies on parameter-name bean matching | open | |
| One bad chain aborts the whole run | fixed | `7bd3fef96` |
| Generated resources are not attached as build artifacts | fixed | `fe61c79be` |
| `BuildCRsMojo` lacks `property =`, `skip`, and `threadSafe = true` | partial | `8858d3aad` |
| Chains are read from `${project.compileSourceRoots}` | fixed | `1366eaa3a` |
| `ChainReader.getChainYamlFile` silently drops a second chain YAML | open | |

## F14 breakdown

| Item | Status | Commit |
| --- | --- | --- |
| A chain with no `deployments` is built into `defaultDomain` | open | |
| `deployAction` is never read, so `NONE` still produces resources | open | |
| With default configuration the goal writes nothing and says nothing | fixed | `1366eaa3a` |
| Container hardening defaults are weaker than the catalog's | open | |

## Fixed

### F1 and the mapper failure, `bcdb973dc`

Moved `META-INF/services/io.atlasmap.v2.Action` from `runtime-catalog` into
`integration-build-pipeline`, next to the action classes it names. `engine` and `micro-engine` keep
their own copies, which register their own `AtlasMapUtils` actions rather than these.
`AtlasMapInterpreter` now passes the `JsonProcessingException` as the `MapperException` cause.

Verified:

- `transformation/mapper-2` builds through the mojo, and the generated Camel XML carries the serialized
  `QIPDictionary` action.
- The whole `testConfigurations` corpus builds clean, 28 resources, no failures.
- `runtime-catalog` `AtlasMapInterpreterTest.interpretationDefaultValue` still passes, which exercises
  `QIPDefaultValueAction` and so depends on the registration resolving from the jar.

### F8, `bcdb973dc`

Removed the `qip.cr.build` block from the plugin's `application.yml`. The mojo builds
`ResourceBuildOptions` from its own `<options>` parameter, so nothing read the block.

The `ResourceBuildOptions.namespace` half of F8 stands: the mojo still has no way to set a namespace, so
`ServiceMonitorBuilder` keeps falling back to the `{{ .Release.namespace }}` literal.

### F11, `IntegrationServiceCatalogImpl`, `bcdb973dc`

`findAllByIds` filters out `null` entries, matching `PersistentIntegrationServiceCatalog`.

### F2, `5ef3c4185`

Route generation is driven by a `controlPlaneType` mojo parameter (`ISTIO` by default, `CORE` to
suppress), read by `OptionControlled*` subclasses of the three route builders through
`ControlPlaneUtil`. The base classes are excluded from the component scan, so their own
`@ConditionalOnProperty` cannot register one beside its subclass. `ControlPlaneUtil` throws when the
build never recorded its parameters, rather than reporting "disabled", so the silent skip cannot come
back through a wiring mistake.

Placeholder resolution is strict: `ApplicationConfiguration` registers a
`PropertySourcesPlaceholderConfigurer` with `setIgnoreUnresolvablePlaceholders(false)`. Enabling it
surfaced four properties the module never defined, now supplied from runtime-catalog's values:
`spring.application.cloud_service_name` (overridable through `QIP_CATALOG_SERVICE_NAME`),
`camel.constants.request-filter-header.name`, `qip.access-control.resource-type.chain`, and `protoc.*`.

Verified:

- `triggers/http-trigger` produces the public and engine HTTPRoutes; an external HTTP sender produces
  the egress HTTPRoute, ServiceEntry, and DestinationRule.
- `CORE` suppresses all four kinds and skips route collection entirely.
- The whole `testConfigurations` corpus builds, 30 resources, with no Spring placeholder left in the
  output. The DTO library URL reads `http://qip-runtime-catalog-v1:8080/...`, matching what the catalog
  itself emits.
- Removing a property fails the build with `Could not resolve placeholder '...'` and writes nothing.
- 43 tests and checkstyle pass. The three new test classes were mutation-checked: inverting the control
  plane a builder asks for, dropping the short-circuit, reverting `orElseThrow` to `orElse(false)`, and
  removing the scan exclusion each make them fail.

Carried forward from reviewing this fix: a stray blank line in
`OptionControlledHttpRouteResourceBuilder.enabled`.

### F9, `28833e817`

`ServiceFileUtil.isServiceFile` matched context-service and mcp-service exports alongside integration
system ones, and `MicroDomainResourcesBuildService` fed every match to `IntegrationSystemReader`. The
filter now matches integration system exports only, and is named `isIntegrationSystemFile` so a file
called `*.mcp-service.qip.yaml` returning false reads as intended rather than as a missing branch.

Nothing is lost: no class under `camelk` reads `McpService` or `ContextService`,
`IntegrationServiceCatalog` has no accessor for either, and `McpTriggerBeansBuilder` works from element
properties alone. Both kinds keep their own readers, which this module does not call. Skipping them
stays silent, which the team accepted as consistent with the plugin's other unsupported inputs.

Verified by building the same project on both predicates. With an MCP service export carrying the id of
a real integration service in the tree:

| Predicate | Result |
| --- | --- |
| Before | `Duplicate integration service with id 1acc68e3-...`, nothing written |
| After | build succeeds |

Also added the tests both file-name predicates were missing, `ServiceFileUtilTest` and
`ChainFileUtilTest`, 28 cases over the prefix and infix forms, the extension gate including the
`.yaml.bak` case `endsWith` alone lets through, and each predicate rejecting what the other accepts.
Mutation-checked: restoring the MCP branches breaks two, dropping the extension gate from
`ChainFileUtil` breaks three.

### F4, `86b24ed33`

`build()` mapped every entry of the flat `Chain.getElements()` to a new element and also recursed into
children, so each nested element was created twice and the copies competed for the chain's connections
through a last-writer-wins map. It now maps the roots, lets the existing recursion produce the rest, and
flattens once, so `snapshot.getElements()` keeps the shape `SnapshotAdapter` returns in runtime-catalog.
`createElementMap` collects with `toMap`, so a duplicate id fails loudly rather than silently picking a
winner.

Verified:

- The probe from the review reports 2 distinct objects rather than 3, and the element the container
  holds carries the input connection instead of an empty list.
- Output is unchanged where it already worked: `try-catch-finally-2` still generates 13 element beans
  with no duplicates, 7 routes, 3 `doCatch` and 1 `doFinally`, and the corpus still builds 30 resources.
  Correctness there used to rest on `ChainModelMapper` inserting children before their container, which
  nothing asserted; it no longer depends on that ordering.
- Two tests added on the flat shape `ChainReader` produces, the gap the existing fixtures left by
  passing a tree. Both fail on the previous implementation, one on object identity and one on the
  container's child having no connections.

### F6, `c17abe0d8`

Added `integration-build-maven-plugin-build.yaml` and `integration-build-maven-plugin-release.yaml`,
both delegating to the existing `_maven-module-build.yaml` and `_maven-module-release.yaml` reusables,
and registered the module in `release-all.yaml` (`ALL_MODULES`, the `prepare` output, its own release
job, and the `publish-bom` and `create-drop-release` dependency lists), in `snapshot-publish.yaml`, and
in `scripts/modules.sh`.

This is the first pass, reverted on September 18, 2026 while the team considered another approach, then
restored by decision the same day.

Two changes the wiring needed to produce a working release:

- The POM pinned the library at `${revision}${changelist}`, its own version, which holds only while the
  two modules release on the same line. It now pins the literal released version and appends
  `${changelist}`, the way `runtime-catalog` does, so `integration-build-pipeline-release.yaml` moves
  the pin through `sync-poms` and a release resolves a library that exists.
- `scripts/check-version-invariants.sh` checked that one pin; it now iterates `QIP_LIBRARY_CONSUMERS`.

The release job runs after `release-integration-build-pipeline` for the same reason `runtime-catalog`
does: the library release moves this plugin's pin, and the job has to check out a tree that carries it.
The `profile=central` guard rejects a wave that releases the library beside either consumer.

The module stays out of `main-build.yaml`, matching `integration-build-pipeline`, which is absent from
that matrix and its `paths:` filter too.

Verified:

- `scripts/check-version-invariants.sh` passes, and fails with a clear message when the plugin's pin is
  drifted to `1.2.9`.
- `scripts/build-bom.sh` lists the module, `null` until its first tag.
- `mvn verify -pl integration-build-maven-plugin -am -Dgpg.skip=true`, the command the PR build runs,
  passes with the new pin resolving the library from the reactor.
- Every workflow parses, and `release-all.yaml` carries the release job in both aggregate dependency
  lists.

`vars.SONAR_INTEGRATION_BUILD_MAVEN_PLUGIN_PROJECT_KEY` needs a SonarCloud project behind it. Until the
variable is set the key is empty and the `sonar` job skips itself, which is how the reusable workflow
handles an unset key.

### F11 and F14, the `sourceRoots` default, `1366eaa3a`

Both goals now default `sourceRoots` to `${project.basedir}/src/main/integration`.
`${project.compileSourceRoots}` resolves to `src/main/java` plus the `generated-sources` roots earlier
plugins append, and chain exports never live there, so a default run walked directories that hold
nothing either goal reads. A dedicated directory follows the convention other non-Java plugins use,
`src/main/proto` and `src/main/avro`, and one root serves both goals because each walks it recursively
and filters by file name.

That also answers the F14 half. A project without the directory now gets a warning naming the path the
goal expected, from the `not a directory` branch both loaders already had, rather than silence.

Verified:

- `mvn process-classes` regenerates the descriptor, and `plugin.xml` carries
  `default-value="${project.basedir}/src/main/integration"` for `build-crs` and `build-libs`.
- A plain string default on a `List<String>` parameter is safe. `CollectionConverter.fromConfiguration`
  in `org.eclipse.sisu.plexus` splits the value on commas through `csvToXml`, so the field gets a
  one-element list rather than a type-mismatch failure.

### F11, the version fallback order, `bc7b32c22`

`AssumeActualVersion` had no `@Order`, so it tied with `VersionFieldStrategy` at
`Ordered.LOWEST_PRECEDENCE`, and the classpath scan decided which of the two ran first. Had the fallback
won, a `fileVersion` export would have been read as current and never migrated. `AssumeActualVersion` is
now `@Order(Ordered.LOWEST_PRECEDENCE)` and `VersionFieldStrategy` moved to
`Ordered.LOWEST_PRECEDENCE - 1`, so the strategies run in a fixed order: `content.migrations`,
`migrations`, `fileVersion`, then the fallback.

runtime-catalog has no fallback, and its three strategies keep their relative order.

Verified:

- `mvn verify -pl integration-build-maven-plugin -am -Dgpg.skip=true` passes.
- `ApplicationConfigurationTest.asksTheAssumeActualVersionFallbackAfterTheFileVersionStrategy` pins the
  order in the real context: a `fileVersion: 2` document reports `[1, 2]`, and a document without
  metadata reaches the fallback. Mutation-checked: moving `AssumeActualVersion` to
  `Ordered.HIGHEST_PRECEDENCE` fails it with `expected: <[1, 2]> but was: <[100, ..., 108]>`.

### F11, generated files attached as artifacts, `fe61c79be`

Both goals attach what they write to the Maven project through `MavenProjectHelper`, so `install` and
`deploy` publish it. The mojos inject `MavenProject` and `MavenProjectHelper` and hand them to the build
services in a `TaskContext`, next to the task parameters.

- `build-crs` attaches each resource file as type `yaml`, classified by the file name without `.yaml`.
  `ResourceWriteService.writeResources` reports every file it writes to a callback, which does the
  attaching.
- `build-libs` attaches each DTO library as type `jar`, classified by the specification id.

The classifier is what keeps the files apart. `MavenProject.addAttachedArtifact` replaces an attachment
with the same coordinates rather than adding a second one, so without a classifier only the last file
survived. A `jar` with no classifier would also share its coordinates with a `jar`-packaged project's own
artifact.

Verified:

- In a scratch project, `build-crs` over `triggers/http-trigger` with `deployAll` writes 6 resources, and
  `mvn install` puts all 6 in the local repository, named
  `<artifactId>-<version>-<Kind>-<name>.yaml`. Before the classifier, it installed one `probe-1.yaml`
  and logged `already attached, replace previous instance` five times.
- The `build-libs` attachment was not run end to end: the only protocols with a code generator are gRPC,
  which needs `protoc`, and GraphQL, which has no test service.
- `mvn verify -pl integration-build-maven-plugin -Dgpg.skip=true` passes, 85 tests. New tests check that
  `ResourceWriteService` reports each written file, that each resource is attached as `yaml` under its
  extensionless file name, and that each JAR is attached under its specification id. Mutation-checked:
  putting the extension back into either classifier fails the matching test.

### F11, collecting errors instead of stopping at the first, `7bd3fef96`

Both goals take a `failFast` parameter, `cip.failFast`, `true` by default, which keeps the previous
behavior. With `false`, `SkippableFailableOperationWrapper` catches each failure, counts it, logs it with
its stack trace, and lets the loop go on; each stage then throws one `... %d error(s) occurred` summary
when its count is above zero. In fail-fast mode the wrapper counts and rethrows without logging, so Maven
prints each error once.

A failure skips one service file, one chain read, one chain snapshot, one domain, or one service's DTO
libraries. Services are loaded first and their summary is thrown before any chain is read: chains resolve
services from the catalog, so the team decided a failed service stops the goal there.

Two defects were found in review and fixed before the tests were written:

- `loadServices` mapped a failed read's `null` into an `ImportSystemAdapter` before filtering nulls, so
  `addService` hit a `NullPointerException` and every broken service file counted twice. Reproduced with
  one malformed service file: `Failed to load services: 2 error(s) occurred`, with the NPE logged.
- `wrapConsumer` logged only when rethrowing, so skipped chain, domain, and library failures were counted
  but never shown.

Verified:

- `mvn clean verify -pl integration-build-maven-plugin -Dgpg.skip=true` passes, 98 tests.
- `SkippableFailableOperationWrapperTest`, 7 cases, pins the pass-through on success, the rethrow of the
  same exception without logging when failing fast, one `ERROR` log with the original exception when
  skipping, and one shared counter across both wrappers.
- New `failFast=false` cases: two broken service files count as 2 errors and the good one loads; a
  duplicate service id counts as 1; a failing specification leaves the other service's JAR written; a chain
  that fails to read leaves the other domains built; a chain that fails its snapshot drops out of its
  domain and the rest of the domain is built; a failed service stops before any domain is built.
- Mutation-checked: mapping the adapter before the null filter fails
  `countsEachBrokenServiceFileOnceWhenNotFailingFast` with `2 ... but was 4`, and moving the consumer's
  log back to the rethrow branch fails three wrapper tests.

Not covered: a domain whose chains all fail is still built from an empty snapshot list.

## Accepted

### F10, shared-module changes

Closed by decision, September 18, 2026: the changes `integration-build-pipeline` carries into
runtime-catalog are deliberate, not collateral. That covers the dropped `namespaceSelector`, the
`MonitoringOptions.enabled` default, the `ImagePoolPolicy` to `ImagePullPolicy` rename, and the
reproducible `SourceDslConfigMapNamingStrategy` suffix.

The decision left two pieces of dead code behind, rather than behavior.
`ServiceMonitorBuilder.getNamespace` and its `TemplateData.namespace` went in `7ca3b7e23`: no template
had rendered them since `namespaceSelector` was dropped. `ResourceBuildOptions.namespace` stays,
because runtime-catalog populates it and it is on the REST contract. Still outstanding:
`StringGenerator.generate(int)`, the `ThreadLocalRandom` overload that lost its last caller.

Removing `getNamespace` does not leave the placeholder stripping in `ResourceWriteService` without an
input. `ServiceMonitorBuilder.getMetricsScrapeInterval` returns
`{{ .Values.monitoring.interval | default "30s" }}` for a blank interval, and unlike the namespace, the
template does render it, in scalar-leading position where it breaks the YAML parse. Reaching it takes an
explicitly empty interval: the `MonitoringOptions` default is `30s`, and an empty or whitespace-only
`<interval>` in the POM leaves that default standing. runtime-catalog reaches it through an empty
`MICRO_DOMAIN_MONITORING_INTERVAL`.

The rename still deserves a release note. `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so a stored
`imagePoolPolicy` is dropped without a word and the option reverts to `IfNotPresent`.

## Won't fix

### F12, chain version fallback

Closed by decision, September 21, 2026: the fallback is fine for chains and services alike.

Scope of what that accepts, for the record. The fallback only answers when the strategies before it find
no migration metadata, so an export carrying `migrations` or `version` is unaffected either way. A
service export without that metadata is told it carries chain migrations V103 to V108, which the service
reader rejects as coming from a newer version.

## Postponed

### F7, reproducible output

Postponed by decision, September 21, 2026, with the design and the implementation plan written first so
it can be picked up without redoing the analysis:

- [`docs/superpowers/specs/2026-09-21-reproducible-maven-plugin-output-design.md`](../docs/superpowers/specs/2026-09-21-reproducible-maven-plugin-output-design.md), committed in `172d4ec7a` and `be7913a6e`
- [`docs/superpowers/plans/2026-09-21-reproducible-maven-plugin-output.md`](../docs/superpowers/plans/2026-09-21-reproducible-maven-plugin-output.md), committed in `51ff166af`

Nothing was implemented. Six generators draw random ids or read the clock, five of them shared with
runtime-catalog, so two builds of the same sources still differ in the two ConfigMaps that carry the
Camel source and the integrations configuration. Every rebuild reads as a change in a GitOps diff.

## Out of scope

### F5, equivalence test against the runtime-catalog pipeline

Ruled out by decision, September 21, 2026.

The test would have built the same `testConfigurations` corpus through both the catalog and the plugin
and compared the generated Camel DSL. Nothing else compares the two implementations, so the divergences
each remaining and future difference produces are found by reading rather than by a build: F1, F2 and F4
were all of that kind.

### F3, DTO library generation

Deferred by decision, September 18, 2026. Chains whose service calls rely on a generated DTO library
cannot be built by the plugin until this is addressed.
