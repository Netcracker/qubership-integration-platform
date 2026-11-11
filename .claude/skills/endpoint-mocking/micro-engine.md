# Endpoint mocking in micro-engine

The flow, the header contract, and the testing service side are the same as in `engine`. This file covers what differs
in the Quarkus module.

## Wiring

`HttpClientConfigurerBuilder.build()` asks `EndpointMockTestingService` for the interceptor and the route planner.
`TestingHttpComponentCustomizerProducer` installs the no-op `HttpActivityListener` in place of the Spring
`TestingHttpComponentConfiguration`. `TestingService` takes `EndpointInfo` here, where `engine` takes
`ElementProperties`, so the two implementations are duplicated rather than shared.

The builder resolves the bean programmatically, through
`InjectUtil.injectOptional(CDI.current().select(TestingService.class))`, so the implementation carries both
`@LookupIfProperty(name = "cip.testing.enabled", stringValue = "true")` and `@Unremovable`. ArC removes a bean that
nothing injects, and `@LookupIfProperty` does not mark it unremovable. Without `@Unremovable` the lookup returns empty
and mocking silently never happens. `MetricTagsHelper`, resolved the same way in the same method, carries
`@Unremovable` for the same reason, and so does the customizer producer.

A `@QuarkusComponentTest` cannot test the switch: component tests run neither the build step that applies
`@LookupIfProperty` nor bean removal, so such a test passes whatever the property says.
`EndpointMockTestingServiceTest` asserts the annotations by reflection instead. Verify the on and off behavior by hand.

## Context fields

| Field | Source |
| --- | --- |
| `chainId` | the builder's chain id |
| `elementId` | `endpointInfo.getElementId()` |
| `operationPath` | `endpointInfo.getPath()` |
| `path` | the live request target, read inside the interceptor, query included |

`HttpSenderBeansBinder` in `integration-build-pipeline`, the compiler Runtime Catalog uses, writes these values into
the builder's bean properties. `elementId` is `element.getOriginalId()`, the design-time id, falling back to the
element's own id. `operationPath` is `contextPath`, else `integrationOperationPath`, else the literal string `null`, for
every element type it handles. `EndpointInfo.path` therefore carries an operation template or `null`, never a request
path. An `http-sender` has neither property, so `null` arrives, and a path-parameter matcher finds no placeholder to
extract.

Keep `EndpointInfo` to the data the builder already receives. A new field needs a matching change in the binder, and
Camel binds these properties by setter name, so a compiled chain that writes a property an older engine cannot bind
fails at route-build time.

## Deployment

micro-engine is not part of the compose stack. On Kubernetes, Runtime Catalog creates micro-engine domains at runtime
with the environment in `MICRO_DOMAIN_ENVIRONMENT`. The `cip-dev` chart puts `TESTING_SERVICE_ENABLED` and
`TESTING_SERVICE_ADDRESS` there, read from the engine config map, so a micro domain follows the classic engine's
switch.
