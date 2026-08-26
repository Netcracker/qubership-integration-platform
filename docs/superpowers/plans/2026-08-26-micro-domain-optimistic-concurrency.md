# Micro-domain optimistic concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a concurrent write to any object the micro-domain build merged against fail loudly instead of being silently overwritten.

**Architecture:** Three steps. First `KubeOperator` swaps server-side apply for read-modify-write PUT, which is where a `resourceVersion` precondition can actually take effect. Then Phase 1 starts recording what it observed — the live `metadata` of every object it read — and that record travels beside the generated YAML so the write can carry a real precondition and preserve operator-owned metadata. Finally a bounded retry rebuilds the whole context when a conflict fires.

**Tech Stack:** Java 21 (records), Spring, the official Kubernetes Java client (`io.kubernetes.client`), JUnit 5, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-08-25-micro-domain-optimistic-concurrency-design.md`

## Global Constraints

- Work in the worktree `C:\Users\ssn6\Workspace\qip\qip-monorepo\.claude\worktrees\feat-migrate-to-istio` on branch `feat-cr-deploy-optimistic-concurrency`. Do not create a worktree or branch, and do not switch branches.
- Build and test with `mvn -pl runtime-catalog test`. There is no `mvnw` wrapper in this repo. Focused runs use `-Dtest=<ClassName>`.
- Every commit message must contain the literal token `@cf_ignore`. A CyberFerret pre-commit hook otherwise rejects the commit over a pre-existing third-party email in `package-lock.json` that has nothing to do with this work.
- **Commit with an explicit pathspec** (`git commit -m "..." -- <path> <path>`). This worktree carries unrelated staged and untracked content from other work; a bare `git commit` after `git add -A` sweeps it in. Verify with `git show --stat` that only the intended files landed.
- Server-Side Apply ignores `metadata.resourceVersion` — it resolves through the field manager and never reaches the optimistic-concurrency check. A PUT honors it via `GuaranteedUpdate`, returning 409 Conflict on mismatch. This is why the whole plan moves to PUT rather than stamping a version onto the existing apply.
- A PUT with no `resourceVersion` is rejected for resources whose strategy sets `AllowUnconditionalUpdate() == false`, which is the default and includes custom resources. Every replace path must therefore carry a version.
- Do not change the `engine` or `micro-engine` modules, or any CR template.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `runtime-catalog/.../exception/exceptions/kubernetes/KubeApiConflictException.java` | Typed 409, so the retry can tell a conflict from a real failure | 1 (create) |
| `runtime-catalog/.../kubernetes/KubeOperator.java` | All cluster writes; swaps apply for GET-then-PUT | 1, 2 |
| `runtime-catalog/.../cr/MicroDomainResourceBuildContextFactory.java` | Phase 1 reads; gains observation capture | 2 |
| `runtime-catalog/.../cr/MicroDomainResourceBuildService.java` | Returns `BuiltResources` instead of a bare String | 2 |
| `runtime-catalog/.../cr/MicroDomainService.java` | `deploy` stamps versions and overlays metadata | 2 |
| `runtime-catalog/.../cr/rest/v1/controllers/CustomResourceController.java` | Passes `BuiltResources` through; gains the retry | 2, 3 |
| `runtime-catalog/src/test/.../kubernetes/KubeOperatorCreateOrUpdateTest.java` | Write-path tests; several rewrite from patch to replace | 1 |

Task 1 is a complete, independently reviewable change on its own: it removes SSA and preserves today's last-write-wins semantics via a GET before each PUT. Task 2 is what converts that into a genuine precondition. A reviewer could accept one and reject the other, which is why they are separate.

---

## Task 1: Replace server-side apply with GET-then-PUT

**Files:**
- Create: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/exception/exceptions/kubernetes/KubeApiConflictException.java`
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperator.java` — `createOrUpdateConfigMap` (`:236-259`), `createOrUpdateService` (`:261-284`), `createOrUpdateCustomResource` (`:286-330`)
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperatorCreateOrUpdateTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `KubeApiConflictException extends KubeApiException`, raised from every write path on HTTP 409. Task 2 relies on `createOrUpdateResource(Object)` keeping its signature, and on a caller-set `metadata.resourceVersion` being sent verbatim as the precondition rather than overwritten.

### What changes, in one sentence

Each of the three write paths currently LISTs the collection, then either creates or applies with `PatchUtils` + `fieldManager("kubectl-patch")` + `force(true)`. Each becomes: read the single object; if absent, create; if present, copy its `resourceVersion` onto the outgoing object **only when the caller did not already set one**, then replace.

That last clause is the seam for Task 2. In this task nothing sets a version, so every write behaves as today — last-write-wins — but through PUT instead of apply.

- [ ] **Step 1: Create the typed conflict exception**

```java
package org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes;

