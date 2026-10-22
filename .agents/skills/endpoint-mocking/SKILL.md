---
name: endpoint-mocking
description: Explains how the engine, micro-engine, and testing service redirect the outbound HTTP calls of a test-case run to endpoint mocks, and the contracts that keep them in step. Use when changing endpoint mocking, the Testing-Service-Context header, the mock lookup in the testing service, or how a test-case run is sessioned or metered.
---

# Endpoint mocking

Mocking redirects only the outbound HTTP calls of a **test-case run**. Every other exchange, live traffic included,
reaches its real endpoint, whatever the value of `cip.testing.enabled`. The engine and micro-engine implement the redirect, and the testing
service answers the redirected call. This file describes the Spring `engine`. Read
[micro-engine.md](micro-engine.md) before changing `micro-engine/`.

## How a call is redirected

1. The testing service starts a run by calling the chain's HTTP trigger with an `external-session-cip-id` header.
   `HttpTriggerProcessor` copies a non-blank value into the `TESTING_SESSION_ID` exchange property, which survives a
   chain call and a split. A trigger request that carries the header from any other caller starts a test-case run too.
2. `HttpSenderDependencyBinder` builds the element's HTTP client once, at deployment, and takes the optional
   `TestingService` bean. Only non-trigger HTTP elements reach it: `http-sender`, `graphql-sender`, and a
   `service-call` whose `integrationOperationProtocolType` is `http` or `graphql`. A Kafka, AMQP, or gRPC service call
   cannot be mocked. An element without a design-time id is skipped with a `WARN`.
3. `EndpointMockTestingService` installs a request interceptor and a route planner. Both call
   `belongsToTestCaseRun`, which reads `TESTING_SESSION_ID` from the Camel exchange in the `HttpContext`. Without the
   property, the interceptor returns without changes and the planner picks the live route.
4. For a test-case run, the interceptor points scheme and authority at the testing service, rewrites the target to
   `<base path>/api/v1/endpoint-mocks/call`, keeps the query string for readable logs, and sets the
   `Testing-Service-Context` header.

camel-http puts the exchange into the client context only for an endpoint that carries an `HttpActivityListener`, so
`TestingHttpComponentConfiguration` installs a no-op one while `cip.testing.enabled` is on. The GraphQL producer calls
its client with a context of its own and no exchange in it, so a GraphQL call is never mocked and reaches its endpoint
even during a test-case run. The UI matches this: a mock can be bound only to an HTTP sender or an HTTP service call.

## Switching it on

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `cip.testing.enabled` | `TESTING_SERVICE_ENABLED` | `false` | registers `EndpointMockTestingService` and the listener customizer |
| `cip.testing.address` | `TESTING_SERVICE_ADDRESS` | `http://testing-service:8080` | testing service base address; a base path in it is kept, so an ingress-style address works |

- Compose ships mocking on: `TESTING_SERVICE_ENABLED=true` in `infrastructure/docker/engine-dev.env`.
- Helm ships it off. `global.qip.testingService.engineMockingEnabled` in `infrastructure/helm/qip-dev/values.yaml`
  renders `TESTING_SERVICE_ENABLED` into the engine config map only together with `global.qip.testingService.enabled`,
  so the engine never points at a testing service that is not deployed. The key renders in both cases, because the
  deployment reads it and a missing key leaves the pod in `CreateContainerConfigError`. Runtime Catalog passes the same
  two config map keys to micro-engine domains.
- A toggle takes effect on an engine restart, with no redeploy. On startup the engine re-processes every deployment as
  an `UPDATE` with the same snapshot id, which rebuilds the HTTP clients.

With the flag on, startup logs one `INFO` line naming the resolved address, and each mocked call logs a `DEBUG` line.

The redirected request keeps its headers and body, so an `Authorization` header, API key, or cookie meant for the real
endpoint reaches the testing service, and a secret in the query string is also copied into the context header.

## The `Testing-Service-Context` header

