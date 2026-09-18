# Maven plugin review

Review of the `feat-maven-plugin` branch: the integration chain compilation pipeline packaged as a
Maven plugin, alongside the existing micro-domain deployment path in `runtime-catalog`.

- Reviewed: `main..feat-maven-plugin` at `5d0cbea6e`, September 18, 2026.
- Fix status for every finding below: [FIXES.md](FIXES.md).

Findings carry stable IDs (`F1`-`F11`). Keep them stable so the ledger stays readable.

## How runtime-catalog deploys to a micro-domain

Four stages:

1. **Snapshot.** `SnapshotService.build` verifies element properties, copies the chain's elements into
   a persisted `Snapshot`, fills service environments from the systems table, and stores the Camel XML.
2. **Options.** `ResourceBuildOptionsProvider.getOptions` binds `qip.cr.build.*` Spring properties into
   a `ResourceBuildOptions`, then lets `ResourceBuildOptionsCustomizer` beans adjust it.
3. **Context and build.** `MicroDomainResourceBuildContextFactory` loads the snapshots through
   `SnapshotAdapter` and builds a `BuildInfo`. For an APPEND deploy it also reads the live Integration,
   Service, ServiceMonitor, source ConfigMaps, HTTPRoutes, ServiceEntries, and DestinationRules out of
   Kubernetes, seeds the build cache with them, and records `resourceVersion` observations.
   `ResourceBuildService` then runs the per-chain builders followed by the common builders, producing
   one multi-document YAML string.
4. **Write.** `MicroDomainService.deploy` writes to the API server with those observations as
   optimistic-concurrency preconditions. `CustomResourceController` rebuilds and retries up to three
   times on conflict.

## What the plugin does instead

The plugin keeps stage 3 unchanged and replaces stages 1, 2, and 4:

| Stage | runtime-catalog | Maven plugin |
| --- | --- | --- |
| Snapshot | `Snapshot` entity in PostgreSQL | in-memory `SnapshotImpl` from `SnapshotBuildService` |
| Chain source | `ChainElement` rows | `ChainReader` over exported chain directories |
| Services | `PersistentIntegrationServiceCatalog` | `IntegrationServiceCatalogImpl` fed by `IntegrationSystemReader` |
| Options | `qip.cr.build.*` properties plus customizers | the mojo's `<options>` parameter |
| Output | write to the Kubernetes API with preconditions | YAML files in `${project.build.directory}` |

That is the right seam. `integration-build-pipeline` already exposed `IntegrationServiceCatalog` and
the two-argument `ResourceBuildContext.create`, so the whole builder tree is reused unchanged.

A build owns its whole target namespace: the micro-domains it generates are the only ones there. That
is why the plugin has no counterpart to the catalog's read-before-write. runtime-catalog seeds existing
`ServiceEntry` and `DestinationRule` specs into the build cache because domains it does not own can
contribute to the same external host; the plugin has no such neighbor to preserve, so generating those
documents from an empty cache is correct here.

## Findings

Evidence comes from running the `build-crs` goal against the repository's own corpus,
`integration-build-pipeline/src/test/resources/testConfigurations`.

### F1. Mapper chains abort the build (blocker)

`mvn compile` on `transformation/mapper-2` fails:

```text
Failed to build integration source ConfigMap for snapshot ... of chain 'mapper':
MapperException: Unable to serialize Atlas Map configuration.
```

Reproduced directly against `AtlasMapInterpreter`:

```text
JsonMappingException: Invalid type: class ...mapper.model.atlasmap.action.QIPDictionaryAction
  (through ... Mapping["inputField"]->JsonField["actions"]->ArrayList[0])
```

`META-INF/services/io.atlasmap.v2.Action` registers the QIP action subtypes. It lived in
`runtime-catalog`, `engine`, and `micro-engine`, but not in `integration-build-pipeline`, where both
the action classes and `AtlasMapInterpreter` live. Every host had to carry a registration for classes
it does not own, and the new host did not.

Fix: move that file into `integration-build-pipeline/src/main/resources/META-INF/services/`. One file,
and it covers every host.

`AtlasMapInterpreter` also threw `new MapperException(msg)` without the cause, which is why the real
error was invisible from a build log.

### F2. Route resources are silently missing, and a placeholder leaks into output (blocker)

