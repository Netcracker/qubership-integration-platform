# Fourth-Review Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two findings from the fourth whole-branch final review that the human directed a fix for: a stop-time action failure during a start-failure teardown now destroys the original deploy error instead of chaining onto it, and the shared Istio `HTTPRoute` custom resources are vulnerable to a silent cross-replica lost update.

**Architecture:** Task 1 extracts the `update()` start-failure catch block's stop-action call into a small, directly testable method that attaches any stop-action failure to the original exception via `addSuppressed` rather than letting it replace `throw e` — mirroring the same "run the sequence, preserve the real failure" philosophy already applied to `stopDeploymentContext`. Task 2 makes `IstioRoutesRegistrationService`'s read-merge-write cycle carry the resourceVersion it actually observed into the write (instead of `KubeOperator` silently re-fetching a fresh one, which defeats optimistic concurrency entirely), and adds a bounded retry loop that re-reads, re-merges, and re-writes when the K8s API reports a conflict.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito (`mock(Class)` factory style).

## Global Constraints

- Task 1 must not change `update()`'s control flow beyond the stop-action call: `startContext`'s original exception is always the one thrown from the catch block, never replaced.
- Task 2's retry must be bounded (no infinite retry loop) and must only retry on the specific "someone else wrote first" conflict signal (HTTP 409) — any other Kubernetes API failure still fails immediately, matching current behavior.
- Task 2 does not touch `deleteCustomObject` or the CR-deletion branch of `mergeTierRoutes` — the human's directive was specifically about the create/replace write path (`createOrReplaceCustomObject`'s resourceVersion handling), which is where Important #3 was found.
- Full spec/review history for this branch (context only, not modified by this plan): `docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md`, `.superpowers/sdd/2026-07-31-istio-routes-registration-service/progress.md`.

---

### Task 1: Preserve the original start failure when stop-time cleanup also fails

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeService.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeServiceTest.java`

**Interfaces:**
- Adds: `void attachStopFailureToStartFailure(Exception startFailure, SpringCamelContext context, DeploymentInfo deploymentInfo, DeploymentConfiguration configuration)` — package-private (no modifier), same visibility pattern as `stopDeploymentContext` from the prior fix round, so the existing test file (same package) can call it directly.

`update()`'s start-failure catch block (currently lines 449-455) calls `deploymentProcessingService.processStopContext(...)` directly, not through `stopDeploymentContext`. Since `DeploymentProcessingService` has no try/catch anywhere, if a stop action throws (e.g. `RemoveRoutesFromControlPlaneAction` failing to remove a route the failed deployment had newly registered), that throw propagates from this line instead of `throw e` — destroying the original `startContext` failure (bad XML, a MaaS resolution error, a Groovy compile failure) and reclassifying its retriability. This task attaches the stop failure as a suppressed exception on the original one instead, so the original failure is always what gets thrown, logged, and reported — with the stop failure still visible in the stack trace for diagnosis.

- [ ] **Step 1: Write the failing tests**

Add these two test methods to the existing `IntegrationRuntimeServiceTest` class (the file and its `setUp()`/mocks already exist from the prior fix round — do not recreate them, just add these methods and the two new imports they need: `org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration` and `static org.junit.jupiter.api.Assertions.assertEquals` if not already present):

```java
    @Test
    void attachesStopFailureAsSuppressedAndDoesNotReplaceTheOriginalStartFailure() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        DeploymentConfiguration configuration = DeploymentConfiguration.builder().build();
        Exception startFailure = new RuntimeException("bad xml");
        RuntimeException stopFailure = new RuntimeException("route removal failed");
        doThrow(stopFailure).when(deploymentProcessingService)
                .processStopContext(context, deploymentInfo, configuration);

        service.attachStopFailureToStartFailure(startFailure, context, deploymentInfo, configuration);

        assertEquals(1, startFailure.getSuppressed().length);
        assertSame(stopFailure, startFailure.getSuppressed()[0]);
        verify(quartzSchedulerService).commitScheduledJobs();
    }

    @Test
    void doesNotAddASuppressedExceptionWhenStopActionsSucceed() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        DeploymentConfiguration configuration = DeploymentConfiguration.builder().build();
        Exception startFailure = new RuntimeException("bad xml");

        service.attachStopFailureToStartFailure(startFailure, context, deploymentInfo, configuration);

        assertEquals(0, startFailure.getSuppressed().length);
        verify(quartzSchedulerService).commitScheduledJobs();
        verify(deploymentProcessingService).processStopContext(context, deploymentInfo, configuration);
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `mvn -pl engine test -Dtest=IntegrationRuntimeServiceTest`
Expected: compile error — `attachStopFailureToStartFailure` does not exist yet on `IntegrationRuntimeService`.

- [ ] **Step 3: Extract the method and use it in `update()`**

