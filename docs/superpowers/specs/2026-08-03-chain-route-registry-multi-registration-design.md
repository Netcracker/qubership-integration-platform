# Multi-registration `ChainRouteRegistry` and non-retriable route-removal failures

## Context

This spec amends `docs/superpowers/specs/2026-08-03-chain-route-registry-design.md` (already implemented,
commits `6e69f0bd..665dff30`). That design fixed two Critical bugs (C1: NPE on every real stop path; C2:
a redeploy's old-deployment cleanup deleting the new deployment's just-registered routes) with a
single-owner `ChainRouteRegistry` (`chainId → (deploymentId, routes)`).

The re-run whole-branch final review, dispatched after that fix landed, found the single-owner model closes
C2 but not its mirror image:

- **C-1 — a redeploy whose new context fails to start deletes the still-running old deployment's routes.**
  Traced in `IntegrationRuntimeService.update()`: the new deployment (B) registers and is recorded as the
  chain's owner (`:423`) before `startContext` runs (`:450`). If `startContext` throws, the catch block
  (`:449-455`) calls `processStopContext` for **B** (the failing deployment) with a real, non-null
  configuration — the one call site where that's true. B genuinely *is* the registry's current owner at
  that point, so the single-owner ownership check correctly lets B's removal through. But B's routes are
  the same paths as the still-running old deployment A (a chain's trigger paths don't change across its own
  redeployments), and since `update()` then `throw`s, line `:457` (which would stop A) never runs. Net
  effect: A keeps running but loses its gateway routes — a silent, non-self-healing loss of ingress.

- **I-1 — a `DeploymentRetriableException` from a failed removal on undeploy gets retried as an update of
  the chain that was just being torn down.** Traced `retryProcessingDeploys()`: it drains the retry queue
  into `DeploymentsUpdate.builder().update(toRetry)`, and `processAndUpdateState` only ever dispatches the
  `update` collection as `DeploymentOperation.UPDATE` — there is no path that preserves or re-dispatches as
  `STOP`. This gap pre-dates this branch but was unreachable before, since no stop-time action previously
  threw `DeploymentRetriableException`.

## Goals

- A redeploy whose new deployment fails to start does not remove the still-running old deployment's routes.
- A route-removal failure on stop does not get silently mis-retried as an update of the deployment being
  torn down.
- Both fixes generalize cleanly rather than special-casing the start-failure path — the design should not
  need to know *why* a deployment's teardown is happening, only whether some other currently-registered
  deployment for the same chain still needs the same paths.

## Design

### 1. `ChainRouteRegistry`: single owner → multiple simultaneous registrations, path-level sharing check

The single-owner model (`chainId → (deploymentId, routes)`) assumes at most one deployment is ever
"active" for a chain at a time. C-1 shows that's false during the transition window of both a normal
redeploy and a failed one: the new deployment registers *before* the old one is stopped, so for a real
window, two deployments for the same chain are simultaneously registered.

The registry becomes `chainId → {deploymentId → routes}`. Two new methods replace `getIfCurrentOwner`/
`clearIfCurrentOwner`:

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

`register`'s call site and signature are unchanged — `RegisterRoutesInControlPlaneAction` doesn't change at
all in this revision. `getUnsharedRoutes` returns exactly this deployment's registered routes whose *path*
no other currently-registered deployment for the same chain also claims — the routes actually safe to
remove from the control plane. `unregister` removes this deployment's own entry regardless of what else is
registered; if that empties the chain's inner map, the chain's outer entry is removed too (bounded memory,
no permanent per-chain footprint once every deployment for it has unregistered).

Both inner and outer maps are `ConcurrentHashMap` — same "no new locking" rationale as the original design:
the existing per-chain lock in `IntegrationRuntimeService` already serializes a chain's own
register/stop sequence on one thread, so this class needs no synchronization of its own, only safe
concurrent structures for *different* chains.

### 2. Why this closes both C2 and C-1 with one rule

Tracing all three relevant scenarios against `getUnsharedRoutes`:

- **Genuine single undeploy** (only A ever registered for the chain): `pathsClaimedByOthers` is empty (no
  other registration exists), so all of A's routes are returned and removed. Unchanged from today.