`TestingContext` is serialized as UTF-8 JSON in the field order below and encoded with `Base64.getEncoder()`, the
standard padded alphabet. The Go decoder (`testing-service/internal/model/testing_context.go`) uses
`base64.StdEncoding` and accepts nothing else, while the URL-safe, unpadded, and MIME variants compile and pass a
Java-only test. Golden literals in each module's `TestingContextTest` pin the encoding. A change to the header lands
in three places: `engine`, `micro-engine`, and the Go module.

| Field | Engine source | Read by |
| --- | --- | --- |
| `chainId` | `DeploymentInfo.getChainId()` | mock lookup |
| `elementId` | `ChainProperties.ELEMENT_ID` from the element properties | mock lookup |
| `operationPath` | `ChainProperties.OPERATION_PATH` (`integrationOperationPath`) | path-parameter matchers |
| `path` | the live request target, read inside the interceptor | query- and path-parameter matchers |

The testing service reads these fields from the header, not from the wire request:

- `path` carries the query string, because a query-parameter matcher parses it as a URL.
- `operationPath` carries the operation template, because a path-parameter matcher aligns its segments with `path` to
  extract `{name}` placeholders. Runtime Catalog fills it with the `uri` for an `http-sender` or a `graphql-sender`,
  so path-parameter matching on those elements has no template to work with.

A wrong value in either field produces no error: the matcher stops matching and the call gets `404`.

## Invariants

- **The element id is the design-time id.** Runtime Catalog's `CommonPropertiesBuilder` writes
  `element.getOriginalId()` under `ChainProperties.ELEMENT_ID`. `ElementProperties.getElementId()` is the snapshot id
  and changes with every snapshot, so a lookup keyed on it matches nothing after the next deploy.
  `ChainProperties.PATH` is declared and written by nothing.
- **Build the context per request, inside the interceptor.** The builder method runs once per client build, so a
  request-scoped value computed there is fixed at deployment time.
- **Guard the second pass with the `HttpContext` attribute, never with the header.** On an authentication challenge
  hc5 restores the original headers and runs the interceptor over the same request again. The attribute holds the
  request instance, matched by identity, and replays the first pass's context. A redirect's follow-up request is a new
  object and is rewritten in its turn. A `containsHeader(TestingContext.HEADER_NAME)` check would let a chain caller
  who sends the header keep the live target with mocking on. `ignoresAContextHeaderTheCallerSupplied` and
  `ignoresAContextHeaderTheCallerSuppliedOnTheSecondPass` pin this, and `micro-engine` has the same pair under
  `should…` names.

## The testing service side

`endpointMocksService.Call` in `testing-service/internal/services/endpoint_mocks_service.go` loads the enabled mocks for
`(chainId, elementId)`, orders them by number of enabled matchers, most first, then by creation time, oldest first, and answers
with the first mock whose matchers all pass. A mock whose matcher cannot be evaluated is skipped with a warning. With no
match the answer is `404`; the engine never falls back to the real endpoint.

## Sessions and metrics of a test-case run

- The testing service generates the `external-session-cip-id` value and stores it as the run's `sessionId`.
  `SessionsService` writes it to `Session.externalId`, and the session keeps an id of its own. The run's `sessionId` is
  therefore not a key for `GET /v1/sessions/{id}`; match it against `externalSessionCipId` in the sessions-management
  session DTO. A session exists only when the chain's `sessionsLoggingLevel` is above `OFF`, and `OFF` is the default,
  so a run can record a `sessionId` that resolves to nothing.
- `httpcomponents_httpclient_request_seconds` keeps the element's `chain_id`, `chain_name`, `element_id`,
  `element_name`, and `uri` labels, because both engines take `uri` from the operation-path property. When that
  property is absent, they fall back to the request URI, which under mocking is the rewritten target with its query
  string: one label value per distinct query. `target_host`, `target_port`, and `target_scheme` name the testing
  service, because the mock interceptor rewrites the request before the metrics interceptor runs.
