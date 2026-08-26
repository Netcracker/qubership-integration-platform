# Optimistic concurrency for micro-domain CR deployment — design

## Context

Deploying a snapshot to a micro-domain runs in three phases with no lock between them.

**Phase 1** is every cluster read, centralized in
`MicroDomainResourceBuildContextFactory.createResourceBuildContext`. Under `APPEND` it makes a
single `getMainIntegrationResources(name)` call (`:104-112`) — itself about seven GETs covering
the Integration, its Service, ServiceMonitor, ConfigMaps, and the three tier HTTPRoutes — and
feeds five separate merges from that one read. In both modes it also LISTs every `ServiceEntry`
and `DestinationRule` (`putHostResourceSpecsToBuildCache`).

**Phase 2** is pure. Builders merge their contribution onto the cached specs and emit one
multi-document YAML string. No I/O, deterministic given Phase 1.

**Phase 3** is `MicroDomainService.deploy` (`:185-194`): `Yaml.loadAll`, then a sequential
loop calling `KubeOperator.createOrUpdateResource` once per document. Each write LISTs the
plural to choose create versus update, then applies with `PATCH_FORMAT_APPLY_YAML`,
`fieldManager("kubectl-patch")`, and `force(true)`. No `resourceVersion` is sent.

Anyone who writes one of these objects between Phase 1 and Phase 3 is silently overwritten. The
window is not small: it spans database reads, nine or more cluster round trips, YAML generation,
and a per-document write loop.

### Where the contention is

The host-keyed `ServiceEntry` and `DestinationRule` are the worst case, and the only objects with
a writer outside runtime-catalog. `EgressTarget.hostResourceName()` — duplicated verbatim in
`engine/.../util/paths/` and `integration-build-pipeline/.../util/paths/` — derives the name from
the external host alone. So every domain targeting a given host writes the same two objects, and
a classic-domain engine targeting that host writes them too.

Everything else is runtime-catalog's alone. The three tier HTTPRoutes are per micro-domain,
shared by every snapshot that domain hosts; they collide only when two deploys of the same
micro-domain overlap. The engine does not write them: `IstioRoutesRegistrationService` exists
only in the `engine` module and serves classic domains, whose routes carry different names and
different path prefixes, and `micro-engine` contains no HTTPRoute code at all. The Integration
and Service are likewise per-domain.

On the host resources the two writers are asymmetric. The engine reads, merges, and writes with a
`resourceVersion` precondition, retrying up to `MAX_MERGE_ATTEMPTS` on conflict
(`IstioRoutesRegistrationService.upsertHostResource`). Runtime-catalog force-applies with no
precondition. So runtime-catalog silently clobbers the engine and never the reverse.

### Merging does not make the precondition unnecessary

Both writers merge rather than overwrite, and it is worth being precise about what that buys,
because it is easy to read as "the race is already handled."

Both are add-or-replace-by-port-number against the object's existing content.
`EgressRouteResourceBuilder:295` folds this build's ports into `existingSpec.path("ports")` keyed
on `number`, and `:332` does the same for `trafficPolicy.portLevelSettings` keyed on
`port.number`; `mergedEntries:368-382` keeps every existing entry whose key is not in the new set,
then appends the new ones. The engine's `upsertServiceEntry:353` and `upsertDestinationRule:372`
use the identical shape through their own `mergedEntries:385-402`. The two implementations are
duplicated per the `GatewayPathMatch` precedent, and they agree.

What that protects is *intra-build* clobbering: one chain's port cannot erase another chain's port
for the same host. What it does not touch is the Phase 1 to Phase 3 window, because
runtime-catalog merges against the spec seeded into the build cache during Phase 1. If the engine
adds port 8443 after that read, the catalog writes `stale_existing ∪ new`, and 8443 is gone. The
merge downgrades the failure from total loss to subset loss; it does not prevent it.

The engine does not have this problem, because its merge sits *inside* the retry loop: on conflict
`upsertHostResource` re-reads and re-merges against fresh state. Runtime-catalog merges once, in
Phase 2, against data that is already many round trips old by the time it writes.

### Why apply cannot carry the precondition

