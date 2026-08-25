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
plural to choose create versus update, then applies with
`PATCH_FORMAT_APPLY_YAML`, `fieldManager("kubectl-patch")`, and `force(true)`. No
`resourceVersion` is sent.

Anyone who writes one of these objects between Phase 1 and Phase 3 is silently overwritten. The
window is not small: it spans database reads, nine or more cluster round trips, YAML generation,
and a per-document write loop.

Contention is concentrated, and unevenly.

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
Phase 2, against data that is already many round trips old by the time it writes. That asymmetry
is the reason a precondition is needed here at all, and section 7 explains why it also forces the
retry to rebuild rather than re-send.

### Two API facts this design rests on

Both were verified against the apiserver source rather than assumed, because the obvious
approach turns out not to work.

**Server-Side Apply ignores `metadata.resourceVersion`.** `applyPatcher.applyPatchToCurrentObject`
resolves through the field manager and never reaches the optimistic-concurrency check that
`GuaranteedUpdate` performs for ordinary updates. Upstream's `TestApplyFailsWithVersionMismatch`
shows a version mismatch in an apply body surfacing as `BadRequest`, not the 409 Conflict a retry
loop keys on. Stamping `resourceVersion` onto an applied document is inert.

**A PUT does honor it.** `Store.Update` calls `GuaranteedUpdate`, which compares the submitted
`resourceVersion` against the stored one and returns 409 Conflict when they differ. This is the
mechanism the engine already relies on in `engine/.../KubeOperator.createOrReplaceCustomObject`
(`:129-155`).

A third fact bounds what `force` can do here: SSA conflicts fire only between *different* field
managers. All three patch sites hardcode `"kubectl-patch"`, so two concurrent runtime-catalog
deploys never conflict with each other whatever `force` is set to.

## Goals

- Detect, rather than silently absorb, a concurrent write to any object the build merged against.
- Concentrate that detection on the objects where contention actually is.
- Make runtime-catalog and the engine symmetric on the two objects they both write, the
  host-keyed `ServiceEntry` and `DestinationRule`, so neither silently clobbers the other.
- Leave a typed conflict exception for the retry step to catch.

## Non-goals

- The whole-build retry itself. It is a separate task, implemented and committed after this
  one lands, and is specified in section 7 only far enough to fix the seams this step must
  leave behind.
- Atomicity across documents. `deploy`'s loop stays non-atomic; see section 6.
- Any change to the engine.
- The HTTPRoute rule-ownership annotation
  (`2026-08-24-httproute-rule-ownership-annotation-design.md`) and the `ServiceEntry` /
  `DestinationRule` deletion gaps.

## Design

### 1. The write-mode split

A PUT overwrites the whole object; an apply merges. So PUT is safe only where the document being
written is a *complete* representation — either because it was read and modified, or because QIP
is the object's sole author.

| Object | Sole author | Write mode |
|---|---|---|
| Public / private / egress HTTPRoute | Runtime-catalog alone, emitting complete specs | PUT |
| `ServiceEntry`, `DestinationRule` | Runtime-catalog and the engine, both emitting complete specs | PUT |
| Source DSL and integrations-configuration ConfigMaps | QIP | PUT |
| `CamelKIntegration` | No — the Camel-K operator co-authors it | Apply |
| Service | No — the API server defaults `clusterIP` and peers | Apply |
| ServiceMonitor | QIP, but no contention and no benefit | Apply |

`CamelKIntegrationResourceBuilder` renders from a Handlebars template with a fixed field set
(`:122-142`): name, two labels, replicas, container, health, JVM settings, mounts, properties,
environment, and service account. A PUT of that document would strip every annotation, label,
and finalizer the Camel-K operator added. Today's apply leaves them alone because they belong to
a different field manager.

The split lands well: the PUT-eligible objects are exactly the contended ones — the host
resources above all, since they are the only ones another component writes — and the two that
must stay on apply are the two with the narrowest collision window.

### 2. Capturing versions in Phase 1

`MicroDomainResourceBuildContextFactory` records `metadata.resourceVersion` for every
PUT-eligible object it reads, keyed by kind and name.

Coverage differs by mode:

- `putHostResourceSpecsToBuildCache` runs in both modes, so `ServiceEntry` and `DestinationRule`
  are always covered. These are the cross-domain objects the engine also writes, so they are the
  ones that most need it, and they are covered in every mode.
