# Sixth-Review Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two findings from the sixth whole-branch final review: a failed undeploy permanently poisons `ChainRouteRegistry` for that chain (silently leaking its gateway routes on every future redeploy), and a stop failure during a redeploy discards the new deployment's already-staged scheduled jobs and reports it `FAILED` even though it started and is running successfully.

**Architecture:** Task 1 moves `ChainRouteRegistry.unregister` into a `finally` block in `RemoveRoutesFromControlPlaneAction`, so a deployment's registry entry never outlives that deployment's own stop attempt regardless of whether the control-plane removal succeeded — a failed removal still leaks that one attempt's routes once (unchanged from today), but the chain is no longer permanently poisoned for every subsequent redeploy. Task 2 scopes `RouteRegistrationException` specifically (the one exception type this call site can actually throw) out of `update()`'s old-context-stop loop: it's caught and logged there instead of propagating, since the old deployment's cleanup failure is unrelated to whether the new deployment — already started successfully at that point — should be reported as failed. Any other exception type still propagates and fails the redeploy, unchanged.

**Tech Stack:** Java 21, JUnit 5, Mockito (`mock(Class)` factory style).

## Global Constraints

- Task 2 catches `RouteRegistrationException` specifically, not a broader `RuntimeException` — any other exception type from a stop action during this call site must still propagate and fail `update()`, matching current behavior for genuinely unexpected failures.
- Task 2 does not touch the other two `stopDeploymentContext` call sites (`stop()`, `removeOldDeployments()`) — this is specifically about the old-context-stop loop inside a redeploy's `update()`, where an unrelated old deployment's cleanup failure shouldn't fail the new deployment's already-successful start.
- Task 1's design-spec correction: `docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md:163-167` currently justifies leaving the registry entry in place on failure as enabling "a future manual retry" — this rationale is now invalid (unregister always runs) and must be corrected, not left contradicting the shipped code.

---

### Task 1: Unregister unconditionally so a failed removal can't permanently poison a chain

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java`
- Modify: `docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md`

**Interfaces:**
- No signature changes. `execute`'s behavior changes: `chainRouteRegistry.unregister(chainId, deploymentId)` now runs whether or not `removeEngineRoutes` succeeds.

`unregister` currently only runs after a successful removal, deliberately, on the theory that leaving the entry in place lets "a future retry" recompute `getUnsharedRoutes` against still-accurate state. That theory no longer holds: `RouteRegistrationException` (thrown on failure) is a plain, non-retriable `RuntimeException`, and `stop()` already removes the Camel context from the cache before this action ever runs — so nothing ever re-attempts this specific deployment's stop. The entry becomes a permanent phantom claim on those paths: every later redeploy of the same chain computes `getUnsharedRoutes` against a registry that still thinks the failed, long-gone deployment owns those paths, and skips removing them when it, too, is eventually undeployed. The routes stay in the shared control-plane resource forever, silently, after the chain itself is gone.

- [ ] **Step 1: Write the failing test**

Replace the existing test `throwsRouteRegistrationExceptionAndLeavesTheRegistryEntryInPlaceWhenRemovalFails` (currently lines 120-131) — its assertion is exactly what this task inverts:

```java
    @Test
    void throwsRouteRegistrationExceptionAndUnregistersEvenWhenRemovalFails() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);
        doThrow(new ControlPlaneException("boom"))
                .when(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);

        assertThrows(RouteRegistrationException.class, () ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));

        assertTrue(chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }
```

Every other test in the file stays unchanged.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl engine test -Dtest=RemoveRoutesFromControlPlaneActionTest`
Expected: FAIL — `throwsRouteRegistrationExceptionAndUnregistersEvenWhenRemovalFails` fails because the current `execute` still leaves the entry in place on failure (`getUnsharedRoutes` returns the non-empty `routes` list, not empty).

- [ ] **Step 3: Move `unregister` into a `finally` block**

Replace the current `execute` method body:

```java
    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        String chainId = deploymentInfo.getChainId();
        String deploymentId = deploymentInfo.getDeploymentId();

        List<DeploymentRouteUpdate> routesToRemove = chainRouteRegistry.getUnsharedRoutes(chainId, deploymentId);
        try {
            if (!routesToRemove.isEmpty()) {
                controlPlaneService.removeEngineRoutes(routesToRemove, applicationConfiguration.getDeploymentName());
            }
            chainRouteRegistry.unregister(chainId, deploymentId);
        } catch (ControlPlaneException e) {
            throw new RouteRegistrationException("Failed to remove control plane routes for chain " + chainId, e);
        }
    }
```

with:

```java
    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        String chainId = deploymentInfo.getChainId();
        String deploymentId = deploymentInfo.getDeploymentId();

        List<DeploymentRouteUpdate> routesToRemove = chainRouteRegistry.getUnsharedRoutes(chainId, deploymentId);
        try {
            if (!routesToRemove.isEmpty()) {
                controlPlaneService.removeEngineRoutes(routesToRemove, applicationConfiguration.getDeploymentName());
            }
        } catch (ControlPlaneException e) {
            throw new RouteRegistrationException("Failed to remove control plane routes for chain " + chainId, e);
        } finally {
            chainRouteRegistry.unregister(chainId, deploymentId);
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl engine test -Dtest=RemoveRoutesFromControlPlaneActionTest`
Expected: PASS, 8 tests, 0 failures.

- [ ] **Step 5: Correct the now-invalid rationale in the design spec**

In `docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md`, find this paragraph (currently lines 163-167):

