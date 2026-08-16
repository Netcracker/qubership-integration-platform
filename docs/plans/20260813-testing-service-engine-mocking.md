# Testing Service: endpoint mocking in the engines (plan 2 of 3)

## Overview

Implement endpoint mocking on the engine side so that HTTP calls made by a chain can be intercepted and served by the
testing service instead of the real endpoint.

Both engines already carry the extension point: `TestingService` is declared in `engine` and `micro-engine`, and both
call it while configuring the HTTP client for chain elements. Neither ships an implementation, so the optional bean is
absent and mocking never happens. This plan supplies those implementations and the configuration that switches them on.

Depends on plan 1 (`20260813-testing-service-go-module.md`): the mock endpoint must exist, and the testing-context wire
format is defined by the Go model ported in its Task 2.

## Context (from discovery)

- `engine`: `TestingService` is consumed by `HttpSenderDependencyBinder` as `Optional<TestingService>`, injected by
  Spring. Its methods take `ElementProperties`.
- `micro-engine`: the same hook lives in `HttpClientConfigurerBuilder.build()` and resolves the bean programmatically —
  `InjectUtil.injectOptional(CDI.current().select(TestingService.class))`. Its methods take `EndpointInfo`.
- The two interfaces are therefore **not identical**, so the implementations cannot be shared — consistent with the rest
  of these modules, which duplicate code wholesale.
- **`EndpointInfo.path` is not a request path.** The builder's `operationPath(String)` setter writes that field, and
  `build()` passes it through as `EndpointInfo.path`. It carries the operation template from runtime-catalog's
  `HttpSenderBeansBinder`, not anything request-scoped. The name is a trap.
- The caller already filters elements: only HTTP chain elements that are not HTTP triggers reach the hook.
- **Two element ids exist and only one is correct.** `ElementProperties.getElementId()` is the *snapshot* element id
  (`DeploymentBuilderService` builds it from `element.getId()`), which changes on every snapshot. The design-time id is
  `properties.get(ChainProperties.ELEMENT_ID)`, which `CommonPropertiesBuilder` fills from `element.getOriginalId()`.
  Mocks are bound to the design-time id, and `micro-engine` already sends `getOriginalId()`.
- **There is no `path` property.** `ChainProperties.PATH` is declared in both engines and written by nothing.
- `operationPath` differs per engine and per element type: for a `service-call` both send `integrationOperationPath`;
  for an `http-sender` `engine` sends the element's `uri`, while `micro-engine` sends
  `contextPath ?? integrationOperationPath ?? "null"` — and an `http-sender` has neither, so the literal string `null`
  arrives.
- The reverse direction is already wired: the testing service stamps `external-session-cip-id` on the trigger request,
  and `SessionsService` in both engines reads it, which is how a run is linked to a session.
- Mock semantics match the source implementation: when no enabled mock matches, the testing service answers `404`.
  Among matching mocks it picks the most specific — sorted by enabled-matcher count descending, then creation time
  ascending.
- `micro-engine` is not part of the local compose stack. Its test suite does include `quarkus-junit` and ten
  `@QuarkusComponentTest` classes, but none of them reproduce conditional registration: component tests run neither the
  build step that generates `@LookupIfProperty` suppression nor bean removal, so the on/off switch cannot be covered
  automatically there.
- Besides `service-call` and `http-sender`, a `graphql-sender` element also reaches this hook and behaves like an
  `http-sender` for the purposes of this feature.

## Development Approach

- **testing approach**: Regular — implementation first, tests immediately after within the same task
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility

New files carry no copyright header, per project convention, and must pass Checkstyle, which runs on compile. The
sanitization rules from plan 1 apply to code, configuration and commit messages; `external-session-cip-id` stays, since
it is already public platform code.

## Testing Strategy

- **unit tests**: interceptor behavior (rewritten target, `path` carrying the query string, encoded context header),
  route planner target, `canBeMocked`, context encoding against a golden base64 literal shared with plan 1
- **Spring conditional-bean test**: `ApplicationContextRunner` over the single component class — the engine's only
  `@SpringBootTest` needs a Consul stub, Flyway disabled and a custom initializer, far too much machinery for this
- **Quarkus registration test**: `@QuarkusComponentTest` with `@TestConfigProperty`, modeled on the existing producer
  test in that module
- **manual verification**: the local compose stack, which contains the Spring engine only (Task 6)

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

When mocking is enabled, the HTTP client configured for a chain element gets two additions:

- a **route planner** that sends the request to the testing service instead of the real host,
- a **request interceptor** that rewrites the request target to `/api/v1/endpoint-mocks/call` and attaches a
  `Testing-Service-Context` header.