/**
 * A write lost an optimistic-concurrency race: the object changed between the read that produced
 * the {@code resourceVersion} we sent and this write, or a create found the object already there.
 * Distinct from {@link KubeApiException} so a caller can rebuild and retry rather than treating it
 * as a terminal failure.
 */
public class KubeApiConflictException extends KubeApiException {
    public KubeApiConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Check `KubeApiException`'s constructors first and match one that takes a message and a cause. If it has no such constructor, add the call that compiles rather than inventing a new one on the parent.

- [ ] **Step 2: Write the failing tests**

Add to `KubeOperatorCreateOrUpdateTest`. These are new tests; Step 5 deals with the existing ones they contradict.

```java
    @Test
    void replacesAConfigMapThatAlreadyExistsInsteadOfPatchingIt() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(replaceRequest).execute();
        assertEquals("101", configMap.getMetadata().getResourceVersion(),
                "the live version becomes the precondition when the caller supplied none");
    }

    // Task 2 sets the version from the Phase 1 read. If the write path overwrote it with the
    // version it just fetched, the precondition would always match and never detect a race.
    @Test
    void keepsACallerSuppliedResourceVersionInsteadOfOverwritingIt() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        configMap.getMetadata().setResourceVersion("77");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(configMap);

        assertEquals("77", configMap.getMetadata().getResourceVersion());
    }

    @Test
    void raisesAConflictExceptionWhenAReplaceLosesTheRace() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrUpdateResource(configMap));
    }

    @Test
    void raisesAConflictExceptionWhenACreateFindsTheObjectAlreadyThere() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "AlreadyExists"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrUpdateResource(configMap));
    }

    @Test
    void neverUsesServerSideApplyForAnyKind() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            kubeOperator.createOrUpdateResource(configMap);
            patchUtils.verifyNoInteractions();
        }
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertEquals;` and
`import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;`
if they are not already present.

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `mvn -pl runtime-catalog test -Dtest=KubeOperatorCreateOrUpdateTest`

Expected: FAIL. The replace tests fail because the current code LISTs and patches, so `coreApi.readNamespacedConfigMap` is never called and `replaceRequest.execute()` never happens. `neverUsesServerSideApplyForAnyKind` passes already for a create (nothing to patch) — that is fine; it is there to lock the property once Step 4 lands, and Step 6 confirms it against an update.

- [ ] **Step 4: Rewrite the three write paths**

Replace `createOrUpdateConfigMap` and `createOrUpdateService` entirely:

```java
    private void createOrUpdateConfigMap(V1ConfigMap cm) throws KubeApiException {
        String name = getName(cm).orElseThrow(() -> new KubeApiException("Failed to get config map name"));
        V1ConfigMap live = readOrNull(() -> coreApi.readNamespacedConfigMap(name, namespace).execute());
        try {
            if (live == null) {
                coreApi.createNamespacedConfigMap(namespace, cm).execute();
            } else {
                applyPrecondition(cm.getMetadata(), live.getMetadata());
                coreApi.replaceNamespacedConfigMap(name, namespace, cm).execute();
            }
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update ConfigMap", e);
        }
    }

    private void createOrUpdateService(V1Service service) throws KubeApiException {
        String name = getName(service).orElseThrow(() -> new KubeApiException("Failed to get service name"));
        V1Service live = readOrNull(() -> coreApi.readNamespacedService(name, namespace).execute());
        try {
            if (live == null) {
                coreApi.createNamespacedService(namespace, service).execute();
            } else {
                applyPrecondition(service.getMetadata(), live.getMetadata());
                coreApi.replaceNamespacedService(name, namespace, service).execute();
            }
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update Service", e);
        }
    }
```

Replace the body of `createOrUpdateCustomResource`. The `listType` parameter becomes unused once the LIST goes away — delete it from the signature and from every call site in `createOrUpdateResource` (there are six: `CamelKIntegration`, `V1ServiceMonitor`, HTTPRoute, `ServiceEntry`, `DestinationRule`, and the generic branch), rather than leaving a dead parameter:

```java
    private <T extends KubernetesObject> void createOrUpdateCustomResource(
            String group,
            String version,
            String plural,
            T obj,
            boolean updateIfExists
    ) throws KubeApiException {
        String name = getName(obj).orElseThrow(() -> new KubeApiException("Failed to get custom object name"));
        Object rawLive = readOrNull(() ->
                customObjectsApi.getNamespacedCustomObject(group, version, namespace, plural, name).execute());
        try {
            if (rawLive == null) {
                customObjectsApi.createNamespacedCustomObject(group, version, namespace, plural, obj).execute();
                return;
            }
            if (!updateIfExists) {
                log.info("Custom object {}/{} already exists, skipping update as not needed for this kind",
                        obj.getKind(), name);
                return;
            }
            applyPrecondition(obj.getMetadata(), fromRawObject(rawLive, KubeCustomObject.class).getMetadata());
            customObjectsApi.replaceNamespacedCustomObject(group, version, namespace, plural, name, obj).execute();
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update custom object", e);
        }
    }
```

Add the three helpers:

```java
    /**
     * Copies {@code live}'s {@code resourceVersion} onto {@code outgoing} unless the caller already
     * set one. A caller-supplied version is a deliberate precondition taken from an earlier read;
     * overwriting it with the version we just fetched would make the check always pass and defeat
     * the point.
     */
    private static void applyPrecondition(V1ObjectMeta outgoing, V1ObjectMeta live) {
        if (outgoing == null || live == null) {
            return;
        }
        if (outgoing.getResourceVersion() == null || outgoing.getResourceVersion().isBlank()) {
            outgoing.setResourceVersion(live.getResourceVersion());
        }
    }

    /** Runs a single-object read, returning null for 404 and propagating every other failure. */
    private <T> T readOrNull(ApiReader<T> reader) throws KubeApiException {
        try {
            return reader.read();
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.NOT_FOUND.value()) {
                return null;
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        }
    }

    @FunctionalInterface
    private interface ApiReader<T> {
        T read() throws ApiException;
    }

    /** Maps HTTP 409 — a lost race on replace, or AlreadyExists on create — onto the typed conflict. */
    private static KubeApiException toKubeException(String message, ApiException e) {
        if (e.getCode() == HttpStatus.CONFLICT.value()) {
            return new KubeApiConflictException(message + ": " + e.getResponseBody(), e);
        }
        return new KubeApiException(message, e);
    }
```

Then delete the now-unused imports: `io.kubernetes.client.util.PatchUtils`, `io.kubernetes.client.custom.V1Patch`, and any `*List` type import kept solely for the removed `listType` arguments. Let the compiler tell you which — `mvn -pl runtime-catalog compile` reports unused imports only if checkstyle is configured, so remove them by inspection.

- [ ] **Step 5: Pin the one read-modify-write caller this change puts at risk**

`MicroDomainService.deleteChainSnapshot:254` reads the Integration through `getMainIntegrationResources` — which deserializes it into `CamelKIntegration` — strips a mount, and writes it back. Under apply that was safe: fields the POJO does not model were simply absent from the patch and left alone. Under PUT they would be deleted.

`CamelKIntegration.IntegrationSpec` models exactly four fields (`replicas`, `serviceAccountName`, `traits`, `template`) and the class has no `status` at all. Nothing is lost today, for two reasons that are worth checking rather than assuming: the Integration CRD has a `/status` subresource, so a PUT to the main resource cannot touch status; and QIP's own Handlebars template emits exactly the four spec fields the POJO models, so there is nothing else in spec to lose.

That makes this safe now and fragile later — if Camel-K or QIP ever adds a spec field the POJO does not model, this write silently drops it. Add a test that fails loudly if the round trip starts losing content:

```java
    @DisplayName("Writes the Integration back with its operator-owned metadata intact")
    @Test
    void deleteChainSnapshotPreservesOperatorMetadataOnTheIntegration() {
        // build the same fixture as deleteChainSnapshotRemovesTheSnapshotSourceMountAndConfiguration,
        // but give the Integration metadata an operator annotation before the call:
        integration.setMetadata(new V1ObjectMeta()
                .name(INTEGRATION_RESOURCE_NAME)
                .annotations(new LinkedHashMap<>(Map.of("camel.apache.org/operator.id", "camel-k"))));

        service.deleteChainSnapshot(DOMAIN, "s1");

        verify(kubeOperator).createOrUpdateResource(integration);
        assertEquals("camel-k",
                integration.getMetadata().getAnnotations().get("camel.apache.org/operator.id"),
                "V1ObjectMeta is fully modeled, so a POJO round trip must not lose annotations");
    }
```

Put it in `MicroDomainServiceTest` next to the existing `deleteChainSnapshot` tests. It documents the boundary: metadata survives the POJO because `V1ObjectMeta` is complete; unmodeled *spec* fields would not, and there are none today.

- [ ] **Step 6: Update the existing tests that assert patch behavior**

Five existing tests assert the apply path and will now fail. Rewrite each to the replace path rather than deleting it — the coverage is still wanted:

- `patchesAConfigMapThatAlreadyExists` → rename to `replacesAConfigMapThatAlreadyExists`, stub `readNamespacedConfigMap` returning a live object, assert `replaceRequest.execute()`.
- `patchesAServiceThatAlreadyExists` → same shape with `readNamespacedService` / `replaceNamespacedService`.
- `patchesAnHttpRouteThatAlreadyExists` → stub `getNamespacedCustomObject` returning a raw map with `metadata.resourceVersion`, assert `replaceNamespacedCustomObject` was called.
- `wrapsApiFailuresRaisedWhileApplyingAConfigMap` → keep, but make the failure come from `replaceRequest.execute()` and assert it is a `KubeApiException` that is **not** a `KubeApiConflictException` (use a non-409 code such as 500, so the two branches of `toKubeException` are both covered).
- `leavesAnExistingGenericCustomObjectAloneWhenItsDefinitionForbidsUpdates` → the object must now be found via `getNamespacedCustomObject` rather than the LIST; assert `replaceNamespacedCustomObject` is never called.

Any test that stubs `listNamespacedConfigMap`, `listNamespacedService`, or `listNamespacedCustomObject` purely to drive the create-versus-update decision no longer needs that stub. Remove those stubs; leaving them is harmless with plain `mock()` but misleading.

- [ ] **Step 7: Run the full module suite**

Run: `mvn -pl runtime-catalog test`

Expected: PASS with no failures. If `neverUsesServerSideApplyForAnyKind` is the only green-before-and-after test, extend it once here to also cover an update (stub the read to return a live object) and confirm it still passes — that is the assertion that keeps SSA from creeping back.

- [ ] **Step 8: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/exception/exceptions/kubernetes/KubeApiConflictException.java runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperator.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperatorCreateOrUpdateTest.java
git commit -m "refactor(runtime-catalog): replace server-side apply with read-then-PUT

Server-side apply ignores metadata.resourceVersion -- it resolves through
the field manager and never reaches the optimistic-concurrency check -- so
no precondition can be expressed through it. A PUT honors the version via
GuaranteedUpdate and returns 409 Conflict on mismatch.

Every write path now reads the single object, creates it when absent, and
otherwise replaces it. PatchUtils, the kubectl-patch field manager, and
force(true) are gone, and with them the need to reason about field
ownership. A caller-supplied resourceVersion is preserved rather than
overwritten, which is the seam the next commit uses to turn this into a
real precondition.

Behavior is unchanged for now: nothing sets a version yet, so each write
still resolves to last-write-wins.

@cf_ignore -- pre-existing third-party author email in package-lock.json,
untouched by this commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>" -- runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/exception/exceptions/kubernetes/KubeApiConflictException.java runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperator.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperatorCreateOrUpdateTest.java
```

---

## Task 2: Capture Phase 1 observations and use them as preconditions

**Files:**
- Modify: `runtime-catalog/.../cr/MicroDomainResourceBuildContextFactory.java` — `createResourceBuildContext` (`:61-86`), `addAppendConfigurationToContext` (`:103-113`), `putHostResourceSpecsToBuildCache` (`:169-178`)
- Modify: `runtime-catalog/.../cr/MicroDomainResourceBuildService.java` — `buildResources` (`:28-32`)
- Modify: `runtime-catalog/.../cr/MicroDomainService.java` — `deploy` (`:185-194`)
- Modify: `runtime-catalog/.../cr/rest/v1/controllers/CustomResourceController.java` — `buildResource` (`:75-79`), `doDeployResource` (`:183-192`)
- Test: `runtime-catalog/src/test/.../cr/MicroDomainResourceBuildContextFactoryTest.java`, `runtime-catalog/src/test/.../cr/MicroDomainServiceTest.java`

**Interfaces:**
- Consumes: `KubeApiConflictException` and the caller-supplied-version behavior from Task 1.
- Produces: `MicroDomainService.BuiltResources(String yaml, Map<ResourceKey, Optional<V1ObjectMeta>> observations)` and `MicroDomainService.ResourceKey(String kind, String name)`. Task 3 calls `buildResources` and expects it to return `BuiltResources`.

### The three states, and why two is not enough

`addAppendConfigurationToContext` runs only under `APPEND`; `putHostResourceSpecsToBuildCache` runs in both modes. So under `REWRITE` there is no Phase 1 read of the Integration, Service, ConfigMaps, or HTTPRoutes.

"No version" is therefore ambiguous between *Phase 1 looked and the object was not there* (create it) and *Phase 1 never looked* (it probably exists — read it at write time). Collapsing these makes every `REWRITE` deploy after the first attempt a create against a live object and fail. The map must express three states:

| Map state | Meaning | Write behavior |
|---|---|---|
| `Optional.of(meta)` | read at `meta.getResourceVersion()` | stamp that version, replace |
| `Optional.empty()` | looked, absent | create |
| key absent | never looked | Task 1's read-then-PUT decides |

- [ ] **Step 1: Write the failing tests**

In `MicroDomainResourceBuildContextFactoryTest`:

These call the factory directly. Reuse the class's existing `@BeforeEach` fixture and its `snapshotRepository.findAllByIdIn(any())` stub; the three helpers below are the only new arrangement they need.

```java
    private CamelKIntegration integrationWithVersion(String name, String resourceVersion) {
        CamelKIntegration integration = new CamelKIntegration();
        integration.setMetadata(new V1ObjectMeta().name(name).resourceVersion(resourceVersion));
        return integration;
    }