- **C2 — redeploy, old A's stop runs after new B already registered the same path**: at that point
  `registrationsByChainId[chain] = {A: [...], B: [...]}`. `getUnsharedRoutes(chain, A)` excludes A's own
  entry when computing `pathsClaimedByOthers`, finds B claims the same path, and excludes it — nothing gets
  removed. A's own entry is still `unregister`'d, leaving `{B: [...]}`. B's routes are untouched.
- **C-1 — B's own start failure, A still running**: same registry state as C2's window
  (`{A: [...], B: [...]}`), just the *other* deployment's stop runs. `getUnsharedRoutes(chain, B)` excludes
  B's own entry, finds A claims the same path, and excludes it — nothing gets removed. B's own entry is
  `unregister`'d, leaving `{A: [...]}`. A's routes are untouched.

Both are the same computation from opposite sides — the design doesn't need to know *which* deployment is
"new" or "old," or *why* a given deployment's stop is running; it only needs "is this path still claimed by
someone else registered for this chain."

As a side effect, this also correctly handles a case neither the original design nor the single-owner model
addressed: a redeploy where a trigger is *deleted* from the chain (not moved to another tier). The dropped
path appears in A's registration but not B's, so it's never in `pathsClaimedByOthers` — it gets removed
when A's stop runs, exactly as it should. (This case wasn't previously broken by this branch — the
single-owner model already handled it as a special case of "no newer registration claims this path" — but
the multi-registration model handles it as the same general rule rather than a special case.)

### 3. `RemoveRoutesFromControlPlaneAction`: consume the new API, stop throwing a retriable exception

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

`unregister` still runs only after a needed removal succeeds (or is skipped entirely when
`routesToRemove` is empty, since there's nothing that can fail) — preserving the original design's
retry-safety intent: on failure, this deployment's registration entry stays in place, so recomputing
`getUnsharedRoutes` later (whether via a future manual retry or a subsequent redeploy of the same chain)
reflects the still-accurate current state rather than a stale snapshot.

### 4. `RouteRegistrationException`: fail the deployment instead of mis-retrying it

New class in `org.qubership.integration.platform.engine.errorhandling`, mirroring the existing
`DeploymentRetriableException`'s constructor set exactly:

```java
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

`RemoveRoutesFromControlPlaneAction` throws this instead of `DeploymentRetriableException`. In
`IntegrationRuntimeService.processDeploymentUpdate`'s catch chain
(`catch (KubeApiException) → catch (DeploymentRetriableException) → catch (Throwable)`), a
`RouteRegistrationException` matches neither of the first two, so it falls to `catch (Throwable e)`: status
stays `FAILED` (not `PROCESSING`), `putInRetryQueue` is never called, and `chainErrorCode` is set to
`UNEXPECTED_DEPLOYMENT_ERROR`. The deployment is visibly marked failed in the engine's own state rather
than being silently re-dispatched as an update of the chain that was just being torn down — this closes
I-1 without touching the shared retry-queue machinery (`retryProcessingDeploys`,
`DeploymentsUpdate`), which is out of scope for this branch.

## Non-goals

- Fixing the underlying retry-queue gap (`retryProcessingDeploys` losing the original operation type) —
  pre-existing, out of scope; `RouteRegistrationException` sidesteps it for this one call site rather than
  fixing it generally.
- Any change to `ControlPlaneService`'s contract, either implementation, or `micro-engine`.
- `RegisterRoutesInControlPlaneAction` — unchanged; `register`'s signature and call site are identical to
  the original design.

## Testing

- `ChainRouteRegistryTest` gets rewritten for the new API: `getUnsharedRoutes` returns all routes when no
  other deployment is registered for the chain; returns only the routes whose paths aren't claimed by
  another registered deployment when one exists; returns an empty list for a deployment that never
  registered; `unregister` removes only the named deployment's entry, leaving others intact, and cleans up
  the chain's outer entry once empty (but not before).
- `RemoveRoutesFromControlPlaneActionTest` gets new/updated cases: the C2 and C-1 scenarios directly (two
  registrations sharing a path, removal from either side is a no-op for the shared path but still
  unregisters the caller), a case with a genuinely unshared path (gets removed), and a failed removal
  throwing `RouteRegistrationException` (not `DeploymentRetriableException`) while leaving the registry
  entry in place.
