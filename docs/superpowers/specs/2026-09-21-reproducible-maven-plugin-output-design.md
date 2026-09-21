# Reproducible output from the integration build Maven plugin

Design for review finding F7, recorded in
[integration-build-maven-plugin/FIXES.md](../../../integration-build-maven-plugin/FIXES.md).

## Problem

Two builds of the same sources produce different resources. Measured on one chain, with nothing else
changed between runs:

```text
SAME: HTTPRoute, Integration, Service
DIFF: ConfigMap-...-src-cfg     (integrations-config)
DIFF: ConfigMap-...-gsfpqzb     (the Camel source)
```

Fifteen UUIDs and two timestamps move. Resource *names* are already stable, so the churn is entirely in
resource *contents*, which is what a GitOps diff reads. Every rebuild looks like a change.

Six generators produce the churn. Five of them are in `integration-build-pipeline`, shared with
runtime-catalog:

| Generator | Site | Reaches the output as |
| --- | --- | --- |
| snapshot and element ids | `SnapshotBuildService` | `snapshotId` label, `ElementInfo-<id>` beans, step ids |
| build timestamp | `BuildInfoFactory` | `DeploymentInfo` name and timestamp |
| route id | `ChainRouteBuilder`, 2 sites | XML route id, `direct:<id>` endpoints |
| route registration id | `RoutesGetterService`, 3 sites | `RouteRegistrationInfo-<id>` beans |
| AtlasMap mapping id | `AtlasMapInterpreter:419` | `"id": "mapping.<uuid>"` |
| mapper element id | `MapperInterpreterHelper.mappingId` | `internalProperty_mappingId` property |

Only the first is plugin-only, which is why the plugin cannot reach reproducibility on its own. The
`BuildInfoFactory` change is additive, so it is the one shared generator that needs no bean.

## Scope

In scope: the Maven plugin's generated resources become byte-identical for identical input.

Out of scope, and load-bearing for the design: **runtime-catalog's generated output does not change.**
Its snapshot ids come from JPA and stay there. Every shared-module change below is either additive or
defaulted to today's behavior.

## Decisions

1. An id changes when the chain's content changes. The snapshot id carries that; element ids do not, so
   editing one chain does not renumber the elements it did not touch.
2. Element id: `UUID.nameUUIDFromBytes(originalId)`.
3. Snapshot id: `UUID.nameUUIDFromBytes(chainId + digest)`. Both parts are fixed width, a 36-character
   id and a 64-character digest, so they cannot run together ambiguously. A variable-length part added
   later would need a separator.
4. Digest: SHA-256 over the chain directory, walked in sorted path order, with each file's path relative
   to the directory fed in alongside its bytes. That directory is what `ChainReader.read` consumes, so
   the digest cannot drift from the input, and it covers the separately exported property files —
   scripts, mapping descriptions — that a digest of the parsed model would miss.
5. Timestamps come from `project.build.outputTimestamp`, Maven's reproducible-build property, falling
   back to `Instant.now()` when it is unset. Reproducibility is therefore opt-in through the standard
   mechanism, and a project that does not set it keeps a real build clock.
6. The four remaining generators move behind beans. The shared module keeps a default implementation
   per bean that draws a random UUID, so runtime-catalog is unaffected; the plugin overrides them.

## Design

### The generator beans

Four interfaces in `integration-build-pipeline`, each with a default `@Component` implementation that
returns a random UUID.

| Bean | Call site | Seed | Plugin derivation |
| --- | --- | --- | --- |
| `RouteIdGenerator` | `ChainRouteBuilder`, 2 sites | the head element's element id | `nameUUIDFromBytes("route" + elementId)` |
| `RouteRegistrationIdGenerator` | `RoutesGetterService`, 3 sites | the element's element id | `nameUUIDFromBytes("registration" + elementId)` |
| `MappingIdGenerator` | `AtlasMapInterpreter:419` | `action.getId()` | `"mapping." + nameUUIDFromBytes(actionId)` |
| `MapperMappingIdGenerator` | `MapperInterpreterHelper.mappingId` | the mapper element's id | `nameUUIDFromBytes("mapping" + elementId)` |