    private KubeCustomObject hostResource(String kind, String name, String resourceVersion) {
        KubeCustomObject object = new KubeCustomObject();
        object.setKind(kind);
        object.setMetadata(new V1ObjectMeta().name(name).resourceVersion(resourceVersion));
        object.setSpec(new LinkedHashMap<>());
        return object;
    }

    /** IntegrationResources with only the fields a given test cares about; the rest read as absent. */
    private MicroDomainService.IntegrationResources resources(
            CamelKIntegration integration, KubeCustomObject publicHttpRoute) {
        return new MicroDomainService.IntegrationResources(
                integration, null, null, null, List.of(), null, List.of(),
                publicHttpRoute, null, null);
    }

    @Test
    void recordsTheLiveMetadataOfEveryObjectItReadUnderAppendMode() {
        CamelKIntegration integration = integrationWithVersion("int-res", "42");
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(Optional.of(resources(integration, null)));

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), true);

        Optional<V1ObjectMeta> observed =
                built.observations().get(new MicroDomainService.ResourceKey("Integration", "int-res"));
        assertNotNull(observed, "an object Phase 1 read must be recorded, not omitted");
        assertTrue(observed.isPresent());
        assertEquals("42", observed.get().getResourceVersion());
    }

    @Test
    void recordsAnAbsentObjectAsObservedEmptyRatherThanOmittingIt() {
        // publicHttpRoute is null: getMainIntegrationResources looked and found nothing.
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(Optional.of(resources(integrationWithVersion("int-res", "42"), null)));

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), true);

        Optional<V1ObjectMeta> observed = built.observations()
                .get(new MicroDomainService.ResourceKey("HTTPRoute", PUBLIC_ROUTE_NAME));
        assertNotNull(observed, "looked-and-absent must be distinguishable from never-looked");
        assertTrue(observed.isEmpty());
    }

    // The test that fails if someone collapses the map to two states.
    @Test
    void omitsKeysItNeverLookedAtUnderRewriteMode() {
        when(microDomainService.getExistingServiceEntries())
                .thenReturn(List.of(hostResource("ServiceEntry", "example-com", "7")));
        when(microDomainService.getExistingDestinationRules()).thenReturn(List.of());

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), false);

        assertFalse(
                built.observations().containsKey(new MicroDomainService.ResourceKey("Integration", "int-res")),
                "REWRITE performs no Phase 1 read of the Integration, so its key must be absent, "
                        + "not present-and-empty -- present-and-empty would make the write attempt a create");
        assertTrue(
                built.observations().containsKey(new MicroDomainService.ResourceKey("ServiceEntry", "example-com")),
                "host resources are read in both modes and must still be recorded");
        verify(microDomainService, never()).getMainIntegrationResources(anyString());
    }