Replace the current start-failure catch block in `update()` (currently lines 449-455):

```java
        try {
            startContext(context);
        } catch (Exception e) {
            quartzSchedulerService.commitScheduledJobs();
            deploymentProcessingService.processStopContext(context, deploymentInfo, configuration);
            throw e;
        }
```

with:

```java
        try {
            startContext(context);
        } catch (Exception e) {
            attachStopFailureToStartFailure(e, context, deploymentInfo, configuration);
            throw e;
        }
```

Add the new method right after `stopDeploymentContext` (currently ending at line 733):

```java
    void attachStopFailureToStartFailure(
        Exception startFailure,
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration configuration
    ) {
        quartzSchedulerService.commitScheduledJobs();
        try {
            deploymentProcessingService.processStopContext(context, deploymentInfo, configuration);
        } catch (RuntimeException stopFailure) {
            startFailure.addSuppressed(stopFailure);
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl engine test -Dtest=IntegrationRuntimeServiceTest`
Expected: PASS, 7 tests, 0 failures (5 from the prior fix round plus these 2).

- [ ] **Step 5: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeService.java engine/src/test/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeServiceTest.java
git commit -m "fix: preserve the original start failure when stop-time cleanup also fails"
```

---

### Task 2: Fix the shared Istio HTTPRoute CR's cross-replica lost-update race

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/KubeApiConflictException.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`

**Interfaces:**
- Adds: `KubeApiConflictException extends KubeApiException`, thrown by `KubeOperator.createOrReplaceCustomObject` when the Kubernetes API reports an HTTP 409 (on either the create or the replace branch).
- Changes `KubeOperator.createOrReplaceCustomObject(KubeCustomObjectRequest request)`'s behavior (not its signature): it now branches on `request.getBody().getMetadata().getResourceVersion()` (set by the caller) instead of performing its own internal GET to decide create-vs-replace and to obtain a resourceVersion.
- Removes: `KubeOperator.getCustomObjectResourceVersion` (private, now unused) and its now-unused `com.fasterxml.jackson.databind.JsonNode` import.
- `IstioRoutesRegistrationService.mergeTierRoutes` keeps its exact public-facing behavior (same three callers, same exceptions surfaced to `ControlPlaneService`'s contract) but gains an internal bounded retry loop; the per-attempt logic moves into a new private `attemptMergeTierRoutes` method with the same parameter list `mergeTierRoutes` already has.

`mergeTierRoutes` reads the current CR with `kubeOperator.getCustomObject(...)`, computes a merge, then calls `kubeOperator.createOrReplaceCustomObject(...)`. That method currently does its own separate GET immediately before writing, purely to decide create-vs-replace and to grab *whatever the current resourceVersion is* — completely disconnected from the resourceVersion that was present when `mergeTierRoutes` actually read the state it merged against. This defeats Kubernetes' optimistic-concurrency mechanism: two replica pods racing on the same shared CR don't get a loud conflict error, since each write is always accepted with the freshest possible resourceVersion. Whichever write lands last silently overwrites the other's changes — and since one CR's rules span every chain that pod hosts (not just the chain currently being deployed), the loss isn't confined to one chain's own redeploy.

The fix: `mergeTierRoutes` observes the resourceVersion at the same moment it reads the rules it merges against, and passes that exact resourceVersion into the write. `createOrReplaceCustomObject` stops doing its own GET and simply respects what it's given: a non-null resourceVersion means replace-with-that-version (which the Kubernetes API rejects with 409 if it's stale), a null resourceVersion means create (which the API rejects with 409 if the object already exists, e.g. another replica created it in between). Either 409 is a genuine "someone else changed this since I looked" signal, surfaced as `KubeApiConflictException`. `mergeTierRoutes` catches that specifically and retries the whole read-merge-write cycle (up to 3 attempts total) against fresh state, rather than treating it as a terminal failure.

- [ ] **Step 1: Write the new exception class**

```java
/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.errorhandling;

public class KubeApiConflictException extends KubeApiException {
    public KubeApiConflictException() {
        super();
    }

    public KubeApiConflictException(String message) {
        super(message);
    }

    public KubeApiConflictException(String message, Exception exception) {
        super(message, exception);
    }
}
```

Save to `engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/KubeApiConflictException.java`.

- [ ] **Step 2: Write the failing `KubeOperatorTest` tests**

Replace the four existing `createOrReplaceCustomObject*` tests (currently at lines 123-213: `createOrReplaceCustomObjectCreatesWhenNoResourceVersionExists`, `createOrReplaceCustomObjectReplacesWhenResourceVersionExists`, `createOrReplaceCustomObjectThrowsKubeApiExceptionOnCreateFailure`, `createOrReplaceCustomObjectThrowsKubeApiExceptionOnReplaceFailure`) with these six — the old tests assert the internal-GET-driven behavior this task removes:

```java
    @Test
    void createOrReplaceCustomObjectCreatesWhenNoResourceVersionIsSet() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrReplaceCustomObject(request()));

        verify(createRequest).execute();
        verify(customObjectsApi, never()).getNamespacedCustomObject(any(), any(), any(), any(), any());
    }

    @Test
    void createOrReplaceCustomObjectReplacesWhenResourceVersionIsSet() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrReplaceCustomObject(req));

        verify(replaceRequest).execute();
        verify(customObjectsApi, never()).getNamespacedCustomObject(any(), any(), any(), any(), any());
    }

    @Test
    void createOrReplaceCustomObjectThrowsConflictExceptionOn409DuringCreate() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "AlreadyExists"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrReplaceCustomObject(request()));
    }

    @Test
    void createOrReplaceCustomObjectThrowsConflictExceptionOn409DuringReplace() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrReplaceCustomObject(req));
    }

    @Test
    void createOrReplaceCustomObjectThrowsKubeApiExceptionOnOtherCreateFailure() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.createOrReplaceCustomObject(request()));
        assertFalse(exception instanceof KubeApiConflictException);
    }

    @Test
    void createOrReplaceCustomObjectThrowsKubeApiExceptionOnOtherReplaceFailure() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.createOrReplaceCustomObject(req));
        assertFalse(exception instanceof KubeApiConflictException);
    }
```

Add `import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;` to the test file's imports.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -pl engine test -Dtest=KubeOperatorTest`
Expected: FAIL — `KubeApiConflictException` doesn't exist as an importable production type yet (Step 1 created the file, so this should actually be a behavioral failure, not a compile error: the "never getNamespacedCustomObject" assertions fail because the current `createOrReplaceCustomObject` still calls it internally, and the 409 tests fail because 409 currently isn't special-cased).

- [ ] **Step 4: Rewrite `KubeOperator.createOrReplaceCustomObject`**

Replace the current method (lines 127-155):

```java
    public void createOrReplaceCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = getCustomObjectResourceVersion(request);
        try {
            if (resourceVersion != null) {
                request.getBody().getMetadata().setResourceVersion(resourceVersion);
                customObjectsApi.replaceNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        getNotNullCustomResourceName(request),
                        request.getBody()
                ).execute();
            } else {
                customObjectsApi.createNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        request.getBody()
                ).execute();
            }
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
    public void createOrReplaceCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = request.getBody().getMetadata().getResourceVersion();
        try {
            if (resourceVersion != null) {
                customObjectsApi.replaceNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        getNotNullCustomResourceName(request),
                        request.getBody()
                ).execute();
            } else {
                customObjectsApi.createNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        request.getBody()
                ).execute();
            }
        } catch (ApiException e) {
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

Delete the now-unused `getCustomObjectResourceVersion` private method (lines 213-240) entirely, and remove the now-unused `import com.fasterxml.jackson.databind.JsonNode;` line. Add `import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;`.

- [ ] **Step 5: Run the `KubeOperatorTest` tests to verify they pass**

Run: `mvn -pl engine test -Dtest=KubeOperatorTest`
Expected: PASS, 12 tests, 0 failures (6 unchanged tests + 6 new ones from Step 2).

- [ ] **Step 6: Write the failing `IstioRoutesRegistrationServiceTest` tests**

Add these four test methods to the existing test class:

```java
    @Test
    void postPublicEngineRoutesCarriesTheObservedResourceVersionIntoTheWrite() {
        KubeCustomObject existing = existingCr(List.of());
        existing.getMetadata().setResourceVersion("42");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existing));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        assertEquals("42", captor.getValue().getBody().getMetadata().getResourceVersion());
    }

    @Test
    void postPublicEngineRoutesLeavesResourceVersionNullWhenNoCrExists() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        assertNull(captor.getValue().getBody().getMetadata().getResourceVersion());
    }

    @Test
    void postPublicEngineRoutesRetriesOnceOnConflictThenSucceeds() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict"))
                .doNothing()
                .when(kubeOperator).createOrReplaceCustomObject(any());

        assertDoesNotThrow(() -> service.postPublicEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(2)).getCustomObject(any());
        verify(kubeOperator, times(2)).createOrReplaceCustomObject(any());
    }

    @Test
    void postPublicEngineRoutesGivesUpAfterThreeConflictsAndWrapsAsControlPlaneException() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict")).when(kubeOperator).createOrReplaceCustomObject(any());

        assertThrows(ControlPlaneException.class, () -> service.postPublicEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, times(3)).getCustomObject(any());
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(any());
    }
