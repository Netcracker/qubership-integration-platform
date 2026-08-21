# Chain-scoped route registry for stop-time cleanup

## Context

`engine`'s route lifecycle has two `DeploymentProcessingAction`s: `RegisterRoutesInControlPlaneAction`
(`@OnBeforeDeploymentContextCreated`) posts a deployment's routes to `ControlPlaneService`, and
`RemoveRoutesFromControlPlaneAction` (`@OnStopDeploymentContext`) is meant to purge them on stop.

The final whole-branch review of the `IstioRoutesRegistrationService` work found two Critical bugs in
`RemoveRoutesFromControlPlaneAction`, both verified directly against `IntegrationRuntimeService.java`:

- **C1 — NPE on every real stop path.** `RemoveRoutesFromControlPlaneAction.execute()` calls
  `deploymentConfiguration.getRoutes()` with no null check. `IntegrationRuntimeService.stopDeploymentContext()`
  (`:713-714`) always passes `null` for `deploymentConfiguration` — that's the method actually used for
  undeploy (`stop()`, `:709`), a redeploy's old-context stop (`update()`, `:457`), and `removeOldDeployments`'
  cleanup (`:533`). Only the start-failure path (`:453`) passes a real configuration. Since
  `DeploymentProcessingService.executeActions` has no try/catch, this NPE propagates out of
  `stopDeploymentContext` uncaught — on redeploy specifically, it fires *after* the new context already
  started (`:450`), so the old context's `.stop()` (`:720`) never runs: both contexts keep running and
  double-process messages, the deployment is reported `FAILED` even though the new context is fine, and the
  failure-path cleanup predicate (`FAILED`/`PROCESSING` only) doesn't match the old `DEPLOYED`-status
  deployment, so its cache entry leaks too. This is a regression introduced when
  `RemoveRoutesFromControlPlaneAction` was added — not a pre-existing bug.
- **C2 — redeploy ordering is register-then-stop, not stop-then-register.** Tracing `update()`:
  `:423` registers the new deployment's routes, `:450` starts the new context, `:457` stops the old
  context for the same chain — after the new one is already live. A chain's trigger paths don't change
  across its own redeployments, so if C1 is naively patched (e.g. skip on null config) without addressing
  this, the old deployment's stop-time cleanup would delete the exact paths the new deployment just
  registered moments earlier. This directly contradicts an earlier assumption (that stop always precedes
  register on redeploy) that the original `RemoveRoutesFromControlPlaneAction` design relied on.

This spec redesigns how `RemoveRoutesFromControlPlaneAction` gets its route data, closing both.

## Goals

- `RemoveRoutesFromControlPlaneAction` works correctly on all three real stop paths (undeploy, redeploy's
  old-context stop, `removeOldDeployments` cleanup), none of which provide a non-null
  `DeploymentConfiguration`.
- A redeploy's old-deployment cleanup never removes routes that the new deployment already re-registered.
- Failed removal attempts are retried correctly (the framework's existing `DeploymentRetriableException` →
  retry-queue mechanism must still work).
- No change to `ControlPlaneService`'s contract or either implementation (`ControlPlaneDefaultService`,
  `IstioRoutesRegistrationService`) — this is purely an orchestration-layer fix.

## Non-goals

- `micro-engine`. Its equivalent (`RouteUnregisterAction`) is Camel-event-driven with a guaranteed non-null
  `context` at removal time (it reads routes back via `MetadataUtil.getRouteRegistrationInfo`), so it
  doesn't have C1's problem. Whether it has a C2-equivalent ordering issue is a separate, unverified
  question, out of scope here.
- `RegisterRoutesInControlPlaneAction`'s existing cross-tier-flip `removeEngineRoutes` call (the one that
  purges stale cross-tier registrations when a route's visibility type changes within the same deployment).
  That call already has `deploymentConfiguration` directly and needs no changes.

## Design

### 1. Approaches considered

- **A — Chain-scoped ownership registry (chosen).** A new component, `ChainRouteRegistry`, keyed by
  `chainId`, stores the *current* `(deploymentId, routes)` for that chain. Register overwrites; stop only
  removes if it's still the recorded owner. Uses only `DeploymentInfo` (always non-null, provides both
  `getChainId()` and `getDeploymentId()`), so it doesn't depend on `deploymentConfiguration` or `context`
  being non-null. The ownership check closes C2 directly: a redeploy's new registration overwrites the
  chain's entry before the old deployment's stop runs, so the old deployment sees it's no longer the owner
  and skips.
- **B — Plain per-deploymentId registry, no ownership check.** Rejected. Fixes C1 (no `deploymentConfiguration`
  dependency) but not C2 — on redeploy, the old deployment would still find and remove its own tracked
  routes, which are the exact paths the new deployment just wrote.
- **C — Push ownership-awareness into `ControlPlaneService` implementations.** Rejected. Core Mesh's
  control plane has no concept of "which deployment owns this path" at all (matches by path + hostRewrite
  only), so this would need duplicate bookkeeping in every implementation for something that's fundamentally
  about engine's own deployment-lifecycle identity. C2 isn't Istio-specific either — Core Mesh's
  path-matched UUID lookup has the identical redeploy-clobber exposure today. Fixing it once at the
  orchestration layer, where chain/deployment identity actually lives, is the right altitude and benefits
  both implementations symmetrically.

