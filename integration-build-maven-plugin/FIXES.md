# Maven plugin review: fix ledger

Status of every finding in [REVIEW.md](REVIEW.md). Update a row when its status changes, and record the
commit that changed it.

Statuses: `open`, `fixed`, `partial`, `out of scope`.

| ID | Finding | Severity | Status | Commit |
| --- | --- | --- | --- | --- |
| F1 | AtlasMap custom actions unregistered, so mapper chains abort the build | blocker | fixed | `bcdb973dc` |
| F2 | Route resources never generated; `${spring.application.cloud_service_name}` leaks into output | blocker | fixed | `5ef3c4185` |
| F3 | DTO library location hardwired to runtime-catalog, no codegen on the plugin side | major | out of scope | |
| F4 | Nested elements duplicated in the snapshot element graph | major | open | |
| F5 | No equivalence test against the runtime-catalog pipeline | major | open | |
| F6 | Module missing from every CI workflow and from `scripts/modules.sh` | major | open | |
| F7 | Generated resource contents differ between runs | medium | open | |
| F8 | Dead `qip.cr.build` block in the plugin's `application.yml` | medium | fixed | `bcdb973dc` |
| F9 | Service file filter accepts context and MCP services, reader handles only integration systems | medium | open | |
| F10 | Shared-module changes alter runtime-catalog behavior | medium | open | |
| F11 | Smaller items, see below | minor | partial | `bcdb973dc` |
| F12 | ServiceEntry and DestinationRule built from an unseeded cache | medium | open | |

## F11 breakdown

| Item | Status | Commit |
| --- | --- | --- |
| `IntegrationServiceCatalogImpl.findAllByIds` returns `null` entries for unknown ids | fixed | `bcdb973dc` |
| `AssumeActualChainVersion` has no `@Order` | open | |
| `MavenPluginYamlMapperConfiguration` relies on parameter-name bean matching | open | |
| One bad chain aborts the whole run | open | |
| Generated resources are not attached as build artifacts | open | |
| `BuildCRsMojo` lacks `property =`, `skip`, and `threadSafe = true` | open | |
| Chains are read from `${project.compileSourceRoots}` | open | |
| `ChainReader.getChainYamlFile` silently drops a second chain YAML | open | |

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

Carried forward from reviewing this fix: F12 below, and a stray blank line in
`OptionControlledHttpRouteResourceBuilder.enabled`.

## Deferred

### F6, CI and release wiring

A first pass wired the module into `_maven-module-build.yaml` and `_maven-module-release.yaml`,
`release-all.yaml`, `snapshot-publish.yaml`, and `scripts/modules.sh`. It was reverted on
September 18, 2026: the team is developing a different approach. F6 stays open.

That pass also had to change the plugin's library pin from `${revision}${changelist}` to a literal
version, so `integration-build-pipeline-release.yaml` could move it through `sync-poms` and a release
could resolve a library that exists. Whatever shape the new approach takes has to answer the same
question, so the second note under F6 in [REVIEW.md](REVIEW.md) records it.

## Out of scope

### F3, DTO library generation

Deferred by decision, September 18, 2026. Chains whose service calls rely on a generated DTO library
cannot be built by the plugin until this is addressed.

## Notes

Removing the dead `qip.cr.build` block under F8 does not address F2. F2 is about keys that are missing
and needed: `qip.control-plane.mesh-type`, `qip.istio.enabled`, `qip.gateway.*`,
`spring.application.cloud_service_name`, `qip.chains.external-routes.base-path`, and
`camel.constants.request-filter-header.name`.