`HttpRouteResourceBuilder`, `EgressRouteResourceBuilder`, and `EngineRoutesResourceBuilder` are gated on
`qip.control-plane.mesh-type=Istio` and `qip.istio.enabled=true`. The plugin's `application.yml` defines
neither, so those beans are never created. A chain with `externalRoute: true` builds without complaint
and produces no HTTPRoute. Confirmed with `triggers/http-trigger`: the route is collected
(`type=EXTERNAL_TRIGGER`) and then dropped.

The same file omits `spring.application.cloud_service_name`, `qip.gateway.*`,
`qip.chains.external-routes.base-path`, `camel.constants.request-filter-header.name`,
`qip.access-control.resource-type.chain`, and `protoc.*`. `AnnotationConfigApplicationContext` falls
back to non-strict placeholder resolution, so the misses become literal strings instead of startup
failures. Running `senders/service-call` produces:

```yaml
libraries:
- specificationId: 1acc68e3-...-petstore-1.0.7
  location: "http://${spring.application.cloud_service_name}:8080/v1/models/.../dto/jar"
```

That ConfigMap ships to the engine as written.

Fix: register a `PropertySourcesPlaceholderConfigurer` with `setIgnoreUnresolvablePlaceholders(false)`
so a missing key fails the build, then supply the keys the pipeline needs.

### F3. DTO libraries have no plugin-side story (major)

`LibraryLocationFromCatalogGetter` hardwires the library URL to the runtime-catalog HTTP endpoint, and
`LibraryLocationGetterProvider` carries a `TODO` for any other source. The plugin also never runs
`SystemModelCodeGenerator`, so nothing produces the jar. Spec-based service calls cannot work from a
Maven build regardless of how F2 is resolved.

### F4. The snapshot element graph contains duplicates (major)

`ChainModelMapper` sets `chain.elements` to a flat list containing every element, children included
(`chain.setElements(new ArrayList<>(elementsById.values()))`). `SnapshotBuildService.createElements`
then maps every entry to a new top-level element and also recurses into `getChildren()`, so each nested
element is created twice. A probe over a container plus one child:

```text
snapshot top-level elements : 2
distinct objects in graph   : 3
occurrences per snapshot id : {parent=1, child=2}
  child-copy A  parent=-       in=1 out=0    <- holds the connections
  child-copy B  parent=parent  in=0 out=0    <- the container's copy, empty
```

Which copy ends up with the connections depends on `createElementMap`'s last-writer-wins pass over an
iteration order that `ChainModelMapper` happens to produce, with children inserted before their
container. That coincidence is why `try-catch-finally-2` generates correct routes. Nothing asserts it.

`SnapshotBuildServiceTest` misses this because its fixtures pass a tree
(`chain.setElements(List.of(parent))`), not the flat list the reader produces.

Fix: build snapshot elements from roots only (`element.getParent().isEmpty()`), then flatten once for
`snapshot.setElements`.

### F5. No equivalence test against runtime-catalog (major)

Two implementations produce the same artifact and nothing compares them. The corpus exists, and
`snapshot-flow-build.yaml` already runs it through the catalog. A test that builds the same
`testConfigurations` through both paths and diffs the generated Camel XML would have caught F1, F2, and
F4 before review.

### F6. The module is invisible to CI (major)

`integration-build-maven-plugin` appears in `pom.xml` and nowhere else:

- no `integration-build-maven-plugin-build.yaml`, unlike every other Maven module;
- absent from the `main-build.yaml` matrix and its `paths:` filter;
- absent from `release-all.yaml`'s `ALL_MODULES`;
- absent from `snapshot-publish.yaml`'s module choices;
- absent from `scripts/modules.sh` (`QIP_BOM_MODULES`, `QIP_PARENT_CHILDREN`).

It is never built on a pull request and never released.

Two facts for whoever designs the wiring:

- The `main-build.yaml` bullet may not be a gap. `integration-build-pipeline` is absent from that matrix
  and its `paths:` filter too, so both library-style modules rely on their own PR workflow instead.
- The plugin's POM derives its library pin from its own `${revision}`, which holds only while the two
  modules sit on the same version. Release wiring that leaves it alone would publish a plugin demanding
  a library version that may never exist.

### F7. Output is not reproducible (medium)

Two consecutive runs over the same sources:

```text
SAME: Integration-qip-engine-default-v1.yaml
SAME: Service-qip-engine-default-v1.yaml
DIFF: ConfigMap-qip-engine-default-v1-jvs4blr.yaml
DIFF: ConfigMap-qip-engine-default-v1-src-cfg.yaml
```