Three of the four seed from an element id, so each carries a discriminator naming what it produces.
Without one, an HTTP trigger — which is both a route head and a route registration — would drive two
generators from the identical string and get one UUID for both. Nothing breaks, since a `direct:`
endpoint and a bean name never meet, but the coincidence is the kind that costs someone an afternoon.
This is the opposite of the element and snapshot ids, where the seeds already differ and a prefix would
be ceremony.

An element id is a UUID and unique across chains and domains, so it needs nothing else alongside it.
Each element yields at most one route registration: the three call sites filter on mutually exclusive
element types, and the service site iterates a `groupingBy` partition, so an element appears under
exactly one service.

**`RouteIdGenerator` seeds from the element id, not the original id.** The route-id namespace already
holds raw element ids: `ChainRouteBuilder` names container and branch routes
`new ChainRoute(containerElement.getId())` and the reuse route
`new ChainRoute(startElement.getOriginalId())`, and all of them become `direct:` endpoints. Seeding from
the original id would emit `nameUUIDFromBytes(originalId)`, which is exactly the element's snapshot id
under decision 2, and therefore exactly the endpoint a container sub-route already uses for that
element. Seeding from the element id lands outside both namespaces.

**`MappingIdGenerator` seeds from the action id rather than an ordinal.** An ordinal would put a counter
in a singleton that runtime-catalog uses concurrently. The action id is stateless, required by the
parser so any description that parses has one, unique within the description by construction, and stable
across edits, so adding a mapping does not renumber the ones after it. The generator returns the whole
value including the `mapping.` prefix, so one place knows that format.

**The trigger call site needs the element back in scope.** `buildTriggersRoutes` runs
`.map(TriggerUtils::getHttpTriggerRoute)` and then builds the `Route` from that, so the element is gone
by the time the id is set. Collapsing the two `map` calls into one that closes over the element restores
it. The other two sites already hold theirs.

### Overriding in the plugin

The plugin's implementations carry `@Primary`. Injection stays by type, needs no qualifier, and does not
depend on component-scan ordering the way `@ConditionalOnMissingBean` would. It is also lighter than the
scan-exclusion the route builders needed, because these are beans rather than subclasses competing with
their own base class.

### Plugin-only pieces

`SnapshotBuildService` derives the snapshot and element ids per decisions 2 and 3, and takes the chain
digest and the build timestamp as parameters. `MicroDomainResourcesBuildService` computes the digest per
chain directory and carries it alongside the chain it belongs to. `BuildCRsMojo` reads
`project.build.outputTimestamp`, accepting epoch seconds or an ISO-8601 instant and treating a blank or
single-character value as unset, which is the convention projects use for a placeholder.

The one additive shared-module edit outside the beans: `BuildInfoFactory` gains a three-argument
`createBuildInfo` that takes a timestamp. The existing two-argument method stays and delegates with
`Instant.now()`, and runtime-catalog calls that one, so its behavior is unchanged.

## Testing

- A unit test per plugin generator, asserting the derivation and that repeated calls with one seed agree.
- A test that `RouteIdGenerator`'s output for an element differs from that element's own id, which is the
  collision this design exists to avoid and the one a future reader is most likely to reintroduce.
- The end-to-end check: build the `testConfigurations` corpus twice with `outputTimestamp` set and assert
  byte-identical output. Nothing short of this demonstrates F7 closed.

## Risks

Most of the touched files are shared with runtime-catalog. The defaults keep its behavior identical, but
a mistake in a default implementation surfaces in the catalog's generated resources rather than the
plugin's. `snapshot-flow-build.yaml`, which runs the corpus through micro-engine, is the net that would
catch it.

Deriving route ids from the head element assumes each element heads at most one route. A spike found no
duplicate `direct:` endpoints across all 25 source ConfigMaps the corpus generates, which is evidence
rather than proof; a chain shape the corpus does not cover could still break it.