Server-Side Apply ignores `metadata.resourceVersion`. `applyPatcher.applyPatchToCurrentObject`
resolves through the field manager and never reaches the optimistic-concurrency check that
`GuaranteedUpdate` performs for ordinary updates; upstream's `TestApplyFailsWithVersionMismatch`
shows a version mismatch in an apply body surfacing as `BadRequest`, not the 409 Conflict a retry
loop keys on. Stamping a version onto an applied document is inert.

A PUT does honor it. `Store.Update` calls `GuaranteedUpdate`, which compares the submitted
`resourceVersion` against the stored one and returns 409 Conflict when they differ. This is the
mechanism the engine already relies on in `engine/.../KubeOperator.createOrReplaceCustomObject`
(`:129-155`).

Nor can `force(true)` be tuned into a substitute. SSA conflicts fire only between *different*
field managers, and all three patch sites hardcode `"kubectl-patch"`, so two concurrent
runtime-catalog deploys never conflict with each other whatever `force` is set to.

## Goals

- Detect, rather than silently absorb, a concurrent write to any object the build merged against.
- Make runtime-catalog and the engine symmetric on the two objects they both write, the
  host-keyed `ServiceEntry` and `DestinationRule`, so neither silently clobbers the other.
- Use one write mode and one conflict mechanism for every kind, rather than reasoning per kind
  about field ownership.
- Leave a typed conflict exception for the retry step to catch.

## Non-goals

- The whole-build retry itself. It is a separate task, implemented and committed after this one
  lands, and is specified in section 7 only far enough to fix the seams this step must leave.
- Atomicity across documents. `deploy`'s loop stays non-atomic; see section 6.
- Any change to the engine.
- The HTTPRoute rule-ownership annotation
  (`2026-08-24-httproute-rule-ownership-annotation-design.md`) and the `ServiceEntry` /
  `DestinationRule` deletion gaps.

## Design

### 1. One write mode

Every kind moves to read-modify-write PUT carrying a `resourceVersion` precondition. The apply
path goes with it: `PatchUtils`, the `kubectl-patch` field manager, and `force(true)` are deleted
rather than tuned, and with them the need to reason about which manager owns which field.

This subsumes what was originally scoped as a separate step. "Drop `force(true)`" is not a change
to make once the apply path no longer exists.

### 2. What a PUT can and cannot disturb

An earlier draft of this design kept the Integration and Service on apply, on the belief that a
PUT of a generated document would strip fields other actors own. Checking that belief against
source retired it. The findings are recorded here because they are the whole justification for
section 1.

| Concern | Finding |
|---|---|
| Integration `status` | The Integration CRD enables the `/status` subresource (`+kubebuilder:subresource:status` in `pkg/apis/camel/v1/integration_types.go`), so a PUT to the main resource structurally cannot modify it. `status.integrationKit`, `status.dependencies`, `status.traits`, `status.profile`, digest, and observedGeneration are all safe. |
| Integration `spec` | The Camel-K operator does not write back to `spec.traits`, `spec.dependencies`, `spec.profile`, or `spec.sources`. Those are desired state; the operator mirrors observed state into `status.*`. |
| Service allocated fields | `patchAllocatedValues` in `pkg/registry/core/service/storage/storage.go` copies `clusterIP`, `ports[].nodePort`, and `healthCheckNodePort` from the stored object when an incoming PUT omits them. They are preserved, not rejected. |
| Integration metadata | Not protected by any of the above. A PUT replaces `metadata` wholesale, dropping the operator's `camel.apache.org/operator.id`, `platform.id`, `integration-profile.id` annotations and its `created.by.*` / `runtime.*` labels. See section 5. |

### 3. Phase 1 captures version and metadata

`MicroDomainResourceBuildContextFactory` records, for every object it reads, both
`metadata.resourceVersion` and the object's full `metadata`, keyed by kind and name.

Coverage differs by mode:

- `putHostResourceSpecsToBuildCache` runs in both modes, so `ServiceEntry` and `DestinationRule`
  are always covered. These are the cross-domain objects the engine also writes, so they are the
  ones that most need it, and they are covered in every mode.
