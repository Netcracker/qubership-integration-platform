# Chain-trigger HTTPRoute matching for placeholder paths — design

## Context

An HTTP Trigger's configured path can contain Camel/JAX-RS-style `{param}` placeholders
(e.g. `/orders/{id}/items`), scoped to a single path segment — confirmed against the
existing `PathParser`/`PathIntersectionChecker` utilities, duplicated in both
`runtime-catalog` and `engine`. Today, `HttpRouteResourceBuilder` (`runtime-catalog`) and
`IstioRoutesRegistrationService` (`engine`) both build every rule's `matches[0].path` as
`type: PathPrefix`, `value: <base-prefix><route path>` — a literal string. For a
placeholder path, that value still contains the literal `{id}` text, which never matches a
real request path, so the route is unreachable.

A prior, already-committed change aligned the external gateway-facing prefix with the
internal Camel servlet's real listening prefix in both `engine` and `micro-engine`
(`qip.camel.routes.prefix` / `qip.camel.routes-prefix`, with the legacy `/routes` prefix
kept as a second servlet mapping for backward compatibility). As a direct result, both
builders now emit their rules with **no** `URLRewrite` filter — the original request path
forwards to the backend unchanged, since external and internal prefixes match.

That alignment is what makes this design possible. Gateway API's `URLRewrite` filter with
`ReplacePrefixMatch` is explicitly documented as incompatible with a `RegularExpression`
match (the implementation must reject the route, per the `HTTPPathModifier` spec) — so
`RegularExpression` matching was not usable while a rewrite was still required. With no
filter needed at all, that restriction no longer applies, and `RegularExpression` becomes
usable purely for matching.

An Istio-native alternative (`VirtualService` with `uriRegexRewrite`, which can match and
rewrite with a single field) was evaluated and rejected: Istio's own ambient-mode docs
state that `VirtualService` support in ambient is Alpha, and that mixing it with Gateway
API configuration in the same mesh is unsupported and produces undefined behavior — a risk
this migration can't take on, since Gateway API resources are already used everywhere else
in it.

## Goals

- Correctly match and route chain-trigger paths that contain a `{param}` placeholder, in
  both `HttpRouteResourceBuilder` (`runtime-catalog`) and `IstioRoutesRegistrationService`
  (`engine`).
- As a side effect of the added precision, close the cross-chain path-collision risk
  previously accepted as a known follow-up (e.g. `/orders/{id}` and `/orders/{id}/items`
  no longer collapse onto the same prefix).
- Leave behavior for placeholder-free paths unchanged (`PathPrefix`, exactly as today).

## Non-goals

- `EngineRoutesResourceBuilder`'s checkpoint-sessions route. It rewrites a public,
  domain-scoped API path onto a different internal REST namespace — a real rewrite is
  still required there, so `RegularExpression` (forbidden alongside a rewrite filter)
  still doesn't apply. It keeps its existing truncated-`PathPrefix` + `ReplacePrefixMatch`
  handling, untouched by this change.
- Extending `DeploymentService`'s trigger-path conflict validation to catch overlapping
  placeholder paths at chain-deploy time. Separate, already-identified follow-up.
- Any change to CR naming, `parentRefs`, `backendRefs`, or template/CR structure. This
  design only changes how `matches[0].path` is computed.

## Design

### 1. Path-match algorithm

For a given route path:

```
if path contains a {placeholder} segment (matches \{[^{}/]+\}):
    type  = "RegularExpression"
    value = path with every {placeholder} replaced by [^/]+
else:
    type  = "PathPrefix"
    value = path                      # unchanged from today
```

No filter is emitted in either case — the aligned prefixes mean the original path already
forwards correctly. No explicit `^`/`$` anchors are added to the regex: Istio/Envoy's path
matching uses RE2 `FullMatch` semantics, so the generated pattern is implicitly required to
match the entire path already.

The substitution is a plain string replace, not a per-segment, regex-escaped rebuild. A
literal path segment that happens to contain a regex metacharacter (`.`, `(`, `)`, `+`,
etc. — legal in a URL path segment, though unusual in practice) is not escaped, and could
be misinterpreted as regex syntax or fail to compile. This is a deliberate, accepted
trade-off in favor of simplicity over defending against a rare, unlikely input.

### 2. Shared utility

A new `GatewayPathMatch` value type, implementing the algorithm above behind a single
factory method:

```java
public final class GatewayPathMatch {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}/]+\\}");

    private final String type;
    private final String value;

    public static GatewayPathMatch forPath(String path) {
        Matcher matcher = PLACEHOLDER.matcher(path);
        return matcher.find()
                ? new GatewayPathMatch("RegularExpression", matcher.replaceAll("[^/]+"))
                : new GatewayPathMatch("PathPrefix", path);
    }

    public String getType() { ... }
    public String getValue() { ... }

    @Override public boolean equals(Object o) { ... }  // by (type, value)
    @Override public int hashCode() { ... }
}
```

`equals`/`hashCode` are defined over `(type, value)` so instances can be used directly as
set elements (see [touched-path identity](#4-touched-path-identity) below).

Placed alongside `PathParser`/`PathIntersectionChecker` in each module's
`util.paths` package, and duplicated between `runtime-catalog` and `engine` the same way
those two utilities already are — this codebase's established pattern for this class of
low-level path helper, since the two modules don't share a common library dependency for
it.

### 3. Call sites

Five call sites need the new algorithm, matching the ones identified when this gap was
first scoped:

| Module | File | Call site | Change |
|---|---|---|---|
| `runtime-catalog` | `HttpRouteResourceBuilder` | `buildRule()` | Build `matches[0].path` from `GatewayPathMatch.forPath(path)` instead of always `PathPrefix` + raw `path`. |
| `runtime-catalog` | `HttpRouteResourceBuilder` | `preservedRulesFromCache()` | `touchedPaths` becomes a `Set<GatewayPathMatch>` (see below). |
| `runtime-catalog` | `CustomResourceService` | `tierOwnPaths()` | Same `Set<GatewayPathMatch>` treatment. |
| `engine` | `IstioRoutesRegistrationService` | `buildRule()` | Same as `HttpRouteResourceBuilder.buildRule()`. |
| `engine` | `IstioRoutesRegistrationService` | `attemptMergeTierRoutes()` | Same `Set<GatewayPathMatch>` treatment as `preservedRulesFromCache()`. |

### 4. Touched-path identity

Today, `touchedPaths` is a `Set<String>` of literal path values, compared against a
preserved rule's `matches[0].path.value` alone. With two possible match types now, identity
must be `(type, value)`, not `value` alone — cheap to get right, and closes an edge case
that matters here specifically: **a route's match type can change between deploys** if its
configured path gains or loses a placeholder (e.g. a chain redeploy changes
`/orders/{id}` to `/orders/active`). The old cached rule and the newly computed rule for
the same route will have different `type` values in that case; comparing on `value` alone
already produces a correct answer, but comparing on `(type, value)` (i.e. treating each
`GatewayPathMatch` as the identity, via its `equals`/`hashCode`) is what actually reflects
what "the same route's match" means once the match itself is typed.

Concretely: `touchedPaths` is built as a `Set<GatewayPathMatch>` from the current
deployment's routes, and a preserved rule is dropped from the preserved list (i.e. treated
as touched, and therefore replaced rather than kept) when
`GatewayPathMatch(ruleNode's type, ruleNode's value)` is contained in that set.

### 5. Unchanged

CR naming, `parentRefs`, `backendRefs`, Handlebars templates (`runtime-catalog`) and the
`ObjectNode`-based rule construction (`runtime-catalog`) / POJO builders (`engine`) around
`matches[0].path` are untouched — only the `type`/`value` fed into them changes.

## Testing

- **`GatewayPathMatch.forPath()` unit tests** (both modules): no placeholder (returns
  `PathPrefix` unchanged), single placeholder, placeholder followed by a literal suffix,
  multiple placeholders in one path, placeholder at the start of the path.
- **`HttpRouteResourceBuilder` / `IstioRoutesRegistrationService` rule-building tests**:
  a placeholder route produces a `RegularExpression` match with no `filters`; a
  placeholder-free route is unchanged (`PathPrefix`, no `filters`, matching current
  passing tests).
- **Preserved/touched-path tests**: a redeployed route whose path is unchanged is
  recognized as touched and replaced, not duplicated; the match-type-change edge case from
  [§4](#4-touched-path-identity) — a route's path changes from placeholder to
  placeholder-free (or vice versa) between deploys — is recognized as touched despite the
  `type` differing from the cached rule.
- **`CustomResourceService` cleanup tests**: snapshot/tier path stripping correctly
  identifies placeholder-path rules for removal.