```
`unregister` still runs only after a needed removal succeeds (or is skipped entirely when
`routesToRemove` is empty, since there's nothing that can fail) — preserving the original design's
retry-safety intent: on failure, this deployment's registration entry stays in place, so recomputing
`getUnsharedRoutes` later (whether via a future manual retry or a subsequent redeploy of the same chain)
reflects the still-accurate current state rather than a stale snapshot.
```

Replace it with:

```
`unregister` runs in a `finally` block, unconditionally — including when `removeEngineRoutes` throws.
The original design left the entry in place on failure, on the theory that a future retry would
recompute `getUnsharedRoutes` against still-accurate state; that retry never happens, because
`RouteRegistrationException` is non-retriable and `stop()` has already removed this deployment's Camel
context from the cache before this action runs. Leaving the entry in place therefore didn't enable a
retry — it permanently poisoned the chain, since every later redeploy would see this dead deployment
still "claiming" its paths and skip removing them. Unregistering unconditionally means a failed removal
still leaks that one attempt's routes in the control plane (unchanged from today — there is no
mechanism that retries this specific failure either way), but the chain itself is never poisoned beyond
that single attempt.
```

- [ ] **Step 6: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 7: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md
git commit -m "fix: unregister unconditionally so a failed removal can't permanently poison a chain's route cleanup"
```

---

### Task 2: Don't fail a successfully-started redeploy over the old deployment's cleanup failure

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeService.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeServiceTest.java`

**Interfaces:**
- Adds: `void stopSupersededContext(SpringCamelContext context, DeploymentInfo deploymentInfo)` — package-private, same visibility pattern as `stopDeploymentContext` and `attachStopFailureToStartFailure` from the prior two fix rounds, so the existing test file (same package) can call it directly.

`update()`'s old-context-stop loop (currently `contextsToStop.stream().forEach(p -> stopDeploymentContext(p.getRight(), p.getLeft()));`, line 456) runs after the new deployment's context has already started successfully (line 450) but before `quartzSchedulerService.commitScheduledJobs()` (line 458) commits its staged scheduled jobs. `RemoveRoutesFromControlPlaneAction` is the only stop action that can throw here (it wraps control-plane failures in `RouteRegistrationException`; the other two stop actions, `McpToolUnregisterAction` and `SdsSchedulerRemoveJobsAction`, do no remote I/O). Since `stopDeploymentContext` already guarantees the old context is actually stopped before it rethrows (the prior fix round's change), catching `RouteRegistrationException` at this call site loses nothing — the old context is still stopped either way. What it gains: the new deployment's `commitScheduledJobs()` still runs, and `update()` still returns `DeploymentStatus.DEPLOYED` instead of the whole redeploy being reported `FAILED` over a problem in the deployment being replaced, not the one that just started.

- [ ] **Step 1: Write the failing tests**

Add these two test methods to the existing `IntegrationRuntimeServiceTest` class (add `import org.qubership.integration.platform.engine.errorhandling.RouteRegistrationException;` if not already present):

```java
    @Test
    void stopSupersededContextLogsAndDoesNotThrowWhenRouteRegistrationExceptionOccurs() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        when(context.isRunning()).thenReturn(true);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("old").chainId("c1").build();
        doThrow(new RouteRegistrationException("route removal failed"))
                .when(deploymentProcessingService).processStopContext(context, deploymentInfo, null);

        assertDoesNotThrow(() -> service.stopSupersededContext(context, deploymentInfo));

        verify(context).stop();
    }

    @Test
    void stopSupersededContextRethrowsOtherRuntimeExceptions() {
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("old").chainId("c1").build();
        RuntimeException unexpected = new IllegalStateException("boom");
        doThrow(unexpected).when(deploymentProcessingService).processStopContext(context, deploymentInfo, null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.stopSupersededContext(context, deploymentInfo));

        assertSame(unexpected, thrown);
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `mvn -pl engine test -Dtest=IntegrationRuntimeServiceTest`
Expected: compile error — `stopSupersededContext` does not exist yet on `IntegrationRuntimeService`.

- [ ] **Step 3: Add `stopSupersededContext` and use it in `update()`**

Replace the current line in `update()` (currently line 456):

```java
        contextsToStop.stream().forEach(p -> stopDeploymentContext(p.getRight(), p.getLeft()));
```

with:

```java
        contextsToStop.forEach(p -> stopSupersededContext(p.getRight(), p.getLeft()));
```

Add the new method right after `stopDeploymentContext` (which itself was added in an earlier fix round and currently ends around line 733; place this one immediately after it, before `attachStopFailureToStartFailure`):

```java
    void stopSupersededContext(SpringCamelContext context, DeploymentInfo deploymentInfo) {
        try {
            stopDeploymentContext(context, deploymentInfo);
        } catch (RouteRegistrationException e) {
            log.error("Failed to remove control plane routes while stopping the superseded deployment {} for chain {}: {}",
                    deploymentInfo.getDeploymentId(), deploymentInfo.getChainId(), e.getMessage(), e);
        }
    }
```

Add `import org.qubership.integration.platform.engine.errorhandling.RouteRegistrationException;` to the production file's imports.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl engine test -Dtest=IntegrationRuntimeServiceTest`
Expected: PASS, 9 tests, 0 failures (7 from the prior two fix rounds plus these 2).

- [ ] **Step 5: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeService.java engine/src/test/java/org/qubership/integration/platform/engine/service/IntegrationRuntimeServiceTest.java
git commit -m "fix: don't fail a successfully-started redeploy over the old deployment's route-cleanup failure"
```
