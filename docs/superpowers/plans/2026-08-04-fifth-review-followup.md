# Fifth-Review Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the last surviving instance of the Istio HTTPRoute CR's cross-replica lost-update race — the unconditional delete that runs when a tier's merged rule set becomes empty.

**Architecture:** `KubeOperator.deleteCustomObject` gains the same resourceVersion-precondition treatment `createOrReplaceCustomObject` already got: it reads the resourceVersion the caller already set on the request (the same field `IstioRoutesRegistrationService.attemptMergeTierRoutes` populates before deciding whether to delete or write), passes it to the Kubernetes API as a delete precondition, and throws the existing `KubeApiConflictException` on a 409. `IstioRoutesRegistrationService`'s retry loop already wraps the entire per-attempt call (both the write branch and the delete branch), so no change is needed there — the delete path is covered automatically once it throws the same exception type.

**Tech Stack:** Java 21, JUnit 5, Mockito (`mock(Class)` factory style).

## Global Constraints

- No change to `IstioRoutesRegistrationService.mergeTierRoutes`'s or `attemptMergeTierRoutes`'s retry-loop structure — the fix is entirely inside `KubeOperator.deleteCustomObject`, and the existing loop picks it up for free.
- A `resourceVersion` of `null` on the request (no CR existed, or the caller genuinely has no observed version) must skip the precondition entirely — the delete call behaves exactly as it does today in that case, so the four existing `KubeOperatorTest` delete tests must keep passing unmodified.

---

### Task 1: Precondition `deleteCustomObject` on the observed resourceVersion

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`

**Interfaces:**
- Changes `KubeOperator.deleteCustomObject(KubeCustomObjectRequest request)`'s behavior (not its signature): when `request.getBody().getMetadata().getResourceVersion()` is non-null, the delete now carries that value as a Kubernetes delete precondition, and a 409 response throws `KubeApiConflictException` (added in the prior fix round) instead of the generic `KubeApiException`.

Verified directly against `client-java-api-25.0.0.jar` (not assumed): `CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest` has a `body(V1DeleteOptions)` method; `V1DeleteOptions` has a `preconditions(V1Preconditions)` method; `V1Preconditions` has a `resourceVersion(String)` method. The precondition mechanism this task uses exists in the pinned client library version.

`IstioRoutesRegistrationService.attemptMergeTierRoutes` (unchanged by this task) already sets `tierRequest.getBody().getMetadata().setResourceVersion(...)` from its own read, unconditionally, before checking whether `mergedRules` is empty — so by the time it calls `kubeOperator.deleteCustomObject(tierRequest)`, the same resourceVersion `createOrReplaceCustomObject` would have used is already sitting on the request. `deleteCustomObject` just needs to read and use it, the same way `createOrReplaceCustomObject` already does.

- [ ] **Step 1: Write the failing `KubeOperatorTest` tests**

Add these two tests, plus two new imports (`io.kubernetes.client.openapi.models.V1DeleteOptions` and `org.mockito.ArgumentCaptor`; `KubeApiConflictException` is already imported from the prior fix round):

```java
    @Test
    void deleteCustomObjectPassesResourceVersionAsPreconditionWhenSet() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(req));

        ArgumentCaptor<V1DeleteOptions> optionsCaptor = ArgumentCaptor.forClass(V1DeleteOptions.class);
        verify(deleteRequest).body(optionsCaptor.capture());
        assertEquals("12345", optionsCaptor.getValue().getPreconditions().getResourceVersion());
        verify(deleteRequest).execute();
    }

    @Test
    void deleteCustomObjectThrowsConflictExceptionOn409() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.deleteCustomObject(req));
    }
```

Do not modify the four existing `deleteCustomObject*` tests (`deleteCustomObjectSucceedsOn200`, `deleteCustomObjectTreats404AsNoOp`, `deleteCustomObjectThrowsKubeApiExceptionOnOtherFailure`) — they use the `request()` helper as-is, which leaves `resourceVersion` null, and must keep passing unchanged after this task (proving the no-precondition case is untouched).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl engine test -Dtest=KubeOperatorTest`
Expected: FAIL — `deleteCustomObjectPassesResourceVersionAsPreconditionWhenSet` fails because `deleteRequest.body(...)` is never called by the current implementation; `deleteCustomObjectThrowsConflictExceptionOn409` fails because a 409 currently falls through to the generic `KubeApiException` branch, not `KubeApiConflictException`.

- [ ] **Step 3: Rewrite `KubeOperator.deleteCustomObject`**

Replace the current method (lines 184-207):

```java
    public void deleteCustomObject(KubeCustomObjectRequest request) {
        try {
            customObjectsApi.deleteNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            ).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return;
            }
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }
```

with:

```java
    public void deleteCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = request.getBody().getMetadata().getResourceVersion();
        try {
            CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest = customObjectsApi.deleteNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            );
            if (resourceVersion != null) {
                deleteRequest.body(new V1DeleteOptions()
                        .preconditions(new V1Preconditions().resourceVersion(resourceVersion)));
            }
            deleteRequest.execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return;
            }
            if (e.getCode() == 409) {
                throw new KubeApiConflictException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }
```

Add these two imports (the file already imports `KubeApiConflictException` from the prior fix round):
```java
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1Preconditions;
```

- [ ] **Step 4: Run the `KubeOperatorTest` tests to verify they pass**

Run: `mvn -pl engine test -Dtest=KubeOperatorTest`
Expected: PASS, 14 tests, 0 failures (12 from the prior fix round + 2 new ones).

- [ ] **Step 5: Add a retry-on-delete-conflict test to `IstioRoutesRegistrationServiceTest`**

This proves the existing retry loop in `mergeTierRoutes`/`attemptMergeTierRoutes` (unchanged by this task) covers the delete branch automatically, without needing any changes to `IstioRoutesRegistrationService` itself. Add one test:

```java
    @Test
    void removeEngineRoutesRetriesOnceOnConflictDuringDeleteThenSucceeds() {
        HTTPRouteRule onlyRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(onlyRule))));
        doThrow(new KubeApiConflictException("conflict"))
                .doNothing()
                .when(kubeOperator).deleteCustomObject(any());

        assertDoesNotThrow(() -> service.removeEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(2)).getCustomObject(any());
        verify(kubeOperator, times(2)).deleteCustomObject(any());
    }
```

Add `import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;` if not already present (it was added in the prior fix round for the write-path retry tests, so it should already be there — check before adding a duplicate).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -pl engine test -Dtest=IstioRoutesRegistrationServiceTest`
Expected: PASS, 16 tests, 0 failures (15 from the prior fix round + this 1 new one).

- [ ] **Step 7: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 8: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java
git commit -m "fix: precondition Istio HTTPRoute CR deletion on the observed resourceVersion"
```