- `addAppendConfigurationToContext` runs only under `APPEND`, so the tier HTTPRoutes and
  ConfigMaps are covered only there. Under `REWRITE` the build declares complete desired state
  and no Phase 1 read happened to take a version from.

That asymmetry forces the map to distinguish three states, not two. "No version" is ambiguous
between *Phase 1 looked and the object was not there* and *Phase 1 never looked*, and the write
path must treat those oppositely — the first is a create, the second is an update to an object
that probably exists. Collapsing them would make every `REWRITE` deploy after the first attempt
a create against a live object and fail.

So the map records observations rather than versions:

```java
Map<ResourceKey, Optional<String>> observations;
```

| Map state | Meaning |
|---|---|
| `Optional.of(v)` | Phase 1 read the object at version `v` |
| `Optional.empty()` | Phase 1 looked and the object did not exist |
| key absent | Phase 1 never looked (this kind is not read in this mode) |

### 3. Carrying versions to the write

The build cache is the wrong carrier. It holds `spec` maps consumed by builders, and the builders
must stay pure YAML generators with no knowledge of concurrency control. Threading versions
through them would touch every builder and buy nothing.

Instead the version map travels beside the YAML:

```java
public record BuiltResources(String yaml, Map<ResourceKey, Optional<String>> observations) { }

public record ResourceKey(String kind, String name) { }
```

`MicroDomainResourceBuildService.buildResources` returns `BuiltResources`.
`CustomResourceController.doDeployResource` passes it to `MicroDomainService.deploy`. The
`POST /custom-resources` build-only endpoint keeps returning the YAML string alone, reading
`.yaml()` off the record.

`deploy` parses the documents as it does today, and for each one looks up `(kind, name)` and
takes one of three branches:

- **Observed at version `v`** — stamp `v` onto `metadata.resourceVersion` and PUT. Conditional:
  a 409 means someone wrote during the build.
- **Observed absent** — create. A 409 `AlreadyExists` means another writer created the object
  during the build. That is the create-race, and it is reported as a conflict like any other.
- **Never observed** — GET the object at write time to learn its current version, then PUT with
  it (or create if the GET returns 404). This is last-write-wins in effect. It is what `REWRITE`
  already does today, and section 2 explains why that mode has nothing better to offer.

### 4. The write path

`KubeOperator.createOrUpdateCustomResource` gains a per-kind branch. For PUT-eligible kinds it
mirrors the engine: `replaceNamespacedCustomObject` when `resourceVersion` is set,
`createNamespacedCustomObject` when it is not.

The existing LIST-to-decide step goes away for the first two branches of section 3, because the
observation already says whether the object existed — one fewer round trip and one fewer TOCTOU
window on the paths that matter. The third branch replaces that LIST with a GET of the single
object, which is both cheaper and sufficient.

ConfigMaps get the same treatment through `replaceNamespacedConfigMap`.

Kinds that stay on apply keep their current code path, minus `force(true)`.

Note the API constraint behind the PUT branches: a PUT with no `resourceVersion` is rejected for
resources whose strategy sets `AllowUnconditionalUpdate() == false`, which is the default and
includes custom resources. That is why the third branch fetches a version rather than sending an
empty one.

### 5. Dropping `force(true)`

Removing it from the sites that keep applying makes runtime-catalog stop silently overriding
another field manager's ownership. It is worth stating plainly what this does and does not buy:
it has no effect on two concurrent runtime-catalog deploys, which share the `"kubectl-patch"`
manager and therefore never conflict with each other.

It carries a real risk on the Integration. If the Camel-K operator owns a field the template also
declares, the apply will begin returning 409 where it previously forced through, turning a
working deploy into a failing one. The behavior is correct — we should not be silently taking
fields from the operator — but it is a behavior change that can surface in production rather than
in tests.

Mitigation, in order of preference: land the change, watch for conflicts on Integration applies,
and if they appear, restore `force(true)` for that kind alone rather than globally. The narrow
restoration keeps the property everywhere it is safe.

Field-manager names stay as they are. Giving runtime-catalog a distinct manager would only matter
against another applier of the same objects, and the engine does not write the Integration or the
Service.

### 6. What this does not close

Stated so the next reader does not assume more coverage than exists.