### 2. `ChainRouteRegistry`

New component in `org.qubership.integration.platform.engine.controlplane` (alongside `ControlPlaneService`/
`ControlPlaneException` — an implementation-agnostic orchestration helper, not tied to either
`ControlPlaneService` implementation, so it belongs with the interface-level concepts rather than
`cloudcore.controlplane`'s implementations):

```java
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

    private record Registration(String deploymentId, List<DeploymentRouteUpdate> routes) {}
}
```

Keyed by `chainId` — the "current owner" concept — not `deploymentId`; a deployment only ever appears as a
*value*, checked against on read/clear.

### 3. Get vs. take — retry correctness

`getIfCurrentOwner` (read) and `clearIfCurrentOwner` (only called after a successful removal) are separate
calls, not one pop-and-return. Popping the entry before calling `removeEngineRoutes` would break retries: if
`removeEngineRoutes` fails and throws `DeploymentRetriableException`, the whole stop operation is requeued
and retried later via the same mechanism used for register failures (`processDeploymentUpdate`'s
`catch (DeploymentRetriableException e) { ...; putInRetryQueue(deployment); }`) — but if the entry were
already gone, the retry would find nothing and silently skip the removal it's supposed to retry. Clearing
only on success leaves a failed attempt's entry in place for the retry to find again.

### 4. `RegisterRoutesInControlPlaneAction` changes

Add `ChainRouteRegistry` as a constructor dependency. Right after the existing `postPublicEngineRoutes`/
`postPrivateEngineRoutes` calls succeed (before the unrelated, unchanged cross-tier-flip `removeEngineRoutes`
cleanup call):

```java
chainRouteRegistry.register(deploymentInfo.getChainId(), deploymentInfo.getDeploymentId(), gatewayTriggersRoutes);
```

Registering only after the posts succeed means the registry always reflects routes the control plane
actually accepted, not routes merely attempted.

### 5. `RemoveRoutesFromControlPlaneAction` changes

Drops its `deploymentConfiguration` dependency entirely (the parameter stays, since it's part of the
`DeploymentProcessingAction` interface contract, but is no longer referenced — the same pattern already
used by the pre-existing `McpToolUnregisterAction`, which also ignores `deploymentConfiguration`):

```java
@Override
public void execute(SpringCamelContext context, DeploymentInfo deploymentInfo, DeploymentConfiguration deploymentConfiguration) {
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
```

No more `deploymentConfiguration.getRoutes()` — this closes C1. Since a redeploy's new registration
overwrites the chain's registry entry before the old deployment's stop runs, `getIfCurrentOwner` returns
empty for the old deployment and it's a clean no-op — this closes C2. Also drops the now-redundant
`RouteType.triggerRouteWithGateway` filter, since the registry already stores the pre-filtered,
path-normalized list that was actually posted.

### 6. Locking — keep relying on the existing per-chain lock; no new locking in `ChainRouteRegistry`

`IntegrationRuntimeService.process()` acquires a per-chain lock (`getCache().getLockForChain(chainId)`)
around each deployment's processing. Critically, for a redeploy specifically, the "register new" and "stop
old" steps happen synchronously within one `update()` call on one thread (`:423` register, `:457` stop-old)
— not as separate, independently-dispatched operations that could race. So there is no cross-thread race
between a chain's own register and stop steps to guard against; a `ConcurrentHashMap` is sufficient purely
for safety across *different* chains being processed concurrently by `deploymentExecutor`. `ChainRouteRegistry`
adds no locking of its own beyond the map's built-in per-key atomicity, and this reasoning — rely on the
existing chain lock, do not add new locking here — is a deliberate, retained part of this design, not an
open question.

### 7. Testing

Both actions currently have zero test coverage. This redesign is the point to add it:

- `ChainRouteRegistryTest`: register then `getIfCurrentOwner` returns the routes; `getIfCurrentOwner` for a
  non-owning deployment ID returns empty; `clearIfCurrentOwner` only clears when the deployment ID matches
  (a stale clear from an old deployment ID must not clear a newer registration); `register` overwrites a
  previous entry for the same chain (the redeploy case).
- `RegisterRoutesInControlPlaneActionTest` and `RemoveRoutesFromControlPlaneActionTest`, both using a real
  (not mocked) `ChainRouteRegistry` instance to prove the ownership hand-off works end-to-end: register then
  remove (same deployment) succeeds; register (deployment A) then remove (deployment B, simulating a
  superseded old deployment) is a no-op; a failed `removeEngineRoutes` leaves the registry entry intact for
  a subsequent retry to find.

## Scope check

Entirely orchestration-layer: `ChainRouteRegistry` (new), `RegisterRoutesInControlPlaneAction` (modified),
`RemoveRoutesFromControlPlaneAction` (modified), plus their new tests. `ControlPlaneService`'s contract and
both implementations (`ControlPlaneDefaultService`, `IstioRoutesRegistrationService`) are untouched, so none
of the already-reviewed Task 1-3 work reopens.