The header carries base64-encoded JSON identifying the call. The authoritative field names come from the Go model in
plan 1, Task 2:

```json
{"chainId": "...", "elementId": "...", "operationPath": "...", "path": "..."}
```

**All four fields are load-bearing — none is diagnostic.** The testing service's matching engine reads the header, not
the wire request:

| Field | Consumed by | Consequence of getting it wrong |
|---|---|---|
| `chainId`, `elementId` | mock lookup | no mock ever matches |
| `path` | query-parameter matchers — the field is parsed as a URL and its query is read | every query-parameter matcher silently fails |
| `path` + `operationPath` | path-parameter matchers — the two are split into segments and aligned to extract `{name}` templates | every path-parameter matcher silently fails |

So `path` must be the **live request target including the query string**, and `operationPath` must be the **operation
template**. For an `http-sender` there is no template, so path-parameter matching degrades to a no-op — acceptable, but
not because the field is unused.

`elementId` must be the design-time id. The mock lookup keys on `(chainId, elementId)` only.

**Why the feature is off by default.** With mocking enabled but the testing service missing, every outbound HTTP call
from every chain fails with a connection error. And with it enabled *and* the service present, any call without a
matching mock gets `404`. Either way, switching it on changes behavior for every chain on the environment, so it stays
off unless someone is deliberately testing.

## Technical Details

**Configuration** (both engines):

| Property | Default | Meaning |
|---|---|---|
| `qip.testing.enabled` | `false` | registers the `TestingService` implementation |
| `qip.testing.address` | `http://testing-service:8080` | base address of the testing service |

**Spring (`engine`)**: a `@ConditionalOnProperty`-guarded component, injected through the existing
`Optional<TestingService>` parameter of `HttpSenderDependencyBinder`; the binder does not change. Read the two values
with `@Value` on the component — this module has roughly sixty `@Value("${qip.…}")` sites against two
`@ConfigurationProperties` holders, so a holder plus its `@EnableConfigurationProperties` registration would be heavier
than the local norm for two scalars.

**Quarkus (`micro-engine`)**: the bean is resolved by a programmatic CDI lookup, so it needs **both**
`@LookupIfProperty` and `@Unremovable`. ArC removes beans that are never injected, and `@LookupIfProperty` does not
mark a bean unremovable — the neighbouring `MetricTagsHelper`, resolved the same way two lines above, carries
`@Unremovable` for exactly this reason.

**Field mapping per engine**, since the sources differ:

| Context field | `engine` | `micro-engine` |
|---|---|---|
| `chainId` | `DeploymentInfo.getChainId()` | the builder's chain id |
| `elementId` | `properties.get(ChainProperties.ELEMENT_ID)` | `endpointInfo.getElementId()` |
| `operationPath` | `properties.get(ChainProperties.OPERATION_PATH)` | `endpointInfo.getPath()` — the misnamed field |
| `path` | live request target from the interceptor, query included | same |

**Encoding.** The Go decoder uses the standard base64 alphabet with padding, so the Java side must use
`Base64.getEncoder()` over UTF-8 JSON — not the URL-safe, unpadded or MIME variants, all of which compile and pass a
Java-only golden test while failing against Go.

**hc5 mechanics** (verified against the versions this repository resolves):

- `HttpRequest.setPath` stores the request target verbatim, so the query string can be preserved through the same call;
  no other API is needed. Preserving it on the wire is cosmetic, though — the testing service builds its exchange from
  the body and headers only, so query matching depends on the header field, not the wire.
- `ProtocolExec` fills scheme and authority from the route only when they are absent, and Camel supplies an absolute
  URI, so the `Host` header would still name the original endpoint unless the interceptor sets them. Custom
  first-position interceptors run before the built-in `RequestTargetHost`, so setting them there does take effect.
- The mock interceptor runs before the metrics one, but per-endpoint metrics are **not** affected in practice: both
  engines label the metric from the element's operation-path property and fall back to the request URI only when that
  property is absent, which does not happen for the elements reaching this hook. Only the engine's rare fallback path
  would see the rewritten URI, and then the preserved query would vary the label per call.

**`EndpointInfo` stays as it is.** Extending it would require a matching change in runtime-catalog's
`HttpSenderBeansBinder` — the builder has no data of its own — and Camel binds those properties by setter name, so a
catalog writing a property an older engine cannot bind fails at route-build time.

## What Goes Where

- **Implementation Steps**: engine and micro-engine code, configuration, tests, compose wiring
- **Post-Completion**: enabling the feature on real environments, operational caveats

## Implementation Steps

