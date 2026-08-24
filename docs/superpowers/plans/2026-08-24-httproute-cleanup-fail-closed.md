# Fail-closed HTTPRoute cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `MicroDomainService` from stripping HTTPRoute rules when it cannot determine which other snapshots still own them.

**Architecture:** Two guards inside `MicroDomainService`. `snapshotRoutes` stops logging resolution failures as a side effect and returns them as a value, so `deleteChainSnapshotHttpRoutes` can skip the whole domain instead of stripping with partial data. `remainingSnapshotIds` starts distinguishing "the ConfigMap lists no other snapshots" from "there is no ConfigMap", so the second case skips too.

**Tech Stack:** Java 21 (records), Spring, JUnit 5, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-08-24-httproute-cleanup-fail-closed-design.md`

## Global Constraints

- Work in the worktree `C:\Users\ssn6\Workspace\qip\qip-monorepo\.claude\worktrees\feat-migrate-to-istio` on branch `feat-migrate-to-istio`. Do not create a new worktree or branch.
- Build and test with `mvn -pl runtime-catalog test`. There is no `mvnw` wrapper in this repo.
- Every commit message must contain the literal token `@cf_ignore`. The CyberFerret pre-commit hook flags a pre-existing third-party author email in `package-lock.json` that has nothing to do with this work; without the token, every commit is rejected.
- Only `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java` and its two test classes change. Do not touch the builders in `integration-build-pipeline`, the engine, or any CR template.
- Out of scope: the `qip.io/rule-owners` annotation described in `docs/superpowers/specs/2026-08-24-httproute-rule-ownership-annotation-design.md`. Do not start it.
- Log messages are developer-facing English text. Reproduce the three `log.warn` calls in this plan character for character; they were written against the repository style guide and are not paraphrasable.
- All three guards log at `warn`, never `error`. The program handles these conditions deliberately.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java` | Micro-domain resource lifecycle, including HTTPRoute tier cleanup | Modified by both tasks |
| `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceHttpRouteTest.java` | Unit tests for the tier cleanup path | Task 1 |
| `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceTest.java` | Unit tests for the wider service, including `deleteChainSnapshot` | Task 2 |

Two tasks, split where a reviewer could accept one and reject the other: Task 1 fixes the database-resolution guard, Task 2 fixes the missing-ConfigMap guard. Task 1 must land first because Task 2's skip decision calls the method Task 1 reshapes.

---

## Task 1: Fail closed when snapshot IDs do not resolve

