# Fail-closed HTTPRoute cleanup for unresolvable snapshot ownership — design

## Context

`MicroDomainService.deleteChainSnapshotHttpRoutes` strips a removed snapshot's gateway paths
from the micro-domain's three shared tier HTTPRoutes (public, private, egress). Each tier is
one CR per domain, shared by every snapshot that domain hosts, so two snapshots can
legitimately claim the same path: a chain redeployed under a new snapshot ID before the
superseded one is removed, or two chains reaching the same external system through the same
egress prefix. The strip set is therefore the removed snapshot's paths minus every remaining
snapshot's paths.

The remaining snapshot IDs come from the integrations-configuration ConfigMap's source list
and are resolved to routes through `SnapshotRepository.findAllByIdIn`. When an ID has no
catalog row, `snapshotRoutes` logs a warning and returns whatever it did resolve, and the
caller strips anyway. A path owned only by an unresolved snapshot is invisible to the
subtraction, so the rule serving it is removed while a live chain still needs it. Review of
PR 670 reported this as P2.

Exposure concentrates in the egress tier. `egressOwnPaths` keys on `gatewayPrefix`, which two
chains calling the same external system share by construction, so collisions there are the
normal case rather than an anomaly. A public or private collision requires two chains
configured with the same trigger path.

The same warning fires at the other call site, where `snapshotRoutes` resolves the *removed*
snapshot. There an unresolved ID yields no own paths at all, `deleteChainSnapshotHttpRoutes`
returns early, and rules leak instead of being stripped — the opposite of what the message
describes.

Nothing reconciles these HTTPRoutes after the fact. Whatever cleanup skips stays in the
cluster until the domain is deleted through `deleteHttpRoutes` or the owning chain is
redeployed.

## Goals

- Never strip a rule when the set of snapshots that might still own it cannot be fully
  determined.
- Leave normal cleanup unchanged, including the ordinary case where the domain's last chain
  is being removed and there are legitimately no remaining snapshots.
- Make each warning describe the outcome that actually occurred.

## Non-goals

- Recording ownership on the HTTPRoute itself. That is the structural fix, specified
  separately in `2026-08-24-httproute-rule-ownership-annotation-design.md`, and it is what
  eventually removes the catalog-database join this design merely guards.
- `rules[].name`. GEP-995 named route rules reached the Experimental channel in Gateway API
  v1.2.0 and have not graduated to Standard, and `infrastructure/qip-dev/README.md:10`
  installs `standard-install.yaml`. A `name` written under those CRDs is pruned silently, so
  cleanup would read back no owners at all and report no error.
- Retrying until ownership resolves. An unresolved ID is not a transient fault: the query
  succeeded and returned fewer rows, which means the ConfigMap names a snapshot the catalog
  no longer has. Retrying re-runs the same query against the same state indefinitely.
- The build-then-deploy lost-update race that affects these same CRs.
- Engine-side route removal in `IstioRoutesRegistrationService`.

## Design

### 1. Three resolution states, not two

The current code treats "no remaining snapshots" and "remaining snapshots that did not
resolve" as the same input. They are opposite situations and get opposite handling.

| State | Today | After |
|---|---|---|
| `remainingSnapshotIds` is empty | Strip everything the snapshot owns | Unchanged — correct, not degraded |
| A remaining ID has no catalog row | Strip anyway, warn | Skip all three tiers, warn |
| The removed snapshot has no catalog row | Return early, warn with inverted text | Skip, warn with accurate text |
| The domain has no integrations-configuration ConfigMap | Strip everything the snapshot owns | Skip all three tiers, warn |

The empty case must stay fail-open. It is the ordinary path when the domain's last chain is
removed, and treating it as unresolvable would stop cleanup from ever running.

### 2. `snapshotRoutes` reports completeness instead of logging it

Resolution failure becomes a value the caller can act on rather than a side effect it cannot
see:

```java
private record ResolvedRoutes(List<Route> routes, List<String> unresolvedIds) {
    boolean isComplete() {
        return unresolvedIds.isEmpty();
    }
}

private ResolvedRoutes snapshotRoutes(Collection<String> snapshotIds)
```

Both the `description` and `domainName` parameters go away with the log statement they served.
Each call site now writes its own message, which is what makes the two outcomes
distinguishable.

An empty `snapshotIds` returns `new ResolvedRoutes(List.of(), List.of())` — complete, with
nothing to resolve. That is what keeps the last-chain case fail-open.

### 3. The decision moves into `deleteChainSnapshotHttpRoutes`