- **`deploy`'s loop is not atomic.** A conflict on document five leaves documents one through
  four written. Per-object preconditions make each write safe and give nothing across the set.
  Re-applying the earlier documents on retry is idempotent, so this is recoverable, but the
  window is a partially updated domain. `/deploy-chains` compounds it: that endpoint is
  `@Transactional` (`CustomResourceController:83`), which rolls back the catalog database and
  cannot roll back Kubernetes.
- **The Integration and Service stay last-write-wins.** By design, per section 1.
- **`REWRITE` mode protects only the host resources.** The tier HTTPRoutes and ConfigMaps fall
  into section 3's third branch there, which is last-write-wins. The exposure is bounded: those
  objects have no writer outside runtime-catalog, so the only way to lose a write is two deploys
  of the same micro-domain overlapping. Closing it would mean reading them during a `REWRITE`
  build purely to obtain versions, which is a larger change than this step takes on. The
  cross-component race — the one the engine can actually lose — is on the host resources, and
  those are covered in both modes.

### 7. Seams for the retry step

The retry is a separate task and a separate commit. This step must leave two things in place for
it, and nothing more:

1. **A typed conflict.** A `KubeApiConflictException` in runtime-catalog, mirroring the engine's,
   raised for 409 from both the replace and the create paths. `MicroDomainService.deploy`
   currently catches `Exception` and wraps everything in `MicroDomainDeployError` (`:191-193`);
   it must let conflicts through distinguishably, either by rethrowing them or by giving
   `MicroDomainDeployError` a conflict subtype. Without this the retry cannot tell a conflict
   from a genuine failure and would retry unrecoverable errors.
2. **`buildResources` returning `BuiltResources`.** The retry rebuilds by calling it again, so
   the rebuild-and-rewrite unit is already expressed as one call returning everything `deploy`
   needs.

The retry itself — bounded rounds around `doDeployResource`, rebuilding the context each round —
is out of scope here.

It must rebuild, never re-send. The merge described earlier runs in Phase 2 against Phase 1 data,
so a built document carries a port list that is a snapshot of the world as it was before the
conflict. Re-applying it would send both the stale `resourceVersion` and the stale union: with the
precondition it 409s forever, and without one it would drop precisely the port whose arrival
caused the conflict. Only re-entering Phase 1 re-merges against what is actually in the cluster.

This is also why the retry belongs around `doDeployResource` rather than around
`MicroDomainService.deploy`. By the time control reaches `deploy`, the merge has already happened
and the stale data is baked into the YAML.

## Testing

**`KubeOperatorTest`, write mode per kind:**

1. A PUT-eligible custom object with a `resourceVersion` set calls `replaceNamespacedCustomObject`
   and never `patchNamespacedCustomObject`.
2. The same object with no `resourceVersion` calls `createNamespacedCustomObject`.
3. A 409 from the replace path surfaces as `KubeApiConflictException`, not a generic
   `KubeApiException`. Same for a 409 from the create path.
4. An apply-eligible kind (Integration) still goes through `PatchUtils.patch`, and the call no
   longer sets `force`.

**`MicroDomainResourceBuildContextFactoryTest`, version capture:**

5. Under `APPEND`, an existing tier HTTPRoute is recorded as `Optional.of(<its version>)`, and a
   tier that does not exist in the cluster is recorded as `Optional.empty()` — the two states
   must be distinguishable, not merged.
6. Under `REWRITE`, the tier HTTPRoute and ConfigMap keys are **absent from the map entirely**,
   while `ServiceEntry` and `DestinationRule` are still recorded. This is the test that pins
   section 2's three-state distinction as deliberate rather than accidental, and it is the one
   that fails if someone later collapses the map back to `Map<ResourceKey, String>`.

**`MicroDomainServiceTest`, the three write branches:**

7. A document observed at a version is written with that `resourceVersion` on its metadata via
   the replace path.
8. A document observed absent is written via the create path with no `resourceVersion`.
9. A document whose key is absent from the map triggers a write-time GET, and is then written via
   the replace path carrying the version that GET returned. This is the `REWRITE` path; without
   it, every `REWRITE` deploy after the first would attempt a create against a live object.

**Regression:**

10. The existing deploy tests pass unchanged for the Integration and Service, proving those kinds
    kept apply semantics.