**Files:**
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java:432-489`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceHttpRouteTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `private record ResolvedRoutes(List<Route> routes, List<String> unresolvedIds)` with `boolean isComplete()`, and `private ResolvedRoutes snapshotRoutes(Collection<String> snapshotIds)`. Task 2 relies on `deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds)` keeping exactly this signature.

### Critical constraint before you write any code

`snapshotRoutes` must compute `unresolvedIds` **only** when `snapshots.size() < snapshotIds.size()`, exactly as the current code does inside its `if`. The test suite stubs the repository with bare `mock(Snapshot.class)` objects whose `getId()` returns `null`. If you compute `resolvedIds` unconditionally, every returned row contributes `null`, every requested ID looks unresolved, and all thirteen currently passing tests fail closed. The size check is what keeps them green.

- [ ] **Step 1: Write the three failing tests**

Add to `MicroDomainServiceHttpRouteTest`, after `deleteChainSnapshotStripsOnlyThePathsNoRemainingSnapshotOwns` (around line 215):

```java
    // Fail closed: a remaining snapshot with no catalog row contributes no paths, so the
    // subtraction that protects a shared path is incomplete and no rule may be stripped.
    @Test
    void deleteChainSnapshotStripsNothingWhenARemainingSnapshotDoesNotResolve() {
        when(snapshotRepository.findAllByIdIn(List.of("snapshot-1")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(snapshotRepository.findAllByIdIn(Set.of("snapshot-2")))
                .thenReturn(List.of());
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1", Set.of("snapshot-2"));

        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteCustomObject(any(), any(), any(), any());
    }

    // The guard keys on completeness, not on emptiness: one resolved remaining snapshot does
    // not make the other one's paths visible.
    @Test
    void deleteChainSnapshotStripsNothingWhenOnlySomeRemainingSnapshotsResolve() {
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot resolved =
                mock(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class);
        when(resolved.getId()).thenReturn("snapshot-2");
        when(snapshotRepository.findAllByIdIn(List.of("snapshot-1")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(snapshotRepository.findAllByIdIn(Set.of("snapshot-2", "snapshot-3")))
                .thenReturn(List.of(resolved));
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        microDomainService.deleteChainSnapshotHttpRoutes(
                DOMAIN, "snapshot-1", Set.of("snapshot-2", "snapshot-3"));

        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteCustomObject(any(), any(), any(), any());
    }

    // The removed snapshot itself may be missing from the catalog. Nothing is stripped then
    // either, and the leftover rules stay until the domain is redeployed.
    @Test
    void deleteChainSnapshotStripsNothingWhenTheRemovedSnapshotDoesNotResolve() {
        when(snapshotRepository.findAllByIdIn(List.of("snapshot-1"))).thenReturn(List.of());

        microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1", Set.of());

        verify(kubeOperator, never()).getCustomObject(any(), any(), any(), any());
        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteCustomObject(any(), any(), any(), any());
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn -pl runtime-catalog test -Dtest=MicroDomainServiceHttpRouteTest`

Expected: FAIL. `deleteChainSnapshotStripsNothingWhenARemainingSnapshotDoesNotResolve` and `deleteChainSnapshotStripsNothingWhenOnlySomeRemainingSnapshotsResolve` fail because the current code strips `/qip-routes/a` anyway. `deleteChainSnapshotStripsNothingWhenTheRemovedSnapshotDoesNotResolve` may already pass, because an unresolved removed snapshot yields no paths and the existing early return fires — that is expected, and Step 3 makes it pass for the right reason.

- [ ] **Step 3: Add the `ResolvedRoutes` record**

In `MicroDomainService`, immediately above the `snapshotRoutes` method:

```java
    /**
     * The routes a set of snapshot IDs resolved to, plus the IDs that had no catalog row. An
     * incomplete result means some other snapshot's paths are invisible, so the caller must not
     * strip anything: it cannot tell a path only the removed snapshot owns from one a live chain
     * still serves.
     */
    private record ResolvedRoutes(List<Route> routes, List<String> unresolvedIds) {
        boolean isComplete() {
            return unresolvedIds.isEmpty();
        }
    }
```

- [ ] **Step 4: Replace `snapshotRoutes`**

Replace the whole method at `MicroDomainService.java:459-489`, javadoc included:

```java
    /**
     * Resolves {@code snapshotIds} to the gateway routes they define, reporting any ID the catalog
     * database has no row for rather than logging and continuing. The caller decides what an
     * incomplete result means, because the answer differs between the removed snapshot and the
     * ones that remain.
     *
     * <p>Unresolved IDs are computed only when fewer rows came back than were asked for. Reading
     * {@code getId()} on every row unconditionally would be equivalent, but the test suite stubs
     * the repository with bare mocks whose ID is {@code null}, and they would all read as
     * unresolved.
     */
    private ResolvedRoutes snapshotRoutes(Collection<String> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return new ResolvedRoutes(List.of(), List.of());
        }
        var snapshots = snapshotRepository.findAllByIdIn(snapshotIds);
        List<Route> routes = snapshots.stream()
                .flatMap(snapshot -> routesGetterService
                        .getRoutes(new SnapshotAdapter(snapshot), integrationServiceCatalog).stream())
                .toList();
        if (snapshots.size() >= snapshotIds.size()) {
            return new ResolvedRoutes(routes, List.of());
        }
        Set<String> resolvedIds = snapshots.stream()
                .map(AbstractEntity::getId)
                .collect(Collectors.toSet());
        List<String> unresolvedIds = snapshotIds.stream()
                .filter(id -> !resolvedIds.contains(id))
                .toList();
        return new ResolvedRoutes(routes, unresolvedIds);
    }
```

- [ ] **Step 5: Add the two guards to `deleteChainSnapshotHttpRoutes`**

Replace lines 432-434 of `MicroDomainService.java` (the method's first three lines, from the signature through the `retainedRoutes` assignment) with:

```java
    void deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds) {
        ResolvedRoutes own = snapshotRoutes(List.of(snapshotId));
        ResolvedRoutes retained = snapshotRoutes(remainingSnapshotIds);
        if (!retained.isComplete()) {
            log.warn("Snapshot(s) {} listed for micro-domain '{}' have no catalog row, so the paths they own "
                    + "are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping one a "
                    + "live chain still serves. Redeploy the domain to clear the leftovers.",
                    retained.unresolvedIds(), name, snapshotId);
            return;
        }
        if (!own.isComplete()) {
            log.warn("Removed snapshot '{}' has no catalog row for micro-domain '{}', so the paths it owns are "
                    + "unknown. Its HTTPRoute rules stay in place. Redeploy the domain to clear them.",
                    snapshotId, name);
            return;
        }
        List<Route> ownRoutes = own.routes();
        List<Route> retainedRoutes = retained.routes();
```

Leave everything from the `publicPaths` assignment down unchanged.

Do not reorder the two `snapshotRoutes` calls. `deleteChainSnapshotStripsOnlyThePathsNoRemainingSnapshotOwns` stubs `getRoutes` with consecutive returns and depends on the removed snapshot being resolved first. Only the two `isComplete` checks are ordered deliberately, retained before own, so an operator seeing both conditions gets the message about the dangerous one.

- [ ] **Step 6: Update the `deleteChainSnapshotHttpRoutes` javadoc**

Append this paragraph to the existing javadoc block at `MicroDomainService.java:420-431`, after the sentence ending "other chains included.":

```java
     * <p>When either resolution is incomplete the method strips nothing at all, across every tier.
     * An unresolved snapshot's routes are exactly what cannot be seen, so there is no way to
     * attribute it to one tier and spare the others. That leaves stale rules behind, and nothing
     * reconciles these CRs afterwards, so they persist until the domain is deleted or the owning
     * chain is redeployed. It is the safer half of the trade: the alternative removes a rule a
     * running chain still serves.
```

- [ ] **Step 7: Run the full module test suite**

Run: `mvn -pl runtime-catalog test`

Expected: PASS, with no failures. The three new tests pass, and every pre-existing test in `MicroDomainServiceHttpRouteTest` and `MicroDomainServiceTest` still passes. If any previously green test now fails, the most likely cause is computing `unresolvedIds` unconditionally — re-read the Step 4 code and the constraint above it.

- [ ] **Step 8: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceHttpRouteTest.java
git commit -m "fix(runtime-catalog): skip HTTPRoute cleanup when snapshot ownership is unknown

deleteChainSnapshotHttpRoutes warned that some snapshot IDs had no catalog
row and then stripped rules anyway. A path owned only by an unresolved
snapshot is invisible to the subtraction, so the rule serving it was
removed while a live chain still needed it.

snapshotRoutes now returns the unresolved IDs instead of logging them, and
the caller skips every tier when either resolution is incomplete. Stale
rules are left behind instead, which is recoverable by redeploying the
domain.

@cf_ignore -- pre-existing third-party author email in package-lock.json,
untouched by this commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Fail closed when the domain has no integrations-configuration ConfigMap

**Files:**
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java:236-277` and `:386-418`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceTest.java`

**Interfaces:**
- Consumes: `deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds)` from Task 1, unchanged signature.
- Produces: `private Optional<Set<String>> remainingSnapshotIds(IntegrationResources resources, String removedSnapshotId)`.

### Why this is a separate defect

`remainingSnapshotIds` currently returns an empty set both when the ConfigMap lists no other sources and when there is no ConfigMap at all. The first is the ordinary case of removing a domain's last chain and must keep stripping. The second means the domain's remaining snapshots cannot be enumerated, which is the same blindness Task 1 guards against. `deleteChainSnapshot` only runs inside `getMainIntegrationResources(name).ifPresent(...)`, so the Integration always exists here and a missing ConfigMap is drift, not a normal state.

- [ ] **Step 1: Write the failing test**

Add to `MicroDomainServiceTest`, after `deleteChainSnapshotRemovesTheSnapshotSourceMountAndConfiguration`:

```java
    @DisplayName("Skips HTTPRoute cleanup when the domain has no integrations-configuration config map")
    @Test
    void deleteChainSnapshotSkipsHttpRouteCleanupWhenConfigurationConfigMapAbsent() {
        stubNamingStrategies();

        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait();
        mount.setResources(new ArrayList<>(List.of("configmap:src-s1/x")));
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);

        // Only the snapshot's own source config map comes back, never the integrations
        // configuration one, so IntegrationResources.integrationsConfiguration() is null.
        V1ConfigMap source = configMap("src-s1", Map.of(SNAPSHOT_ID_LABEL, "s1"));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(source));

        MicroDomainService service = newService(false);
        service.deleteChainSnapshot(DOMAIN, "s1");

        // The cleanup path is the only caller of the snapshot repository. Never touching it
        // proves the tiers were not read, let alone rewritten.
        verify(snapshotRepository, never()).findAllByIdIn(any());
    }
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `mvn -pl runtime-catalog test -Dtest=MicroDomainServiceTest#deleteChainSnapshotSkipsHttpRouteCleanupWhenConfigurationConfigMapAbsent`

Expected: FAIL with a Mockito `NeverWantedButInvoked` on `findAllByIdIn`, because the current code treats the absent ConfigMap as an empty remaining set and runs cleanup.

- [ ] **Step 3: Make `remainingSnapshotIds` return `Optional<Set<String>>`**

Replace the method and its javadoc at `MicroDomainService.java:386-418`:

```java
    /**
     * The snapshot IDs this micro-domain still hosts once {@code removedSnapshotId} is gone, read
     * from the integrations-configuration ConfigMap's source list, or {@link Optional#empty()} when
     * the domain has no such ConfigMap and they cannot be enumerated at all. That list is the only
     * in-cluster record holding raw snapshot IDs, and so the only one whose entries can go straight
     * to {@code SnapshotRepository}: the source ConfigMaps' {@code SNAPSHOT_ID_LABEL} holds a
     * {@code K8sNameValidator}-sanitized form, which strips a leading digit and therefore misses the
     * catalog row for most snapshot UUIDs.
     *
     * <p>{@link #deleteChainSnapshot} reads this before it rewrites that ConfigMap, so the removed
     * snapshot is still listed and is subtracted here. A redeployed chain's list already carries the
     * new snapshot ID by the time cleanup runs, because {@code IntegrationsConfiguration.merge}
     * dedupes sources by {@code chainId} and the later write wins. Cleanup depends on that dedupe
     * key: change it and a superseded snapshot stops seeing its replacement.
     *
     * <p>A present but blank ConfigMap yields an empty set, not an empty {@code Optional}:
     * {@code IntegrationConfigurationSerdes.getFromConfigMap} returns an empty
     * {@code IntegrationsConfiguration} rather than null, and a domain whose last chain is being
     * removed legitimately has no remaining snapshots. Those two cases must stay distinct — the
     * empty set still strips, the empty {@code Optional} does not.
     */
    private Optional<Set<String>> remainingSnapshotIds(IntegrationResources resources, String removedSnapshotId) {
        V1ConfigMap configurationConfigMap = resources.integrationsConfiguration();
        if (configurationConfigMap == null) {
            return Optional.empty();
        }
        Set<String> ids = Optional.ofNullable(integrationConfigurationSerdes.getFromConfigMap(configurationConfigMap))
                .map(IntegrationsConfiguration::getSources)
                .map(sources -> sources.stream()
                        .map(SourceDefinition::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
        ids.remove(removedSnapshotId);
        return Optional.of(ids);
    }
```

- [ ] **Step 4: Skip the cleanup call in `deleteChainSnapshot`**

At `MicroDomainService.java:239`, change the local's type:

```java
            Optional<Set<String>> remainingSnapshotIds = remainingSnapshotIds(resources, snapshotId);
```

At `MicroDomainService.java:275`, replace the unconditional call:

```java
            remainingSnapshotIds.ifPresentOrElse(
                    ids -> deleteChainSnapshotHttpRoutes(name, snapshotId, ids),
                    () -> log.warn("Micro-domain '{}' has no integrations-configuration ConfigMap, so the snapshots it still "
                            + "hosts are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping "
                            + "one a live chain still serves. Redeploy the domain to clear the leftovers.",
                            name, snapshotId));
```

- [ ] **Step 5: Run the full module test suite**

Run: `mvn -pl runtime-catalog test`

Expected: PASS, with no failures. `deleteChainSnapshotRemovesTheSnapshotSourceMountAndConfiguration` still passes because its `cfg` ConfigMap is present, so `remainingSnapshotIds` returns `Optional.of(Set.of("s2"))` and cleanup runs as before.

- [ ] **Step 6: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceTest.java
git commit -m "fix(runtime-catalog): skip HTTPRoute cleanup when the configuration config map is absent

remainingSnapshotIds returned an empty set both when the integrations
configuration listed no other sources and when there was no ConfigMap at
all. The first is a domain's last chain being removed and must still strip;
the second means the remaining snapshots cannot be enumerated, so stripping
can take a live chain offline.

The method now returns Optional.empty() for the second case and
deleteChainSnapshot skips cleanup rather than stripping blind.

@cf_ignore -- pre-existing third-party author email in package-lock.json,
untouched by this commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Verification checklist

Run after both tasks:

- [ ] `mvn -pl runtime-catalog test` is green.
- [ ] `MicroDomainService` no longer contains the string `did not resolve, so paths only they own are invisible` — the old, inverted warning is gone.
- [ ] `snapshotRoutes` has no `domainName` or `description` parameter.
- [ ] `git log --oneline -2` shows both commits on `feat-migrate-to-istio`.