```java
void deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds) {
    ResolvedRoutes own = snapshotRoutes(List.of(snapshotId));
    ResolvedRoutes retained = snapshotRoutes(remainingSnapshotIds);
    if (!retained.isComplete()) {
        log.warn(...);
        return;
    }
    if (!own.isComplete()) {
        log.warn(...);
        return;
    }
    // unchanged from here: unsharedPaths per tier, then stripPathsFromTier
}
```

Both guards skip the whole domain rather than a single tier. An unresolved snapshot's routes
are precisely what cannot be seen, so there is no way to attribute it to one tier and spare
the others.

Resolution order stays as it is today, removed snapshot first and remaining second, because
`MicroDomainServiceHttpRouteTest.deleteChainSnapshotStripsOnlyThePathsNoRemainingSnapshotOwns`
stubs `getRoutes` with consecutive returns and would silently assert the wrong thing if the
two calls swapped. Only the *checks* are ordered deliberately: the retained set is tested
first, so that when both are incomplete the message describing the dangerous condition is the
one an operator sees.

`snapshotRoutes` must keep the existing size-based short-circuit and compute `unresolvedIds`
only when `snapshots.size() < snapshotIds.size()`. Computing them unconditionally would call
`AbstractEntity::getId` on every returned row, and the suite's bare `mock(Snapshot.class)`
stubs return `null` there, so every currently passing test would start reporting its own
snapshot as unresolved and fail closed.

### 4. A missing ConfigMap is unresolvable, not empty

`remainingSnapshotIds` currently returns an empty set both when the ConfigMap lists no other
sources and when there is no ConfigMap at all, and its javadoc acknowledges that the second
case falls back to "the behavior from before the subtraction existed". That is the same
defect in a different disguise.

`deleteChainSnapshot` already runs inside `getMainIntegrationResources(name).ifPresent(...)`,
so the Integration exists whenever this code runs. A domain with an Integration but no
integrations-configuration ConfigMap is drift, not a normal state.

`remainingSnapshotIds` therefore returns `Optional<Set<String>>`: empty when
`resources.integrationsConfiguration()` is null, and `Optional.of(ids)` otherwise, where
`ids` may itself be empty. `deleteChainSnapshot` logs and skips the HTTPRoute cleanup on
`Optional.empty()`.

Keeping the distinction at the read site leaves `deleteChainSnapshotHttpRoutes`'s signature
alone, so its thirteen existing test call sites continue to compile. The ConfigMap is read
before it is rewritten, exactly as today, and the ordering constraint documented on
`remainingSnapshotIds` is unaffected.

### 5. Messages

Three messages replace the one shared, partly inaccurate warning. Each states why ownership
is unknown, what was done about it, and how to clear what was left behind. All three log at
`warn`: once the code skips deliberately, this is a condition the program handles, and the
repository style guide reserves `error` for one it does not.

```java
// Remaining snapshots did not resolve.
log.warn("Snapshot(s) {} listed for micro-domain '{}' have no catalog row, so the paths they own "
        + "are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping one a "
        + "live chain still serves. Redeploy the domain to clear the leftovers.",
        retained.unresolvedIds(), name, snapshotId);

// The removed snapshot did not resolve.
log.warn("Removed snapshot '{}' has no catalog row for micro-domain '{}', so the paths it owns are "
        + "unknown. Its HTTPRoute rules stay in place. Redeploy the domain to clear them.",
        snapshotId, name);

// The domain has no integrations-configuration ConfigMap.
log.warn("Micro-domain '{}' has no integrations-configuration ConfigMap, so the snapshots it still "
        + "hosts are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping "
        + "one a live chain still serves. Redeploy the domain to clear the leftovers.",
        name, snapshotId);
```

### 6. Accepted consequence

Every guard trades a stale rule for a live one. A skipped cleanup leaves rules that route to
a backend no longer serving those paths, so requests to them fail, but they are requests to a
chain the operator has already removed. Because nothing reconciles these CRs, the leftovers
persist until the domain is deleted or the owning chain is redeployed, and the messages say
so. The structural follow-up removes the database dependency that produces this state in the
first place.

## Testing

`MicroDomainServiceHttpRouteTest`:

1. A remaining ID that `findAllByIdIn` does not return: no `createOrUpdateResource` and no
   `deleteCustomObject` on any of the three tiers.
2. A partially resolved remaining set — two IDs requested, one returned: same assertions.
   Proves the guard keys on completeness, not on emptiness.
3. The removed snapshot unresolved: the tiers are untouched.
4. Regression, and the point of the exercise: the existing all-resolve and empty-remaining
   tests pass unchanged, showing the normal path did not become fail-closed.

`MicroDomainServiceTest`, which already exercises `deleteChainSnapshot`:

5. `resources.integrationsConfiguration()` null: the ConfigMap rewrite is skipped as it is
   today, and no tier is written or deleted.
