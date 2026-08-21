# Chain Route Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `RemoveRoutesFromControlPlaneAction` so it works on every real stop path (it currently NPEs on all but one) and so a redeploy's old-deployment cleanup never deletes the new deployment's just-registered routes.

**Architecture:** A new `ChainRouteRegistry` component tracks the current `(deploymentId, routes)` for each chain. `RegisterRoutesInControlPlaneAction` records its own registration there after posting; `RemoveRoutesFromControlPlaneAction` reads from it instead of the (always-null-at-stop-time) `DeploymentConfiguration`, and only removes routes if it's still the chain's recorded owner — which a superseded deployment (from a redeploy) never is, since the new deployment's registration overwrites the chain's entry first.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-03-chain-route-registry-design.md`

## Global Constraints

- Tests use plain JUnit 5 (`org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Assertions.*`) and Mockito's `mock(Class)` factory with `static org.mockito.Mockito.*` imports — matches the existing convention in this codebase (`McpToolUnregisterActionTest`), not `@ExtendWith(MockitoExtension.class)` + `@Mock`.
- `ChainRouteRegistry` adds no locking of its own beyond `ConcurrentHashMap`'s per-key atomicity — the existing per-chain lock in `IntegrationRuntimeService` already serializes a chain's own register-then-stop sequence on one thread. Do not add `synchronized` or explicit locks to this class.
- `RemoveRoutesFromControlPlaneAction`'s `deploymentConfiguration` parameter stays in the method signature (required by the `DeploymentProcessingAction` interface) but must not be read — the whole point of this fix is that it's unreliable at stop time.
- Do not change `ControlPlaneService`'s contract or either implementation (`ControlPlaneDefaultService`, `IstioRoutesRegistrationService`) — this is entirely an orchestration-layer fix.
- Do not touch `micro-engine`.

## File Structure

- **Create** `engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java` — the new chain-scoped ownership registry.
- **Create** `engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java`.
- **Modify** `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java` — records its registration in the registry after posting.
- **Create** `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java`.
- **Modify** `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java` — reads from the registry instead of `DeploymentConfiguration`.
- **Create** `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java`.

---

### Task 1: `ChainRouteRegistry`

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java`

**Interfaces:**
- Produces: `ChainRouteRegistry` — `@Component`, methods `register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes): void`, `getIfCurrentOwner(String chainId, String deploymentId): Optional<List<DeploymentRouteUpdate>>`, `clearIfCurrentOwner(String chainId, String deploymentId): void`. Task 2 constructs this via Spring injection and calls all three methods.

- [ ] **Step 1: Write the failing test**

Create `engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java`:

```java
package org.qubership.integration.platform.engine.controlplane;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRouteRegistryTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID_A = "deployment-a";
    private static final String DEPLOYMENT_ID_B = "deployment-b";

    private final ChainRouteRegistry registry = new ChainRouteRegistry();

    @Test
    void getIfCurrentOwnerReturnsRegisteredRoutesForOwningDeployment() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isPresent());
        assertEquals(routes, result.get());
    }

    @Test
    void getIfCurrentOwnerReturnsEmptyForNonOwningDeployment() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);

        assertTrue(result.isEmpty());
    }

    @Test
    void getIfCurrentOwnerReturnsEmptyWhenChainWasNeverRegistered() {
        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(result.isEmpty());
    }

    @Test
    void registerOverwritesPreviousDeploymentForSameChain() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        assertTrue(registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);
        assertTrue(result.isPresent());
        assertEquals("/new", result.get().get(0).getPath());
    }

    @Test
    void clearIfCurrentOwnerRemovesEntryWhenDeploymentIdMatches() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        assertTrue(registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    @Test
    void clearIfCurrentOwnerDoesNothingWhenDeploymentIdDoesNotMatch() {
        registry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        registry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        // A stale clear from the superseded deployment must not clear the newer registration.
        registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A);

        Optional<List<DeploymentRouteUpdate>> result = registry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B);
        assertTrue(result.isPresent());
        assertEquals("/new", result.get().get(0).getPath());
    }

    @Test
    void clearIfCurrentOwnerDoesNothingWhenChainWasNeverRegistered() {
        assertDoesNotThrow(() -> registry.clearIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A));
    }

    private DeploymentRouteUpdate route(String path) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(RouteType.EXTERNAL_TRIGGER)
                .build();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl engine -am test -Dtest=ChainRouteRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure — `ChainRouteRegistry` doesn't exist yet.

- [ ] **Step 3: Create `ChainRouteRegistry`**

Create `engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java`:

```java
package org.qubership.integration.platform.engine.controlplane;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChainRouteRegistry {
    private final Map<String, Registration> registrationsByChainId = new ConcurrentHashMap<>();

    public void register(String chainId, String deploymentId, List<DeploymentRouteUpdate> routes) {
        registrationsByChainId.put(chainId, new Registration(deploymentId, routes));
    }

    public Optional<List<DeploymentRouteUpdate>> getIfCurrentOwner(String chainId, String deploymentId) {
        Registration current = registrationsByChainId.get(chainId);
        return (current != null && current.deploymentId().equals(deploymentId))
                ? Optional.of(current.routes())
                : Optional.empty();
    }

    public void clearIfCurrentOwner(String chainId, String deploymentId) {
        registrationsByChainId.computeIfPresent(chainId,
                (id, reg) -> reg.deploymentId().equals(deploymentId) ? null : reg);
    }

    private record Registration(String deploymentId, List<DeploymentRouteUpdate> routes) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl engine -am test -Dtest=ChainRouteRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistry.java engine/src/test/java/org/qubership/integration/platform/engine/controlplane/ChainRouteRegistryTest.java
git commit -m "feat: add ChainRouteRegistry"
```

---

### Task 2: Wire `ChainRouteRegistry` into the register and stop actions

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java`

**Interfaces:**
- Consumes: `ChainRouteRegistry` (Task 1) — `register`, `getIfCurrentOwner`, `clearIfCurrentOwner`, exactly as constructed above.
- Produces: no new public interfaces — both classes still implement `DeploymentProcessingAction` with unchanged signatures; only their constructors (new `ChainRouteRegistry` parameter) and bodies change.

- [ ] **Step 1: Write the failing tests**

Create `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java`:

```java
package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterRoutesInControlPlaneActionTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DEPLOYMENT_ID = "deployment-1";
    private static final String DEPLOYMENT_NAME = "engine-service";

    private VariablesService variablesService;
    private ControlPlaneService controlPlaneService;
    private ChainRouteRegistry chainRouteRegistry;
    private RegisterRoutesInControlPlaneAction action;

    @BeforeEach
    void setUp() {
        variablesService = mock(VariablesService.class);
        controlPlaneService = mock(ControlPlaneService.class);
        ApplicationAutoConfiguration applicationConfiguration = mock(ApplicationAutoConfiguration.class);
        when(applicationConfiguration.getDeploymentName()).thenReturn(DEPLOYMENT_NAME);
        chainRouteRegistry = new ChainRouteRegistry();
        action = new RegisterRoutesInControlPlaneAction(
                variablesService, controlPlaneService, applicationConfiguration, chainRouteRegistry);
    }

    @Test
    void registersGatewayTriggerRoutesInTheChainRouteRegistryAfterPosting() {
        DeploymentRouteUpdate publicRoute = route("/public", RouteType.EXTERNAL_TRIGGER);
        DeploymentRouteUpdate privateRoute = route("/private", RouteType.PRIVATE_TRIGGER);
        DeploymentConfiguration configuration = configuration(publicRoute, privateRoute);

        action.execute(null, deploymentInfo(), configuration);

        Optional<List<DeploymentRouteUpdate>> registered =
                chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID);
        assertTrue(registered.isPresent());
        assertEquals(List.of("/public", "/private"), registered.get().stream()
                .map(DeploymentRouteUpdate::getPath).toList());
    }

    @Test
    void registersAnEmptyListWhenThereAreNoGatewayTriggerRoutes() {
        DeploymentRouteUpdate internalRoute = route("/internal", RouteType.INTERNAL_TRIGGER);
        DeploymentConfiguration configuration = configuration(internalRoute);

        action.execute(null, deploymentInfo(), configuration);

        Optional<List<DeploymentRouteUpdate>> registered =
                chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID);
        assertTrue(registered.isPresent());
        assertTrue(registered.get().isEmpty());
    }

    @Test
    void postsPublicAndPrivateRoutesToTheirRespectiveTiers() {
        DeploymentRouteUpdate publicRoute = route("/public", RouteType.EXTERNAL_TRIGGER);
        DeploymentRouteUpdate privateRoute = route("/private", RouteType.PRIVATE_TRIGGER);
        DeploymentConfiguration configuration = configuration(publicRoute, privateRoute);

        action.execute(null, deploymentInfo(), configuration);

        verify(controlPlaneService).postPublicEngineRoutes(eq(List.of(publicRoute)), eq(DEPLOYMENT_NAME));
        verify(controlPlaneService).postPrivateEngineRoutes(eq(List.of(privateRoute)), eq(DEPLOYMENT_NAME));
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder()
                .deploymentId(DEPLOYMENT_ID)
                .chainId(CHAIN_ID)
                .build();
    }

    private DeploymentConfiguration configuration(DeploymentRouteUpdate... routes) {
        return DeploymentConfiguration.builder()
                .routes(List.of(routes))
                .build();
    }

    private DeploymentRouteUpdate route(String path, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(path)
                .type(type)
                .build();
    }
}
```

Create `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java`:

```java
package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before.RegisterRoutesInControlPlaneAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void removesRoutesRegisteredByTheSameDeployment() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);
    }

    @Test
    void clearsTheRegistryEntryAfterASuccessfulRemoval() {
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/chain-1")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isEmpty());
    }

    @Test
    void doesNothingWhenNothingWasEverRegisteredForTheChain() {
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
    }

    @Test
    void doesNothingWhenANewerDeploymentHasAlreadySupersededThisOne() {
        // Simulates a redeploy: deployment A's routes get overwritten by deployment B's
        // registration before A's stop action runs (register-then-stop ordering).
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, List.of(route("/old")));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_B, List.of(route("/new")));

        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_B).isPresent());
    }

    @Test
    void leavesTheRegistryEntryInPlaceWhenRemovalFailsSoARetryCanFindItAgain() {
        List<DeploymentRouteUpdate> routes = List.of(route("/chain-1"));
        chainRouteRegistry.register(CHAIN_ID, DEPLOYMENT_ID_A, routes);
        doThrow(new ControlPlaneException("boom"))
                .when(controlPlaneService).removeEngineRoutes(routes, DEPLOYMENT_NAME);

        assertThrows(DeploymentRetriableException.class, () ->
                removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null));

        assertTrue(chainRouteRegistry.getIfCurrentOwner(CHAIN_ID, DEPLOYMENT_ID_A).isPresent());
    }

    @Test
    void endToEndRedeployOrderingDoesNotDeleteTheNewDeploymentsJustRegisteredRoutes() {
        // Reproduces the real IntegrationRuntimeService.update() ordering: the new
        // deployment registers first (via the real RegisterRoutesInControlPlaneAction,
        // sharing this same registry), then the old deployment's stop action runs.
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

        // Now the old deployment A's stop runs, as IntegrationRuntimeService does after
        // the new context has already started.
        removeAction.execute(null, deploymentInfo(DEPLOYMENT_ID_A), null);

        verify(controlPlaneService, never()).removeEngineRoutes(any(), any());
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

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl engine -am test -Dtest=RegisterRoutesInControlPlaneActionTest,RemoveRoutesFromControlPlaneActionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure — neither action has a `ChainRouteRegistry`-accepting constructor yet.

- [ ] **Step 3: Modify `RegisterRoutesInControlPlaneAction`**

In `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java`, add the import after the existing `org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;` import:

```java
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
```

Replace the field declarations and constructor:

```java
    private final VariablesService variablesService;
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;

    @Autowired
    public RegisterRoutesInControlPlaneAction(
        VariablesService variablesService,
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration
    ) {
        this.variablesService = variablesService;
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
    }
```

with:

```java
    private final VariablesService variablesService;
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;
    private final ChainRouteRegistry chainRouteRegistry;

    @Autowired
    public RegisterRoutesInControlPlaneAction(
        VariablesService variablesService,
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration,
        ChainRouteRegistry chainRouteRegistry
    ) {
        this.variablesService = variablesService;
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
        this.chainRouteRegistry = chainRouteRegistry;
    }
```

Replace:

```java
            controlPlaneService.postPrivateEngineRoutes(
                gatewayTriggersRoutes.stream()
                    .filter(route -> RouteType.isPrivateTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getDeploymentName());

            // Purge each route from the gateway tier it no longer belongs to (visibility
```

with:

```java
            controlPlaneService.postPrivateEngineRoutes(
                gatewayTriggersRoutes.stream()
                    .filter(route -> RouteType.isPrivateTriggerRoute(route.getType())).toList(),
                applicationConfiguration.getDeploymentName());

            chainRouteRegistry.register(
                deploymentInfo.getChainId(), deploymentInfo.getDeploymentId(), gatewayTriggersRoutes);

            // Purge each route from the gateway tier it no longer belongs to (visibility
```

- [ ] **Step 4: Modify `RemoveRoutesFromControlPlaneAction`**

Replace the entire contents of `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java` with:

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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.stop;

import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.configuration.ApplicationAutoConfiguration;
import org.qubership.integration.platform.engine.controlplane.ChainRouteRegistry;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnStopDeploymentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@OnStopDeploymentContext
public class RemoveRoutesFromControlPlaneAction implements DeploymentProcessingAction {
    private final ControlPlaneService controlPlaneService;
    private final ApplicationAutoConfiguration applicationConfiguration;
    private final ChainRouteRegistry chainRouteRegistry;

    @Autowired
    public RemoveRoutesFromControlPlaneAction(
        ControlPlaneService controlPlaneService,
        ApplicationAutoConfiguration applicationConfiguration,
        ChainRouteRegistry chainRouteRegistry
    ) {
        this.controlPlaneService = controlPlaneService;
        this.applicationConfiguration = applicationConfiguration;
        this.chainRouteRegistry = chainRouteRegistry;
    }

    @Override
    public void execute(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        Optional<List<DeploymentRouteUpdate>> routes = chainRouteRegistry.getIfCurrentOwner(
            deploymentInfo.getChainId(), deploymentInfo.getDeploymentId());
        if (routes.isEmpty()) {
            return;
        }
        try {
            controlPlaneService.removeEngineRoutes(routes.get(), applicationConfiguration.getDeploymentName());
            chainRouteRegistry.clearIfCurrentOwner(deploymentInfo.getChainId(), deploymentInfo.getDeploymentId());
        } catch (ControlPlaneException e) {
            throw new DeploymentRetriableException(e);
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl engine -am test -Dtest=RegisterRoutesInControlPlaneActionTest,RemoveRoutesFromControlPlaneActionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 10, Failures: 0, Errors: 0` (3 in `RegisterRoutesInControlPlaneActionTest` + 7 in `RemoveRoutesFromControlPlaneActionTest`).

- [ ] **Step 6: Run the full engine test suite and checkstyle to confirm nothing else broke**

Run: `mvn -pl engine -am test`
Expected: `BUILD SUCCESS`, 0 Checkstyle violations, all tests pass (including `ChainRouteRegistryTest` from Task 1 and the full pre-existing suite).

- [ ] **Step 7: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneAction.java engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/stop/RemoveRoutesFromControlPlaneActionTest.java
git commit -m "fix: use ChainRouteRegistry so route cleanup works on stop and survives redeploy ordering"
```