```

Add `import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;` and `import static org.junit.jupiter.api.Assertions.assertNull;` to the test file's imports if not already present (check the existing wildcard `static org.junit.jupiter.api.Assertions.*` import — if present, `assertNull` needs no separate import).

- [ ] **Step 7: Run the tests to verify they fail**

Run: `mvn -pl engine test -Dtest=IstioRoutesRegistrationServiceTest`
Expected: FAIL — `mergeTierRoutes` doesn't yet set `resourceVersion` on the write request, and doesn't retry on `KubeApiConflictException`.

- [ ] **Step 8: Add the retry loop to `IstioRoutesRegistrationService`**

Replace the current `mergeTierRoutes` method (lines 107-159):

```java
    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
            List<HTTPRouteRule> existingRules = current
                    .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                    .map(HTTPRouteSpec::getRules)
                    .filter(Objects::nonNull)
                    .orElse(List.of());

            Set<String> touchedPaths = givenRoutes.stream()
                    .map(route -> baseRoutePrefix + route.getPath())
                    .collect(Collectors.toSet());

            List<HTTPRouteRule> preservedRules = existingRules.stream()
                    .filter(rule -> !touchedPaths.contains(matchPath(rule)))
                    .toList();

            List<HTTPRouteRule> newRules = buildRules
                    ? givenRoutes.stream().map(route -> buildRule(route, backendName)).toList()
                    : List.of();

            List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
            mergedRules.addAll(newRules);

            if (mergedRules.isEmpty()) {
                if (current.isPresent()) {
                    kubeOperator.deleteCustomObject(tierRequest);
                }
                return;
            }

            HTTPRouteSpec spec = HTTPRouteSpec.builder()
                    .parentRefs(parentRefs(gatewayName))
                    .rules(mergedRules)
                    .build();
            tierRequest.getBody().setSpec(objectMapper.convertValue(spec, new TypeReference<Map<String, Object>>() {}));
            kubeOperator.createOrReplaceCustomObject(tierRequest);
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update Istio HTTPRoute for control plane routes: {}", e.getMessage());
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }
```

with:

```java
    private static final int MAX_MERGE_ATTEMPTS = 3;

    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            for (int attempt = 1; attempt <= MAX_MERGE_ATTEMPTS; attempt++) {
                try {
                    attemptMergeTierRoutes(tierRequest, givenRoutes, gatewayName, backendName, buildRules);
                    return;
                } catch (KubeApiConflictException e) {
                    if (attempt == MAX_MERGE_ATTEMPTS) {
                        throw e;
                    }
                    log.warn("Concurrent update detected for {} on attempt {}/{}, retrying",
                            tierRequest.getBody().getMetadata().getName(), attempt, MAX_MERGE_ATTEMPTS);
                }
            }
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update Istio HTTPRoute for control plane routes: {}", e.getMessage());
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }

    private void attemptMergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
        List<HTTPRouteRule> existingRules = current
                .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                .map(HTTPRouteSpec::getRules)
                .filter(Objects::nonNull)
                .orElse(List.of());

        Set<String> touchedPaths = givenRoutes.stream()
                .map(route -> baseRoutePrefix + route.getPath())
                .collect(Collectors.toSet());

        List<HTTPRouteRule> preservedRules = existingRules.stream()
                .filter(rule -> !touchedPaths.contains(matchPath(rule)))
                .toList();

        List<HTTPRouteRule> newRules = buildRules
                ? givenRoutes.stream().map(route -> buildRule(route, backendName)).toList()
                : List.of();

        List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
        mergedRules.addAll(newRules);

        tierRequest.getBody().getMetadata().setResourceVersion(
                current.map(obj -> obj.getMetadata().getResourceVersion()).orElse(null));

        if (mergedRules.isEmpty()) {
            if (current.isPresent()) {
                kubeOperator.deleteCustomObject(tierRequest);
            }
            return;
        }

        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(parentRefs(gatewayName))
                .rules(mergedRules)
                .build();
        tierRequest.getBody().setSpec(objectMapper.convertValue(spec, new TypeReference<Map<String, Object>>() {}));
        kubeOperator.createOrReplaceCustomObject(tierRequest);
    }
```

Add `import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;` to the production file's imports. The exception-wrapping behavior (`ControlPlaneException` passthrough, everything else wrapped) is unchanged — it now wraps the outer retry loop instead of the single attempt, so a `KubeApiConflictException` that exhausts all attempts still ends up wrapped as `ControlPlaneException` exactly as before.

- [ ] **Step 9: Run the `IstioRoutesRegistrationServiceTest` tests to verify they pass**

Run: `mvn -pl engine test -Dtest=IstioRoutesRegistrationServiceTest`
Expected: PASS, 15 tests, 0 failures (11 unchanged tests + 4 new ones from Step 6).

- [ ] **Step 10: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 11: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/KubeApiConflictException.java engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java
git commit -m "fix: retry Istio HTTPRoute merge on resourceVersion conflict instead of silently dropping the loser's write"
```
