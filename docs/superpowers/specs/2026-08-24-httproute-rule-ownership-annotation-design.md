# HTTPRoute rule ownership by annotation — follow-up design

**Status:** proposed, not scheduled. `2026-08-24-httproute-cleanup-fail-closed-design.md`
guards the symptom in the current branch; this removes the cause.

## Context

Cleanup in `MicroDomainService` decides which HTTPRoute rules a removed snapshot may take
with it by re-deriving, from the catalog database, the paths every *other* snapshot in the
domain owns. That join is wrong in two independent ways.

It depends on the database agreeing with the cluster. When the integrations-configuration
ConfigMap names a snapshot the catalog no longer has, ownership is unknowable. The fail-closed
design handles that by refusing to strip anything, which is safe but leaks rules, permanently,
because nothing reconciles these CRs.

It also re-computes what the builders already computed. `egressOwnPaths` runs routes through
`EgressServiceRouteFormatter` and `GatewayPathMatch.forPath(route.getGatewayPrefix())` to
reproduce the `gatewayPrefix` that `EgressRouteResourceBuilder` wrote to the cluster. Two
implementations of the same rule must stay in agreement forever, with no test that fails when
they drift. A divergence strips the wrong rules or none at all, silently.

Both problems come from the same root: the cluster object records *what* rules exist but not
*who* put them there, so ownership has to be reconstructed from somewhere else.

## Approach

Record ownership on the tier HTTPRoute, next to the rules it describes:

```yaml
metadata:
  annotations:
    qip.io/rule-owners: '{"PathPrefix:/qip/system-a1b2c3":["snap-1","snap-2"]}'
spec:
  rules:
    - matches:
        - path: {type: PathPrefix, value: /qip/system-a1b2c3}
      backendRefs: [...]
```

`HttpRouteResourceBuilder` and `EgressRouteResourceBuilder` maintain the map as they emit
rules, merging it the same way they already merge preserved rules from the build cache.
Cleanup removes the departing snapshot from every owner set, drops the rules whose set becomes
empty, keeps the rest, and writes rules and annotation back in one update.

Ownership is then read from the same object being mutated, in the same read, so it cannot
disagree with the rules and cannot go stale relative to the database.

### Why not `rules[].name`

It is the natural place for this and it is not available. GEP-995 named route rules reached
the Gateway API Experimental channel in v1.2.0 and have not graduated to Standard;
`infrastructure/qip-dev/README.md:10` installs `standard-install.yaml`. Custom resource
definitions prune unknown fields silently, so a written `name` disappears with no error and
cleanup reads back nothing. Revisit if the field graduates or the install moves to the
Experimental channel.

## What this deletes

`deleteChainSnapshotHttpRoutes` stops needing to know which paths the removed snapshot owned:
the annotation already says so. That removes `remainingSnapshotIds`, `snapshotRoutes`,
`unsharedPaths`, `tierOwnPaths`, `egressOwnPaths`, the `SnapshotRepository` and
`RoutesGetterService` constructor dependencies of `MicroDomainService`, and the ordering
constraint requiring `remainingSnapshotIds` to run before the ConfigMap is rewritten. The
fail-closed guards from the companion design collapse into a single rule.

## Unowned rules

A rule present in `rules[]` with no entry in the annotation has unknown ownership and is kept.
This is the fail-closed policy of the companion design, at per-rule rather than per-domain
granularity, and it covers rules written by anything that does not maintain the annotation.

That matters, because the engine is plausibly a second writer on these objects:
`HttpRouteEgressNamingStrategy:30` appends `-egress-routes` to the integration resource name,
and `IstioRoutesRegistrationService:478` appends the same suffix to `cloudServiceName`. Whether
those resolve to the same CR under micro-deploy is an open question below.

## Migration

Existing rules carry no annotation, so cleanup keeps them until their owning chain is
redeployed and the builder stamps ownership. Rules preserved from the live CR during an
unrelated build stay unowned, because the builder cannot know who owns a rule it did not
generate. The window therefore closes chain by chain rather than domain by domain, and stays
open indefinitely for a chain that is never redeployed.

This is the fail-closed direction throughout, so the migration window is a leak rather than a
hazard. Whether to accept it or to seed the annotation from the database once, at first write,
is an open question.

## Open questions

1. Do the engine and runtime-catalog write the same tier CRs under micro-deploy? If they do,
   either the engine maintains the annotation too, or its rules stay permanently unowned and
   are never cleaned up.
2. Key format. `GatewayPathMatch` holds a type and a value; the annotation needs a stable
   string form of that pair, and cleanup must produce byte-identical keys to the builders.
3. Size. Annotations on an object are capped at 256 KB in total. A domain with many rules and
   several owners each needs a measured bound, and a decision about what to do at the ceiling.
4. Whether the annotation should be seeded from the database on first write to close the
   migration window immediately, at the cost of keeping the join for one release.
5. Interaction with the build-then-deploy lost-update race on these CRs. The annotation is
   written in the same update as the rules, so it does not make the race worse, but a lost
   update now loses ownership data as well as rules.