```

`buildRequest(DOMAIN)` builds a `ResourceBuildRequest` whose options carry the domain name — the class already constructs one for its existing tests; extract it to a helper if it is currently inline. `PUBLIC_ROUTE_NAME` is the name `httpRoutePublicNamingStrategy` yields for `DOMAIN` in this fixture.

In `MicroDomainServiceTest`:

```java
    @DisplayName("Stamps the observed resourceVersion onto a document before writing it")
    @Test
    void deployStampsObservedResourceVersion() {
        V1ObjectMeta observed = new V1ObjectMeta().name("route").resourceVersion("42");
        BuiltResources built = new BuiltResources(
                httpRouteYaml("route"),
                Map.of(new ResourceKey("HTTPRoute", "route"), Optional.of(observed)));

        MicroDomainService service = newService(false);
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject written = (KubeCustomObject) captor.getValue();
        assertEquals("42", written.getMetadata().getResourceVersion());
    }

    @DisplayName("Keeps operator-owned annotations that the generated document does not declare")
    @Test
    void deployOverlaysGeneratedMetadataOntoObservedMetadata() {
        V1ObjectMeta observed = new V1ObjectMeta()
                .name("int-res")
                .resourceVersion("42")
                .annotations(new LinkedHashMap<>(Map.of("camel.apache.org/operator.id", "camel-k")))
                .labels(new LinkedHashMap<>(Map.of("qip.domain", "payments")));
        BuiltResources built = new BuiltResources(
                integrationYaml("int-res"),   // declares only the qip.domain label
                Map.of(new ResourceKey("Integration", "int-res"), Optional.of(observed)));

        MicroDomainService service = newService(false);
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        CamelKIntegration written = (CamelKIntegration) captor.getValue();
        assertEquals("camel-k", written.getMetadata().getAnnotations().get("camel.apache.org/operator.id"),
                "an annotation only the operator set must survive the write");
        assertEquals("payments", written.getMetadata().getLabels().get("qip.domain"));
    }

    @DisplayName("Leaves resourceVersion unset for a document Phase 1 observed as absent")
    @Test
    void deployLeavesVersionUnsetForAnObservedAbsentDocument() {
        BuiltResources built = new BuiltResources(
                httpRouteYaml("route"),
                Map.of(new ResourceKey("HTTPRoute", "route"), Optional.empty()));

        MicroDomainService service = newService(false);
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject written = (KubeCustomObject) captor.getValue();
        assertNull(written.getMetadata().getResourceVersion());
    }
