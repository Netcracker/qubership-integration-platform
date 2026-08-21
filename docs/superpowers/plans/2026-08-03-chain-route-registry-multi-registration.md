# Multi-registration `ChainRouteRegistry` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ChainRouteRegistry`'s single-owner model with multiple simultaneous
per-chain registrations and a path-level sharing check, and make a stop-time route-removal
failure fail the deployment instead of silently re-entering the retry queue as an update.

**Architecture:** `ChainRouteRegistry` moves from `chainId → (deploymentId, routes)` to
`chainId → {deploymentId → routes}`. `getUnsharedRoutes(chainId, deploymentId)` returns only
the calling deployment's routes whose *path* no other currently-registered deployment for the
same chain also claims. `RemoveRoutesFromControlPlaneAction` consumes this instead of the old
ownership check, and throws a new `RouteRegistrationException` (not
`DeploymentRetriableException`) on a control-plane failure, so
`IntegrationRuntimeService.processDeploymentUpdate`'s catch chain falls through to
`catch (Throwable)` and marks the deployment `FAILED` rather than requeuing it as an update.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito (`mock(Class)` factory style).

## Global Constraints

- `ChainRouteRegistry.register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes)`
  keeps its exact name and signature — `RegisterRoutesInControlPlaneAction`
  (`engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java:92-93`)
  is not modified by this plan.
- `getIfCurrentOwner`/`clearIfCurrentOwner` are removed entirely and replaced by
  `getUnsharedRoutes`/`unregister` — no method keeps the old names.
- `RouteRegistrationException` mirrors `DeploymentRetriableException`'s exact constructor set
  (`engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/DeploymentRetriableException.java`).
- No change to `ControlPlaneService`'s contract, either implementation
  (`ControlPlaneDefaultService`, `IstioRoutesRegistrationService`), or `micro-engine`.
- Full spec: `docs/superpowers/specs/2026-08-03-chain-route-registry-multi-registration-design.md`
  (commit `f1efb8a2`).

---