`SnapshotBuildService` assigns `UUID.randomUUID()` and `Instant.now()` per build, so the snapshot id,
the label, and every `ElementInfo` bean change each time. Commit `b20f70f93` made resource names
reproducible; contents are not, so a GitOps diff is noisy on every build. Deriving the snapshot id from
a content hash would finish the job.

### F8. Configuration lives in two places, one of which is dead (medium)

The plugin's `application.yml` copied the catalog's whole `qip.cr.build.*` block, but
`ResourceBuildOptionsFactory` reads none of it. Only `<options>` in the POM has an effect. Two
consequences: the block is a maintenance trap, and `ResourceBuildOptions.namespace` is never set, so
`ServiceMonitorBuilder` falls back to the `{{ .Release.namespace }}` literal.

`qip.cr.naming.*`, `qip.cr.labels.*`, and `qip.export.legacy-format` are genuinely used and stay.

### F9. Service file filter and reader do not match (medium)

`ServiceFileUtil.isServiceFile` accepts `*.service.*`, `*.context-service.*`, and `*.mcp-service.*`, but
`MicroDomainResourcesBuildService.processServices` feeds all three to `integrationSystemReader::read`.
An MCP service file dropped into the source tree is accepted and registers a malformed integration
service. `ContextServiceReader` and `McpServiceReader` exist and go unused.

### F10. Shared-module changes that alter runtime-catalog behavior (medium)

These ride along in `integration-build-pipeline` and deserve an explicit decision:

- `cr/templates/service-monitor.hbs` drops `namespaceSelector`, which changes what the catalog generates
  today. `ServiceMonitorBuilder.getNamespace` and `TemplateData.namespace` are now dead.
- `MonitoringOptions.enabled` flips from `true` to `false`. Inert for the catalog, which binds an
  explicit value, but live for any other host.
- `ImagePoolPolicy` becomes `ImagePullPolicy`, renaming a REST field. The typo fix is right, but
  `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so an old payload or a deployment that sets
  `qip.cr.build.container.imagePoolPolicy` silently reverts to `IfNotPresent`. This needs a release note.
- `SourceDslConfigMapNamingStrategy` seeds from `String.hashCode()`, giving 2^32 distinct suffixes
  instead of true randomness. Collision risk is negligible at realistic chain counts, and
  reproducibility is worth it.
- `StringGenerator.generate(int)`, the `ThreadLocalRandom` overload, has no callers left.

### F11. Smaller items

- `IntegrationServiceCatalogImpl.findAllByIds` returns `null` entries for unknown ids, where
  `PersistentIntegrationServiceCatalog` drops them.
- `AssumeActualChainVersion` has no `@Order`, so it ties with `VersionFieldStrategy` at
  `LOWEST_PRECEDENCE` and the winner depends on scan order.
- `MavenPluginYamlMapperConfiguration` exists only so parameter-name matching resolves an ambiguous
  `YAMLMapper` injection. The catalog's same-named bean is configured differently, with `NON_NULL` and a
  filter provider, so `FileMigrationService` and the readers behave differently in the two hosts. A
  `@Qualifier` at the injection points removes the trap.
- One bad chain aborts the whole run. The catalog reports per-chain failures; the plugin should collect
  and report them all.
- Resources land loose in `target/` with no artifact attachment, so `mvn package` and `mvn deploy`
  publish nothing.
- `BuildCRsMojo` has no `property =` on its parameters, so nothing is settable from the command line,
  and no `skip` parameter or `threadSafe = true`.
- Chains are read from `${project.compileSourceRoots}`, which is `src/main/java`. `src/main/resources`
  or a dedicated directory would surprise fewer people.
- `ChainReader.getChainYamlFile` takes `chainFiles[0]`, so a directory holding two chain YAML files
  silently drops one. The plugin's directory walk makes this reachable.

## What works well

The seam is in the right place. `ResourceBuildContext.create(buildInfo, catalog)`, `BuildInfoFactory`,
`ElementDescriptorHelper`, and the move of `verification/` into the shared module are real
deduplication, and the catalog got simpler for it. `ImportSystemAdapter` and `SnapshotImpl` are thin and
well documented. The `Paths.get` to `/` change in `SourceMountPointGetter` is a genuine Windows fix that
the catalog benefits from too. The unit tests pass and read clearly.

## Suggested order

1. F1, so the corpus builds at all.
2. F2, strict placeholder resolution first, then the missing keys.
3. F4, before anything else depends on the current element graph.
4. F5, which locks in F1, F2, and F4.
5. F6, so the module is built and released like its siblings.
6. Decide on F3 and F7 before calling the plugin usable.