```

`httpRouteYaml` and `integrationYaml` are small helpers returning a one-document YAML string for the given name; write them alongside the existing helpers in that test class.

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn -pl runtime-catalog test -Dtest=MicroDomainResourceBuildContextFactoryTest+MicroDomainServiceTest`

Expected: FAIL to compile — `BuiltResources`, `ResourceKey`, and `deploy(BuiltResources)` do not exist yet. A compile failure is the correct RED here; do not fabricate stubs to turn it into an assertion failure.

- [ ] **Step 3: Add the record types and the observation map**

In `MicroDomainService`, beside the existing `IntegrationResources` record:

```java
    /** Identifies a document in the built YAML, and an entry in the observation map. */
    public record ResourceKey(String kind, String name) { }

    /**
     * The built YAML plus what Phase 1 observed for each object it read. The observation is the
     * live {@code V1ObjectMeta} rather than a bare version string: it already carries
     * {@code resourceVersion}, and it is also the metadata the write overlays generated labels onto.
     *
     * <p>Three states, and they are not interchangeable. {@code Optional.of(meta)} means Phase 1
     * read the object; {@code Optional.empty()} means it looked and found nothing; a key that is
     * absent entirely means Phase 1 never looked, which is the ordinary case under {@code REWRITE}.
     */
    public record BuiltResources(String yaml, Map<ResourceKey, Optional<V1ObjectMeta>> observations) { }
```

