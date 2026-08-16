# engine

The Spring Boot execution engine. `README.md` next to this file covers building, configuration and dependencies; this
file covers the constraints that are easy to break while changing the module.

Keep durable notes here. `CLAUDE.md` files are not versioned in this repository, only `AGENTS.md` is. No APM primitive
targets `engine/**`, so `apm compile` never writes this file: like `testing-service/AGENTS.md`, it is maintained by hand
and you edit it directly.

## Endpoint mocking

`EndpointMockTestingService` implements `TestingService`, and `HttpSenderDependencyBinder` takes it as an
`Optional<TestingService>`, asking it for a route planner and a request interceptor while it configures the HTTP client
of a chain element. The binder already filters: only HTTP elements that are not triggers reach the hook, which in
practice means `http-sender`, `graphql-sender` and a `service-call` whose `integrationOperationProtocolType` is `http`
or `graphql` — a Kafka, AMQP or gRPC service call never reaches it and cannot be mocked. The interceptor points scheme
and authority at the testing service, rewrites the request target to `/api/v1/endpoint-mocks/call`, and attaches a
`Testing-Service-Context` header.

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `qip.testing.enabled` | `TESTING_SERVICE_ENABLED` | `false` | registers the bean, through `@ConditionalOnProperty` |
| `qip.testing.address` | `TESTING_SERVICE_ADDRESS` | `http://testing-service:8080` | base address of the testing service; a base path in it is kept, so an ingress-style address works |

**Enable it only where the testing service is deployed, and only while someone is testing.** The switch is
environment-wide rather than per chain: with mocking on and the service absent, every outbound HTTP call from every
chain fails to connect; with the service present, a call with no matching mock is answered `404` instead of reaching the
real endpoint. Among matching mocks the most specific wins — most enabled matchers first, then oldest.

**Whatever the element sends, the testing service receives.** The interceptor re-points the request but forwards its
headers and body untouched, so an `Authorization` header, an API key or a cookie meant for the real endpoint is
delivered to the testing service, and a secret carried in the query string is copied into the context header as well.
That is one more reason to keep this off outside development and test environments.

An accidentally enabled environment is visible in the log: the bean logs one `INFO` line at startup naming the resolved
address, each mocked call adds a `DEBUG` line, and an element skipped for a missing design-time id gets a `WARN`.

On Kubernetes the switch is `global.qip.testingService.engineMockingEnabled` in `infrastructure/qip-dev/values.yaml`,
which the engine config map renders into `TESTING_SERVICE_ENABLED`. It renders whatever the flag says, because the
deployment reads the key unconditionally and a missing one pins the pod in `CreateContainerConfigError`. In compose the
switch is `TESTING_SERVICE_ENABLED` in `infrastructure/engine-dev.env`.

**A toggle takes effect on an engine restart, and no chain needs a redeploy.** On startup the engine re-processes every
deployment as an `UPDATE` with its snapshot id unchanged, which rebuilds the HTTP client, so the first call after the
restart is already mocked. This holds in both directions: switching the flag back off and restarting restored the real
endpoint, again with no redeploy.

### The context header

`TestingContext` is serialized as UTF-8 JSON and encoded with the standard padded base64 alphabet
(`Base64.getEncoder()`). The Go decoder accepts nothing else, while the URL-safe, unpadded and MIME variants all compile
and pass a Java-only test. Golden literals in `TestingContextTest` pin the encoding against the copies in `micro-engine`
and in the Go module.

All four fields are load-bearing, and the testing service reads them from the header rather than from the wire request:

| Field | Source | Consumed by |
| --- | --- | --- |
| `chainId` | `DeploymentInfo.getChainId()` | mock lookup |
| `elementId` | `properties.get(ChainProperties.ELEMENT_ID)` | mock lookup |
| `operationPath` | `properties.get(ChainProperties.OPERATION_PATH)` | path-parameter matchers |
| `path` | the live request target, read inside the interceptor | query-parameter and path-parameter matchers |

`path` must carry the **query string**: a query-parameter matcher parses that field as a URL and reads its query.
`operationPath` must carry the **operation template**: a path-parameter matcher splits both fields into segments and
aligns them to extract the `{name}` placeholders. Drop the query, or put a request path in `operationPath`, and those
matchers stop matching with no error anywhere. The query is kept on the wire too, but only so the logs read well.

The context is built inside `process(...)`, on every request. The builder method itself runs once per Camel HTTP client
build, so anything request-scoped computed there would be frozen at deployment time. The interceptor returns early when
the request already carries the header: hc5 runs the processor again over the same request on an authentication
challenge, and a second rewrite would report the mock endpoint as the live target.

An `http-sender` has no operation template, so this engine sends the element's `uri` and path-parameter matching
degrades to a no-op. A `graphql-sender` appends `?operationName=<name>` to that `uri`; when the `uri` already carries a
query, the appended part is percent-encoded into the last parameter's value, so a query matcher over such an element
sees `NEW?operationName=listDatasets` rather than `NEW`. That is chain configuration and predates mocking.

### The element-id trap

`elementId` is the **design-time** id, which `CommonPropertiesBuilder` fills from `element.getOriginalId()` under
`ChainProperties.ELEMENT_ID`. `ElementProperties.getElementId()` is the *snapshot* id and changes with every snapshot.
Mocks bind to the design-time id and the lookup keys on `(chainId, elementId)` alone, so reading the wrong one silently
matches nothing after the next deploy. There is no request-path property to reach for either: `ChainProperties.PATH`
is declared and written by nothing.

### Differences from `micro-engine`

The `TestingService` interface is not the same in the two modules — this one takes `ElementProperties`, `micro-engine`
takes `EndpointInfo` — so the two implementations are duplicated, which is how the rest of these two modules are written
anyway. The wire format is a single contract, though: a change to the header has to land in `micro-engine`'s copy of
`TestingContext` and in the Go decoder as well.

### Sessions and metrics under mocking

- A test run is linked to a session through `external-session-cip-id`. The testing service generates that id, sends it
  on the trigger request and stores it as the run's `sessionId`, while `SessionsService` writes it to
  `Session.externalId` and the session keeps an id of its own. The run's `sessionId` is therefore not a key into
  `GET /v1/sessions/{id}`, which answers `404`; match it against `externalSessionCipId`, which is what
  sessions-management calls that field in its session DTO. And a session exists only when
  the chain's `sessionsLoggingLevel` is above `OFF`, which is not the default — with logging off the run still records a
  `sessionId` that resolves to nothing.
- `httpcomponents_httpclient_request_seconds` keeps `chain_id`, `chain_name`, `element_id`, `element_name` and the
  element-derived `uri` label, because both engines label the metric from the element's operation-path property rather
  than from the request. Both fall back to the request URI when that property is absent, and under mocking the fallback
  reads the rewritten target with its query string, which is one label value per distinct query — a cardinality hazard,
  though the elements that reach this hook do carry the property. But `target_host`, `target_port` and `target_scheme`
  follow the route and read the testing service's address: the mock interceptor rewrites scheme and authority before the
  metrics interceptor runs.