### Task 1: Multi-registration `ChainRouteRegistry`

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java`

**Interfaces:**
- Produces: `void register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes)`
  (name/signature unchanged from the current file), `List<DeploymentRouteUpdate> getUnsharedRoutes(String chainId, String deploymentId)`,
  `void unregister(String chainId, String deploymentId)`. These two replace
  `Optional<List<DeploymentRouteUpdate>> getIfCurrentOwner(String, String)` and
  `void clearIfCurrentOwner(String, String)`, which are deleted. Task 2's rewrite of
  `RemoveRoutesFromControlPlaneAction` consumes `getUnsharedRoutes` and `unregister`.

The current file (`ChainRouteRegistry.java`) keys a chain to a single `(deploymentId, routes)`
pair, so a redeploy's old-deployment cleanup wrongly finds itself "not the owner" and skips —
but only once the *new* deployment has already overwritten the entry. The gap: nothing tracks
the transition window where both deployments are legitimately registered at once, so the
*other* direction (the new deployment's own start-failure teardown) can't tell that the old
deployment still needs the same paths. `DeploymentRouteUpdate` already exposes `getPath()`
(via `@Getter` on the class) — this is the only field needed to detect a shared path.

- [ ] **Step 1: Replace the test file with tests against the new API**

Every method on the current `ChainRouteRegistryTest` (`getIfCurrentOwner...`,
`clearIfCurrentOwner...`) targets a method this task deletes, so the whole file is replaced in
one step rather than edited incrementally. Write this complete file:

```java
package org.qubership.integration.platform.engine.controlplane;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRouteRegistryTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";

    private final ChainRouteRegistry registry = new ChainRouteRegistry();

    @Test
    void getUnsharedRoutesReturnsAllRoutesWhenNoOtherDeploymentIsRegisteredForTheChain() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertEquals(routes, result);
    }

    @Test
    void getUnsharedRoutesReturnsEmptyListForADeploymentThatNeverRegistered() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B);

        assertTrue(result.isEmpty());
    }

    @Test
    void getUnsharedRoutesReturnsEmptyListWhenTheChainWasNeverRegistered() {
        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isEmpty());
    }

    @Test
    void getUnsharedRoutesExcludesPathsClaimedByAnotherRegisteredDeployment() {
        // A and B both claim /shared (the redeploy overlap window); A alone claims /old-only.
        DeploymentRouteUpdate shared = route("/shared");
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared, route("/old-only")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared, route("/new-only")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A);

        assertEquals(List.of("/old-only"), result.stream().map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void getUnsharedRoutesIsSymmetricFromTheOtherDeploymentsSide() {
        // Same registry state as above, computed from B's side: the C-1 scenario, where B's
        // own start-failure teardown must not remove A's still-running route.
        DeploymentRouteUpdate shared = route("/shared");
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared, route("/old-only")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared, route("/new-only")));

        List<DeploymentRouteUpdate> result = registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B);

        assertEquals(List.of("/new-only"), result.stream().map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void registerOverwritesThisDeploymentsPreviousRoutesWithoutAffectingOtherDeployments() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/first-attempt")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/other")));

        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/retried-attempt")));

        assertEquals(List.of("/retried-attempt"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
        assertEquals(List.of("/other"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void unregisterRemovesOnlyTheNamedDeploymentsEntryLeavingOthersIntact() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        registry.unregister(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
        assertEquals(List.of("/new"),
                registry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void unregisterDoesNothingWhenTheChainWasNeverRegistered() {
        assertDoesNotThrow(() -> registry.unregister(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    private DeploymentRouteUpdate route(String path) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(RouteType.EXTERNAL_TRIGGER)
                .build();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `mvn -pl engine test -Dtest=ChainRouteRegistryTest`
Expected: compile error — `getUnsharedRoutes` and `unregister` do not exist yet on
`ChainRouteRegistry` (the production class still only has `getIfCurrentOwner`/`clearIfCurrentOwner`).

- [ ] **Step 3: Replace the production class**

Replace the full contents of `ChainRouteRegistry.java`:

```java
package org.qubership.integration.platform.engine.controlplane;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ChainRouteRegistry {
    private final Map<String, Map<String, List<DeploymentRouteUpdate>>> registrationsByChainId = new ConcurrentHashMap<>();

    public void register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes) {
        registrationsByChainId
                .computeIfAbsent(chainId, id -> new ConcurrentHashMap<>())
                .put(deploymentId, routes);
    }

    public List<DeploymentRouteUpdate> getUnsharedRoutes(String chainId, String deploymentId) {
        Map<String, List<DeploymentRouteUpdate>> byDeployment = registrationsByChainId.get(chainId);
        if (byDeployment == null) {
            return List.of();
        }
        List<DeploymentRouteUpdate> ownRoutes = byDeployment.get(deploymentId);
        if (ownRoutes == null) {
            return List.of();
        }
        Set<String> pathsClaimedByOthers = byDeployment.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(deploymentId))
                .flatMap(entry -> entry.getValue().stream())
                .map(DeploymentRouteUpdate::getPath)
                .collect(Collectors.toSet());
        return ownRoutes.stream()
                .filter(route -> !pathsClaimedByOthers.contains(route.getPath()))
                .toList();
    }

    public void unregister(String chainId, String deploymentId) {
        registrationsByChainId.computeIfPresent(chainId, (id, byDeployment) -> {
            byDeployment.remove(deploymentId);
            return byDeployment.isEmpty() ? null : byDeployment;
        });
    }
}
```

Both inner and outer maps are `ConcurrentHashMap` for safety across *different* chains being
processed concurrently — same "no new locking" rationale as the original single-owner design:
`IntegrationRuntimeService`'s existing per-chain lock already serializes a chain's own
register/stop sequence on one thread, so this class needs no synchronization of its own.

The spec's testing section also asks for "cleans up the chain's outer entry once empty (but
not before)". The "not before" half is the
`unregisterRemovesOnlyTheNamedDeploymentsEntryLeavingOthersIntact` test above. The "once empty"
half is not independently observable through this class's public API: whether the outer entry
is actually removed or merely left as an empty inner map, `register` (via `computeIfAbsent`)
and `getUnsharedRoutes` (via the `byDeployment == null` / `ownRoutes == null` checks) behave
identically either way. It is a pure memory-footprint property of the
`computeIfPresent(..., byDeployment.isEmpty() ? null : byDeployment)` line in `unregister`,
verified by code inspection during task review rather than by a black-box assertion — do not
treat its absence as an uncovered spec requirement.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl engine test -Dtest=ChainRouteRegistryTest`
Expected: PASS, 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java
git commit -m "feat: support multiple simultaneous chain route registrations"
```

---

### Task 2: `RouteRegistrationException` and `RemoveRoutesFromControlPlaneAction` rewrite

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/RouteRegistrationException.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java`
- Modify: `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java`

**Interfaces:**
- Consumes: Task 1's `ChainRouteRegistry.getUnsharedRoutes(String chainId, String deploymentId): List<DeploymentRouteUpdate>`
  and `ChainRouteRegistry.unregister(String chainId, String deploymentId): void`.
- Produces: `RouteRegistrationException extends RuntimeException`, thrown by
  `RemoveRoutesFromControlPlaneAction.execute()` in place of `DeploymentRetriableException`.
  Nothing else in this plan consumes it directly — it changes behavior by which `catch` clause
  in `IntegrationRuntimeService.processDeploymentUpdate` matches it (unmodified code, out of
  scope for this plan; verified in the design spec's tracing).

`IntegrationRuntimeService.processDeploymentUpdate`'s catch chain is
`catch (KubeApiException) → catch (DeploymentRetriableException) → catch (Throwable)`. A
`RouteRegistrationException` matches neither of the first two, so it falls to
`catch (Throwable e)`: the deployment is marked `FAILED` (not requeued via
`putInRetryQueue`), instead of being silently retried as an `UPDATE` of the chain that was just
being torn down.

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

public class RouteRegistrationException extends RuntimeException {
    public RouteRegistrationException() {
        super();
    }

    public RouteRegistrationException(String message, Exception exception) {
        super(message, exception);
    }

    public RouteRegistrationException(String message) {
        super(message);
    }

    public RouteRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public RouteRegistrationException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
```

Save to `engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/RouteRegistrationException.java`.
This has no test of its own (a constructor-only exception class, matching
`DeploymentRetriableException`, which likewise has none) — Task 2's Step 3 test file exercises
it as the type `RemoveRoutesFromControlPlaneAction` throws.

- [ ] **Step 2: Run the module to confirm the new class compiles**

Run: `mvn -pl engine compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Replace the test file with tests against the new behavior**

`RemoveRoutesFromControlPlaneActionTest` currently calls `chainRouteRegistry.getIfCurrentOwner`
and asserts `DeploymentRetriableException` — both gone after this task. Replace the whole file:

```java
package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.RouteRegistrationException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before.RegisterRoutesInControlPlaneAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoveRoutesFromControlPlaneActionTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";
    private static final String DEPLOYMENT_NAME = "engine-service";

    private ControlPlaneService controlPlaneService;
    private ApplicationAutoConfiguration applicationConfiguration;
    private ChainRouteRegistry chainRouteRegistry;
    private RemoveRoutesFromControlPlaneAction removeAction;

    @BeforeEach
    void setUp() {
        controlPlaneService = mock(ControlPlaneService.class);
        applicationConfiguration = mock(ApplicationAutoConfiguration.class);
        when(applicationConfiguration.getDeploymentName()).thenReturn(DEPLOYMENT_NAME);
        chainRouteRegistry = new ChainRouteRegistry();
        removeAction = new RemoveRoutesFromControlPlaneAction(
                controlPlaneService, applicationConfiguration, chainRouteRegistry);
    }

    @Test
    void doesNotThrowWhenDeploymentConfigurationIsNull() {
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        assertDoesNotThrow(() ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));
    }

    @Test
    void removesRoutesRegisteredByTheSameDeploymentWhenNothingElseClaimsThem() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);
    }

    @Test
    void clearsTheRegistryEntryAfterASuccessfulRemoval() {
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        assertTrue(chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    @Test
    void doesNothingWhenNothingWasEverRegisteredForTheChain() {
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
    }

    @Test
    void redeployOverlapRemovesOnlyThePathsNotClaimedByTheNewerDeploymentAndStillUnregistersTheCaller() {
        // C2: A's stop runs after B already registered the identical trigger path, plus A has
        // a path B dropped.
        DeploymentRouteUpdate shared = route("/shared");
        DeploymentRouteUpdate dropped = route("/dropped");
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared, dropped));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared, route("/added")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService).removeEngineRoutes(List.of(dropped), DEPLOYMENT_NAME);
        assertTrue(chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
        assertEquals(List.of("/shared", "/added"),
                chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).stream()
                        .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void mirrorSideStartFailureTeardownDoesNotRemoveTheStillRunningOldDeploymentsRoutes() {
        // C-1: B's own start-failure teardown runs while A is still the running deployment,
        // and both share the identical trigger path.
        DeploymentRouteUpdate shared = route("/shared");
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(shared));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(shared));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_B), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
        assertTrue(chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_B).isEmpty());
        assertEquals(List.of(shared), chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    @Test
    void throwsRouteRegistrationExceptionAndLeavesTheRegistryEntryInPlaceWhenRemovalFails() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);
        doThrow(new ControlPlaneException("boom"))
                .when(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);

        assertThrows(RouteRegistrationException.class, () ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));

        assertEquals(routes, chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    @Test
    void endToEndRedeployOrderingDoesNotDeleteTheNewDeploymentsJustRegisteredRoutes() {
        // Reproduces the real IntegrationRuntimeService.update() ordering: the new deployment
        // registers first (via the real RegisterRoutesInControlPlaneAction, sharing this same
        // registry), then the old deployment's stop action runs.
        VariablesService variablesService = mock(VariablesService.class);
        RegisterRoutesInControlPlaneAction registerAction = new RegisterRoutesInControlPlaneAction(
                variablesService, controlPlaneService, applicationConfiguration, chainRouteRegistry);

        DeploymentRouteUpdate sameTriggerPath = route("/chain-1", RouteType.EXTERNAL_TRIGGER);
        DeploymentConfiguration configuration = DeploymentConfiguration.builder()
                .routes(List.of(sameTriggerPath))
                .build();

        // Deployment A originally registered this same path (simulating the prior deploy).
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(sameTriggerPath));

        // Deployment B (the new redeploy) registers the identical trigger path.
        registerAction.execute(null, deploymentInfo(DEPLOYMENT_ID_B), configuration);
        clearInvocations(controlPlaneService);

        // Now the old deployment A's stop runs, as IntegrationRuntimeService does after the
        // new context has already started.
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
        assertTrue(chainRouteRegistry.getUnsharedRoutes(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    private DeploymentInfo deploymentInfo(String deploymentId) {
        return DeploymentInfo.builder()
                .deploymentId(deploymentId)
                .chainId(CHAIN_ID)
                .build();
    }

    private DeploymentRouteUpdate route(String path) {
        return route(path, RouteType.EXTERNAL_TRIGGER);
    }

    private DeploymentRouteUpdate route(String path, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(type)
                .build();
    }
}
```

- [ ] **Step 4: Run the test to confirm it fails**

Run: `mvn -pl engine test -Dtest=RemoveRoutesFromControlPlaneActionTest`
Expected: compile error — `ChainRouteRegistry.getUnsharedRoutes` does not exist on the
`RemoveRoutesFromControlPlaneAction`'s current production code path (it still calls
`getIfCurrentOwner`/`clearIfCurrentOwner`), and `RouteRegistrationException` is never thrown by
the current implementation.

- [ ] **Step 5: Replace the `execute` method in `RemoveRoutesFromControlPlaneAction`**

Change the imports: remove
`import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;`
and `import java.util.Optional;`; add
`import org.qubership.integration.platform.engine.errorhandling.RouteRegistrationException;`.
Replace the `execute` method body:

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

`unregister` runs after a needed removal succeeds, or unconditionally when `routesToRemove` is
empty (nothing there can fail) — on failure, this deployment's registry entry stays in place so
a later retry recomputes `getUnsharedRoutes` against the current state rather than a stale one.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -pl engine test -Dtest=RemoveRoutesFromControlPlaneActionTest`
Expected: PASS, 8 tests, 0 failures.

- [ ] **Step 7: Run the full engine test suite**

Run: `mvn -pl engine test`
Expected: BUILD SUCCESS, no regressions (pre-existing WARN/ERROR noise from unreachable
Consul/OpenSearch/KubeOperator in the test environment is expected and unrelated).

- [ ] **Step 8: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/errorhandling/RouteRegistrationException.java engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java
git commit -m "fix: stop deleting a redeploy's routes on start failure, fail undeploy instead of mis-retrying it"
```