- [ ] **Step 4: Capture observations in the factory**

`createResourceBuildContext` returns the context **and** the observations, as one record. Do not store the map on the factory: `MicroDomainResourceBuildContextFactory` is a singleton Spring bean, so a field would be shared across concurrent builds — which is the exact class of bug this plan exists to fix.

Add to `MicroDomainResourceBuildContextFactory`:

```java
    /** A built context together with what Phase 1 observed while building it. */
    public record BuildContextWithObservations(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, Optional<V1ObjectMeta>> observations
    ) { }
```

Change `createResourceBuildContext` to build a local `Map<ResourceKey, Optional<V1ObjectMeta>> observations = new LinkedHashMap<>();`, thread it into `addAppendConfigurationToContext` and `putHostResourceSpecsToBuildCache` as a parameter, and return `new BuildContextWithObservations(context, observations)`.

Record each object as it is read:

```java
    private void recordObservation(
            Map<ResourceKey, Optional<V1ObjectMeta>> observations,
            String kind,
            String name,
            KubernetesObject live
    ) {
        observations.put(new ResourceKey(kind, name),
                live == null ? Optional.empty() : Optional.ofNullable(live.getMetadata()));
    }
```

Call it from `addAppendConfigurationToContext` for the Integration, Service, ServiceMonitor, integrations-configuration ConfigMap, each source ConfigMap, and each of the three HTTPRoutes — passing `null` for the ones `IntegrationResources` holds as null, so they record as observed-absent. Call it from `putHostResourceSpecsToBuildCache` for every `ServiceEntry` and `DestinationRule`, which are read in both modes.

Do **not** add entries for kinds the current mode did not read. Under `REWRITE`, `addAppendConfigurationToContext` does not run, so none of its keys appear — that is the third state, and `omitsKeysItNeverLookedAtUnderRewriteMode` checks it.

- [ ] **Step 5: Thread the map through the build service and the controller**

`MicroDomainResourceBuildService.buildResources` returns `BuiltResources`:

```java
    public BuiltResources buildResources(ResourceBuildRequest request, boolean appendToExisting) {
        BuildContextWithObservations built =
                buildContextFactory.createResourceBuildContext(request, appendToExisting);
        return new BuiltResources(
                resourceBuildService.buildResources(built.context()),
                built.observations());
    }
```

`CustomResourceController.buildResource` reads `.yaml()` off the result so the build-only endpoint still returns a plain string:

```java
        return verifyMicroDomainEnabled(() ->
                microDomainResourceBuildService.buildResources(request, false).yaml());
```

`doDeployResource` passes the whole record:

```java
        BuiltResources built = microDomainResourceBuildService.buildResources(
                buildRequest,
                DeployMode.APPEND.equals(request.getMode()));
        microDomainService.deploy(built);
```

- [ ] **Step 6: Stamp and overlay in `deploy`**