- `addAppendConfigurationToContext` runs only under `APPEND`, so the Integration, Service,
  ServiceMonitor, ConfigMaps, and tier HTTPRoutes are covered only there. Under `REWRITE` the
  build declares complete desired state and no Phase 1 read happened to take a version from.

That asymmetry forces the record to distinguish three states, not two. "No version" is ambiguous
between *Phase 1 looked and the object was not there* and *Phase 1 never looked*, and the write
path must treat those oppositely — the first is a create, the second is an update to an object
that probably exists. Collapsing them would make every `REWRITE` deploy after the first attempt a
create against a live object and fail.

```java
public record ResourceKey(String kind, String name) { }

Map<ResourceKey, Optional<V1ObjectMeta>> observations;
```

The observation is the live `metadata` itself rather than a record pairing a version with it:
`V1ObjectMeta` already carries `resourceVersion`, and holding it separately would be two sources
of truth for one value.

| Map state | Meaning |
|---|---|
| `Optional.of(meta)` | Phase 1 read the object; `meta.getResourceVersion()` is its version |
| `Optional.empty()` | Phase 1 looked and the object did not exist |
| key absent | Phase 1 never looked (this kind is not read in this mode) |

Reads that feed this record go through a generic tree (`KubeCustomObject` or an equivalent map),
never the `CamelKIntegration` POJO. That class models four spec fields and no status, so
round-tripping an Integration through it would lose anything Camel-K adds to spec later — a
failure that would appear only after an upstream version bump.

### 4. Carrying it to the write

The build cache is the wrong carrier. It holds `spec` maps consumed by builders, and the builders
must stay pure YAML generators with no knowledge of concurrency control. Threading versions
through them would touch every builder and buy nothing.

Instead the record travels beside the YAML:

```java
public record BuiltResources(String yaml, Map<ResourceKey, Optional<V1ObjectMeta>> observations) { }
```

`MicroDomainResourceBuildService.buildResources` returns `BuiltResources`.
`CustomResourceController.doDeployResource` passes it to `MicroDomainService.deploy`. The
`POST /custom-resources` build-only endpoint keeps returning the YAML string alone, reading
`.yaml()` off the record.

`deploy` parses the documents as it does today, and for each one looks up `(kind, name)` and takes
one of three branches:

- **Observed at a version** — overlay metadata per section 5, stamp the version onto
  `metadata.resourceVersion`, and PUT. A 409 means someone wrote during the build.
- **Observed absent** — create. A 409 `AlreadyExists` means another writer created the object
  during the build. That is the create-race, and it is reported as a conflict like any other.
- **Never observed** — GET the object at write time to obtain its current version and metadata,
  then PUT (or create if the GET returns 404). This is last-write-wins in effect, which is what
  `REWRITE` already does today.

The existing LIST-to-decide step goes away for the first two branches, because the observation
already says whether the object existed — one fewer round trip and one fewer TOCTOU window on the
paths that matter. The third branch replaces that LIST with a GET of the single object.

A PUT with no `resourceVersion` is rejected for resources whose strategy sets
`AllowUnconditionalUpdate() == false`, which is the default and includes custom resources. That is
why the third branch fetches a version rather than sending an empty one.

### 5. Metadata preservation

Before a PUT, the document's `metadata` is replaced by the observed metadata with the generated
labels and annotations overlaid onto it. Generated values win on key collision; everything else
the live object carried survives.

The operator does repair its own metadata if we drop it — `FilteringFuncs` in
`pkg/platform/operator.go` watches `camel.apache.org/operator.id`, `integration-profile.id`, and
`integration-profile.namespace`, forces a reconcile when they change or vanish, and restores them
regardless of `status.phase`. So this is not a correctness requirement. It is worth doing anyway,
for two reasons:

- The repair is triggered *by* the damage. Stripping the annotations on every deploy provokes an
  extra reconcile each time, and where operator affinity changes it can escalate to a rebuild or
  redeployment.
- In a namespace with more than one operator, an Integration whose `operator.id` disappears is
  picked up by whichever operator handles unannotated resources, which may not be the one it was
  pinned to.

Preserving metadata makes the write a no-op from the operator's point of view instead of a change
it has to notice and repair, and Phase 1 has already read the object, so it costs two lines.