### Task 1: Mock context encoding in `engine`

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/service/testing/TestingContext.java`
- Create: `engine/src/test/java/org/qubership/integration/platform/engine/service/testing/TestingContextTest.java`

- [x] cross-check field names and casing against the Go struct in `testing-service/internal/model` before writing the class
- [x] add `TestingContext` carrying `chainId`, `elementId`, `operationPath`, `path`, serialized as UTF-8 JSON and encoded with `Base64.getEncoder()` — standard alphabet, padded, no line breaks
- [x] write tests for encoding, including null and empty fields, characters requiring escaping, and a `path` containing a query string
- [x] pin one golden base64 literal for a fixed input, choosing a fixture whose encoding contains `+` or `/` and padding — otherwise the standard and URL-safe alphabets coincide and the literal proves nothing. The same literal is asserted in micro-engine and decoded in plan 1's Go test, which is what pins all three implementations
- [x] run `mvn -pl engine -am test -Dgpg.skip=true` - must pass before next task

➕ Two golden literals, not one: plan 1's `goldenTestingContextHeader` encodes identically under the standard and the
URL-safe alphabets, so it cannot catch `Base64.getUrlEncoder()`. A second literal — path `/orders/7?status=NEW&filter=price>100`,
whose encoding carries `+`, `/` and padding — was added to the engine test and to plan 1's Go test, which decodes it.
Only decoding is pinned on the Go side: `json.Marshal` escapes `>` for HTML safety and the engine does not, so the two
encoders disagree byte for byte on that fixture. Task 4 asserts both literals in `micro-engine`.

⚠️ Checkstyle rejects Cyrillic characters in sources (`cyrillicChars` rule), so the non-ASCII test uses accented Latin.

### Task 2: `TestingService` implementation in `engine`

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/service/testing/EndpointMockTestingService.java`
- Create: `engine/src/test/java/org/qubership/integration/platform/engine/service/testing/EndpointMockTestingServiceTest.java`

- [ ] implement `canBeMocked(ElementProperties)` — the caller already restricts this to non-trigger HTTP elements, so this only confirms the design-time element id is present
- [ ] read the element id from `properties.get(ChainProperties.ELEMENT_ID)`, never from `ElementProperties.getElementId()`: the latter is the snapshot id and changes with every snapshot, so mocks would match nothing and silently break on redeploy
- [ ] read `operationPath` from `properties.get(ChainProperties.OPERATION_PATH)`
- [ ] implement `buildEndpointMockInterceptor(chainId, elementProperties)`, building and encoding the context **inside `process(...)` on every invocation** — the builder method itself runs once per Camel HTTP client build, not per request
- [ ] put the live request target, query string included, into the context's `path` field; rewrite the target to `/api/v1/endpoint-mocks/call`, keeping the query on the wire for readable logs; set scheme and authority to the testing service; add the `Testing-Service-Context` header
- [ ] implement `buildRoutePlanner(chainId, elementProperties)` returning a route to the configured host, honoring scheme and port
- [ ] register the component under `@ConditionalOnProperty` for `qip.testing.enabled`, reading both values with `@Value`
- [ ] write a test that fails if the snapshot element id is used instead of the design-time one
- [ ] write tests asserting `path` carries the query string and `operationPath` carries the template, since both feed matchers
- [ ] write tests for the rewritten target, the decodable header, the route planner (host, port, https) and `canBeMocked` returning false when the id is missing
- [ ] run `mvn -pl engine -am test -Dgpg.skip=true` - must pass before next task

### Task 3: Wire configuration into `engine`

**Files:**
- Modify: `engine/src/main/resources/application.yml`
- Create: `engine/src/test/java/org/qubership/integration/platform/engine/service/testing/TestingServiceConditionTest.java`

- [ ] add both properties following the file's existing `${ENV_VAR:default}` style, with `qip.testing.enabled` defaulting to `false`
- [ ] confirm `HttpSenderDependencyBinder` needs no change — it already takes `Optional<TestingService>`
- [ ] write an `ApplicationContextRunner` test asserting the bean is absent by default and present once the property is set
- [ ] run `mvn -pl engine -am test -Dgpg.skip=true` - must pass before next task

### Task 4: `TestingService` implementation in `micro-engine`

**Files:**
- Create: `micro-engine/src/main/java/org/qubership/integration/platform/engine/service/testing/EndpointMockTestingService.java`
- Create: `micro-engine/src/main/java/org/qubership/integration/platform/engine/service/testing/TestingContext.java`
- Modify: `micro-engine/src/main/resources/application.yml`
- Create: `micro-engine/src/test/java/org/qubership/integration/platform/engine/service/testing/EndpointMockTestingServiceTest.java`