```java
    public void deploy(BuiltResources built) throws MicroDomainDeployError {
        try {
            List<Object> resources = Yaml.loadAll(built.yaml());
            for (Object resource : resources) {
                applyObservation(resource, built.observations());
                kubeOperator.createOrUpdateResource(resource);
            }
        } catch (KubeApiConflictException conflict) {
            throw conflict;
        } catch (Exception exception) {
            throw new MicroDomainDeployError("Failed to deploy resources", exception);
        }
    }
```

The `KubeApiConflictException` rethrow is load-bearing: `MicroDomainDeployError` would hide the conflict from Task 3's retry, which must not retry a genuine failure.

```java
    /**
     * Stamps the observed {@code resourceVersion} onto {@code resource} and folds the generated
     * labels and annotations onto the metadata the object already carried, so a write does not
     * strip metadata another actor owns -- notably the Camel-K operator's
     * {@code camel.apache.org/*} annotations on the Integration.
     *
     * <p>A key absent from {@code observations} means Phase 1 never read this kind in this mode;
     * the document is left exactly as generated and {@code KubeOperator} resolves it with a
     * write-time read.
     */
    private void applyObservation(Object resource, Map<ResourceKey, Optional<V1ObjectMeta>> observations) {
        if (!(resource instanceof KubernetesObject object) || object.getMetadata() == null) {
            return;
        }
        ResourceKey key = new ResourceKey(object.getKind(), object.getMetadata().getName());
        Optional<V1ObjectMeta> observation = observations.get(key);
        if (observation == null || observation.isEmpty()) {
            return;
        }
        V1ObjectMeta live = observation.get();
        V1ObjectMeta generated = object.getMetadata();
        generated.setResourceVersion(live.getResourceVersion());
        generated.setLabels(overlay(live.getLabels(), generated.getLabels()));
        generated.setAnnotations(overlay(live.getAnnotations(), generated.getAnnotations()));
    }

    /** {@code base} with {@code overrides} folded on top; generated values win on key collision. */
    private static Map<String, String> overlay(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged.isEmpty() ? null : merged;
    }
```

`KubeCustomObject` implements `KubernetesObject` and exposes `getKind()`, so the instanceof branch covers HTTPRoutes, ServiceEntries, and DestinationRules. `CamelKIntegration` also implements it. `V1ConfigMap` and `V1Service` implement `KubernetesObject` but return `null` from `getKind()` when parsed from YAML that omits the field — the generated documents do declare `kind`, so this works; if a document without `kind` appears, the lookup simply misses and the write falls through to Task 1's read-then-PUT.

- [ ] **Step 7: Run the full module suite**

Run: `mvn -pl runtime-catalog test`

Expected: PASS. Existing `deploy` tests that called `deploy(String)` need updating to the new signature — pass `new BuiltResources(yaml, Map.of())`, which exercises the never-observed branch and asserts the old behavior is preserved when nothing was recorded.

- [ ] **Step 8: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/ runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/
git commit -m "feat(runtime-catalog): carry Phase 1 resourceVersions into the deploy write

The build reads existing state in Phase 1, merges against it in Phase 2,
and wrote in Phase 3 with no precondition, so a writer that changed an
object in between was silently overwritten. The window spans database
reads, nine or more cluster round trips, and a per-document write loop.

Phase 1 now records the live metadata of every object it reads, and that
record travels beside the YAML to the write, which stamps the observed
version as a precondition. Conflicts surface as KubeApiConflictException
rather than being wrapped in MicroDomainDeployError, so a retry can tell
them from genuine failures.

The record has three states, not two. A key absent from the map means
Phase 1 never looked -- the ordinary case under REWRITE, which reads only
the host resources -- and must not be confused with looked-and-absent, or
every REWRITE deploy after the first would attempt a create against a live
object.

Generated labels and annotations are folded onto the observed metadata
rather than replacing it, so a write no longer strips the Camel-K
operator's camel.apache.org/* annotations and provokes a reconcile.