### 6. What this does not close

Stated so the next reader does not assume more coverage than exists.

- **`deploy`'s loop is not atomic.** A conflict on document five leaves documents one through four
  written. Per-object preconditions make each write safe and give nothing across the set.
  Re-applying the earlier documents on retry is idempotent, so this is recoverable, but the window
  is a partially updated domain. `/deploy-chains` compounds it: that endpoint is `@Transactional`
  (`CustomResourceController:83`), which rolls back the catalog database and cannot roll back
  Kubernetes.
- **`REWRITE` mode protects only the host resources.** Everything else falls into section 4's
  third branch there, which is last-write-wins. The exposure is bounded: those objects have no
  writer outside runtime-catalog, so the only way to lose a write is two deploys of the same
  micro-domain overlapping. Closing it would mean reading them during a `REWRITE` build purely to
  obtain versions. The cross-component race — the one the engine can actually lose — is on the
  host resources, and those are covered in both modes.

### 7. Seams for the retry step

The retry is a separate task and a separate commit. This step must leave two things in place for
it, and nothing more:

1. **A typed conflict.** A `KubeApiConflictException` in runtime-catalog, mirroring the engine's,
   raised for 409 from both the replace and the create paths. `MicroDomainService.deploy`
   currently catches `Exception` and wraps everything in `MicroDomainDeployError` (`:191-193`); it
   must let conflicts through distinguishably, either by rethrowing them or by giving
   `MicroDomainDeployError` a conflict subtype. Without this the retry cannot tell a conflict from
   a genuine failure and would retry unrecoverable errors.
2. **`buildResources` returning `BuiltResources`.** The retry rebuilds by calling it again, so the
   rebuild-and-rewrite unit is already expressed as one call returning everything `deploy` needs.

The retry itself — bounded rounds around `doDeployResource`, rebuilding the context each round —
is out of scope here.

It must rebuild, never re-send. The merge described in the Context runs in Phase 2 against Phase 1
data, so a built document carries a port list that is a snapshot of the world as it was before the
conflict. Re-applying it would send both the stale `resourceVersion` and the stale union: with the
precondition it 409s forever, and without one it would drop precisely the port whose arrival caused
the conflict. Only re-entering Phase 1 re-merges against what is actually in the cluster.

This is also why the retry belongs around `doDeployResource` rather than around
`MicroDomainService.deploy`. By the time control reaches `deploy`, the merge has already happened
and the stale data is baked into the YAML.

## Testing

**`KubeOperatorTest`, write mode:**

1. An object with a `resourceVersion` set is written through the replace path, and
   `PatchUtils.patch` is never called for any kind.
2. An object with no `resourceVersion` is written through the create path.
3. A 409 from the replace path surfaces as `KubeApiConflictException`, not a generic
   `KubeApiException`. Same for a 409 from the create path.

**`MicroDomainResourceBuildContextFactoryTest`, observation capture:**

4. Under `APPEND`, an existing tier HTTPRoute is recorded as present with its version *and* its
   metadata; a tier that does not exist is recorded as `Optional.empty()`. The two states must be
   distinguishable, not merged.
5. Under `REWRITE`, the Integration, Service, ConfigMap, and HTTPRoute keys are **absent from the
   map entirely**, while `ServiceEntry` and `DestinationRule` are still recorded. This pins
   section 3's three-state distinction as deliberate, and it is the test that fails if someone
   later collapses the map to two states.

**`MicroDomainServiceTest`, the three write branches and metadata:**

6. A document observed at a version is written with that `resourceVersion` via the replace path.
7. A document observed absent is written via the create path with no `resourceVersion`.
8. A document whose key is absent from the map triggers a write-time GET and is then written via
   the replace path with the version that GET returned. Without this branch, every `REWRITE`
   deploy after the first would attempt a create against a live object.
9. An Integration observed with operator annotations (`camel.apache.org/operator.id` and peers) is
   written with those annotations still present, alongside the generated labels. This is the test
   that fails if metadata overlay regresses to metadata replacement.

**Regression:**

10. The existing deploy tests pass unchanged, proving the switch from apply to PUT did not alter
    what any document declares.