- [ ] port the context encoding and assert the same golden base64 literal as Task 1 — the two modules cannot see each other, so a shared literal is the only practical drift check
- [ ] map the fields explicitly: `operationPath` comes from `endpointInfo.getPath()`, which despite its name carries the operation template; `path` comes from the live request inside the interceptor
- [ ] implement the three methods against `EndpointInfo`, which is not extended
- [ ] annotate the bean with both `@LookupIfProperty` and `@Unremovable` — without the latter ArC removes it, the lookup returns empty, and mocking silently never happens
- [ ] add the two properties to this module's `application.yml`, which currently has no `qip.testing` entry
- [ ] pass `operationPath` through unchanged when it arrives as the literal string `null` for `http-sender` elements — there is nothing to handle on the Java side, the degradation happens in the Go matcher and is expected
- [ ] do not attempt an automated registration test: `@QuarkusComponentTest` runs neither the build step that generates `@LookupIfProperty` suppression nor bean removal, so it would pass regardless of the property. Cover behavior with unit tests and verify the on/off switch manually in Task 6
- [ ] write the same behavioral test set as Task 2
- [ ] run `mvn -pl micro-engine -am test -Dgpg.skip=true` - must pass before next task

### Task 5: Local stack wiring

**Files:**
- Modify: `infrastructure/engine-dev.env`
- Modify: `infrastructure/qip-dev/charts/qip-engine/templates/*.yaml`

- [ ] add both variables to `engine-dev.env` with mocking **off** — the compose service has no `environment:` block and is fed by env files only
- [ ] do not enable mocking by default: with it on, every outbound HTTP call from every local chain without a matching mock returns `404`, which would silently break the shared local stack for everyone not working on this feature
- [ ] document how to turn it on for a session, and use that path in Task 6
- [ ] keep the engine free of a compose dependency on the testing service — the testing service calls the engine, and a dependency would create a cycle
- [ ] surface both properties in the engine helm chart with mocking off by default, and set the address to the release-scoped service name in the engine configmap, following the existing runtime-catalog URL entry — the compose default hostname does not resolve on Kubernetes
- [ ] no automated tests here; verified by stack startup and by Task 6

### Task 6: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] run `mvn -pl engine -am test -Dgpg.skip=true` and `mvn -pl micro-engine -am test -Dgpg.skip=true`
- [ ] bring up the stack — this exercises the Spring engine only, since `micro-engine` is not in compose — create a chain with an HTTP trigger and an outbound HTTP call, and deploy it
- [ ] with mocking off, call the chain and confirm the outbound request reaches the real endpoint
- [ ] turn mocking on and restart the engine — on restart it re-processes every deployment (the exclude list is built from in-memory state, which is empty), so the HTTP client is rebuilt and the toggle takes effect without a chain redeploy; confirm this holds, since the caveat goes into two CLAUDE.md files in Task 7
- [ ] with no mock defined, confirm the call is answered `404` by the testing service and the chain reports the failure
- [ ] define a matching endpoint mock and confirm the chain receives the mocked status, body, headers and configured delay
- [ ] define a mock whose request matcher keys on a **query parameter** and confirm it matches — this proves the context's `path` field carried the query string, which is what the matching engine reads
- [ ] on a `service-call` element, define a mock keyed on a **path parameter** and confirm it matches — this proves `operationPath` carried the template
- [ ] define two matching mocks with different matcher counts and confirm the more specific one wins
- [ ] redeploy the chain, creating a new snapshot, and confirm the same mock still matches — this proves the design-time element id is being sent
- [ ] repeat the basic mock check on a `graphql-sender` element, which also reaches this hook and behaves like `http-sender`
- [ ] run a test case against the chain and confirm the run links to a session visible in the sessions UI
- [ ] check engine logs for interceptor errors, and confirm per-endpoint HTTP metrics keep their element-derived labels rather than collapsing

### Task 7: [Final] Update documentation

- [ ] document both properties, the enable-only-where-deployed rule, and what a toggle requires — record whatever Task 6 established, not an assumption
- [ ] document the context field semantics — that `path` must carry the query string and `operationPath` the template — so a future change does not quietly break matchers
- [ ] note the interface difference between the two engines, the element-id trap, and the misnamed `EndpointInfo.path`
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Enabling on environments:**

- turn `qip.testing.enabled` on only where the testing service is deployed, and only when someone is testing: with it
  on, every outbound HTTP call without a matching mock returns `404`
- the feature is intended for development and test environments only
- changing the property requires an engine restart; the restart re-processes every deployment, so chains need no redeploy

**Behavioral notes to communicate to users:**

- an element with no matching mock receives `404`, not a pass-through to the real endpoint
- among matching mocks the most specific wins — most enabled matchers first, then oldest

**User-facing documentation** for the mock interception flow is written in plan 3, alongside the testing pages.