@cf_ignore -- pre-existing third-party author email in package-lock.json,
untouched by this commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>" -- runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/ runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/
```

---

## Task 3: Rebuild-and-retry on conflict

**Files:**
- Modify: `runtime-catalog/.../cr/rest/v1/controllers/CustomResourceController.java` — `doDeployResource` (`:183-192`)
- Test: `runtime-catalog/src/test/.../cr/rest/v1/controllers/CustomResourceControllerTest.java`

**Interfaces:**
- Consumes: `KubeApiConflictException` (Task 1) and `BuiltResources` (Task 2).
- Produces: nothing later tasks depend on.

### Why it wraps `doDeployResource` and rebuilds

The merge that produces a document runs in Phase 2 against Phase 1 data. A built document therefore carries a port list that is a snapshot of the world before the conflict. Re-sending it would resend both the stale `resourceVersion` and the stale union: with the precondition it 409s forever, and without one it would drop precisely the port whose arrival caused the conflict. Only re-entering Phase 1 re-merges against what is actually in the cluster — which is why the loop wraps the build **and** the deploy, not just the deploy.

- [ ] **Step 1: Write the failing tests**

```java
    @DisplayName("Rebuilds and retries when a deploy loses an optimistic-concurrency race")
    @Test
    void deployRebuildsAndRetriesOnConflict() {
        BuiltResources first = new BuiltResources("first", Map.of());
        BuiltResources second = new BuiltResources("second", Map.of());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(first)
                .thenReturn(second);
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deploy(any());

        controller.deployResource(deployRequest("payments"));

        // Rebuilt, not re-sent: the second attempt must carry a freshly built document.
        verify(microDomainResourceBuildService, times(2)).buildResources(any(), anyBoolean());
        ArgumentCaptor<BuiltResources> captor = ArgumentCaptor.forClass(BuiltResources.class);
        verify(microDomainService, times(2)).deploy(captor.capture());
        assertEquals(List.of("first", "second"),
                captor.getAllValues().stream().map(BuiltResources::yaml).toList());
    }

    @DisplayName("Gives up after the retry budget and surfaces the last conflict")
    @Test
    void deployStopsRetryingAfterTheBudgetIsExhausted() {
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new KubeApiConflictException("conflict", null)).when(microDomainService).deploy(any());

        assertThrows(KubeApiConflictException.class, () -> controller.deployResource(deployRequest("payments")));

        verify(microDomainService, times(3)).deploy(any());
    }

    @DisplayName("Does not retry a failure that is not a conflict")
    @Test
    void deployDoesNotRetryANonConflictFailure() {
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new MicroDomainDeployError("boom", null)).when(microDomainService).deploy(any());

        assertThrows(MicroDomainDeployError.class, () -> controller.deployResource(deployRequest("payments")));

        verify(microDomainService, times(1)).deploy(any());
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn -pl runtime-catalog test -Dtest=CustomResourceControllerTest`

Expected: FAIL. `deployRebuildsAndRetriesOnConflict` fails with the conflict propagating on the first attempt, because no retry exists yet.

- [ ] **Step 3: Add the retry**

```java
    private static final int MAX_DEPLOY_ATTEMPTS = 3;

    private void doDeployResource(ResourceDeployRequest request) {
        ResourceBuildRequest buildRequest = ResourceBuildRequest.builder()
                .options(resourceBuildOptionsProvider.getOptions(request))
                .snapshotIds(request.getSnapshotIds())
                .build();
        for (int attempt = 1; ; attempt++) {
            BuiltResources built = microDomainResourceBuildService.buildResources(
                    buildRequest,
                    DeployMode.APPEND.equals(request.getMode()));
            try {
                microDomainService.deploy(built);
                return;
            } catch (KubeApiConflictException conflict) {
                if (attempt == MAX_DEPLOY_ATTEMPTS) {
                    throw conflict;
                }
                log.warn("Deploy of micro-domain '{}' lost a concurrency race on attempt {}/{}; "
                                + "rebuilding against current cluster state and retrying",
                        request.getName(), attempt, MAX_DEPLOY_ATTEMPTS);
            }
        }
    }
```

The build happens **inside** the loop. Hoisting it out would re-send the stale document and defeat the whole mechanism.

- [ ] **Step 4: Run the full module suite**

Run: `mvn -pl runtime-catalog test`

Expected: PASS with no failures.

- [ ] **Step 5: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/rest/v1/controllers/CustomResourceController.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/rest/v1/controllers/CustomResourceControllerTest.java
git commit -m "feat(runtime-catalog): rebuild and retry a deploy that loses a race

A conflict means some other writer changed an object between the build's
Phase 1 reads and its Phase 3 writes. The built document is then stale in
two ways at once: it carries the old resourceVersion, and its merged port
lists were computed against the old state.

So the retry rebuilds rather than re-sending. Re-applying the same
document would 409 forever against the precondition, and without one would
drop exactly the entry whose arrival caused the conflict. The loop
therefore wraps the build as well as the deploy.

Bounded at three attempts, and only conflicts are retried -- any other
failure propagates on the first attempt.

@cf_ignore -- pre-existing third-party author email in package-lock.json,
untouched by this commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>" -- runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/rest/v1/controllers/CustomResourceController.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/rest/v1/controllers/CustomResourceControllerTest.java
```

---

## Verification checklist

Run after all three tasks:

- [ ] `mvn -pl runtime-catalog test` is green.
- [ ] `grep -rn "PatchUtils\|kubectl-patch\|force(true)" runtime-catalog/src/main` returns nothing.
- [ ] `grep -rn "deploy(String" runtime-catalog/src/main` returns nothing — the old signature is gone.
- [ ] `git log --oneline -3` shows the three commits on `feat-cr-deploy-optimistic-concurrency`.
