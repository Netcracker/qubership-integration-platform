# micro-engine

The Quarkus execution engine, functionally the same as `engine/`. `README.md` next to this file covers building,
configuration and dependencies; this file covers the constraints that are easy to break while changing the module.

Keep durable notes here. `CLAUDE.md` files are not versioned in this repository, only `AGENTS.md` is. No APM primitive
targets `micro-engine/**`, so `apm compile` never writes this file: like `testing-service/AGENTS.md`, it is maintained
by hand and you edit it directly.

## Endpoint mocking

`EndpointMockTestingService` implements `TestingService`, and `HttpClientConfigurerBuilder.build()` asks it for a route
planner and a request interceptor while it configures the HTTP client of a chain element. The interceptor points scheme
and authority at the testing service, rewrites the request target to `/api/v1/endpoint-mocks/call`, and attaches a
`Testing-Service-Context` header.

The header contract, the base64 encoding and the behavior of the mocks are one thing across both engines and are written
up in `engine/AGENTS.md`. Read that first; this section covers what differs here.

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `qip.testing.enabled` | `TESTING_SERVICE_ENABLED` | `false` | makes the bean lookup resolve, through `@LookupIfProperty` |
| `qip.testing.address` | `TESTING_SERVICE_ADDRESS` | `http://testing-service:8080` | base address of the testing service |

**Enable it only where the testing service is deployed, and only while someone is testing.** With mocking on and the
service absent, every outbound HTTP call from every chain fails to connect; with the service present, a call with no
matching mock is answered `404` instead of reaching the real endpoint. A toggle takes effect on an engine restart, which
re-processes every deployment and rebuilds the HTTP client, so no chain needs a redeploy. That was verified on the Spring
engine: `micro-engine` is in neither the local compose stack nor a Helm chart, so its configuration is set wherever its
domain container is.

### Registration takes two annotations

`HttpClientConfigurerBuilder` resolves the bean programmatically, through
`InjectUtil.injectOptional(CDI.current().select(TestingService.class))`, so the implementation carries **both**
`@LookupIfProperty` and `@Unremovable`. ArC removes a bean nothing injects and `@LookupIfProperty` does not mark one
unremovable; drop `@Unremovable` and the lookup returns empty, so mocking never happens and nothing reports it. The
neighboring `MetricTagsHelper`, resolved the same way two lines above, carries `@Unremovable` for the same reason.

A `@QuarkusComponentTest` cannot cover the switch. Component tests run neither the build step that generates the
`@LookupIfProperty` suppression nor bean removal, so such a test passes whatever the property says. Verify the on/off
switch by hand.

### `EndpointInfo.path` is not a request path

`TestingService` here takes `EndpointInfo` where `engine` takes `ElementProperties`, which is why the two
implementations are duplicated rather than shared. The field mapping follows from that:

| Context field | Source |
| --- | --- |
| `chainId` | the builder's chain id |
| `elementId` | `endpointInfo.getElementId()`, already the design-time id, so `engine`'s snapshot-id trap has no counterpart here |
| `operationPath` | `endpointInfo.getPath()` |
| `path` | the live request target, read inside the interceptor, query included |

`EndpointInfo.path` carries the **operation template**, not a request path. The builder's `operationPath(String)` setter
writes the field and runtime-catalog's `HttpSenderBeansBinder` fills it: `integrationOperationPath` for a
`service-call`, `contextPath ?? integrationOperationPath ?? "null"` for an `http-sender` — which has neither, so the
literal string `null` arrives. It is passed through unchanged; path-parameter matching then degrades to a no-op in the
Go matcher, which is expected.

Do not extend `EndpointInfo` to carry more. The builder has no data of its own, so a new field means a matching change
in runtime-catalog, and Camel binds those properties by setter name: a catalog writing a property an older engine cannot
bind fails at route-build time.
