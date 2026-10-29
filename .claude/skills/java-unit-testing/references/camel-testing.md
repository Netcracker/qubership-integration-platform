# Camel testing

Read this file when the micro-engine class under test uses Camel: a `Processor`, `Predicate`, `AggregationStrategy`,
interceptor, route loader, or error handler factory, or the `Exchange`, `Message`, `CamelContext`, and `TypeConverter`
types. Helper names and imports are in `references/project-test-helpers.md`.

## Testing level

Test processors and helpers as plain unit tests: instantiate the processor, call `processor.process(exchange)`, and
assert the exchange state. Start a Camel runtime only when route execution, route startup, or endpoint resolution is the
behavior under test. Route-level behavior through compiled chains belongs to the `component-testing` skill.

## Exchange setup

Use a real `Exchange` when the test reads back headers, properties, or body:

```java
Exchange exchange = MockExchanges.defaultExchange();
exchange.getMessage().setHeader("X-Request-Id", "request-id");

processor.process(exchange);

assertEquals("request-id", exchange.getMessage().getHeader("X-Request-Id"));
```

Use `MockExchanges.withMessage()` or `withMessageAndConverter()` when the test verifies one interaction that Mockito
expresses better, such as a `TypeConverter` that returns `null` or throws. Keep converter stubs in the test that uses
them.

## Assertions

Assert what a caller of the processor sees:

* `exchange.getMessage().getHeader(...)`, `getBody(...)`, and `exchange.getProperty(...)`;
* `exchange.getException()` when the processor stores the error instead of throwing it;
* `assertThrows(...)` when the processor throws;
* calls on mocked collaborators, including a downstream call that is skipped.

Check a removed header with `assertNull`. For propagated headers, assert the relevant keys rather than a whole mutable
map, unless the full map is the contract.

## Context propagation and MDC

For the processors under `camel/processors/context/` and the interceptors that set a request ID, use a real `Exchange`
and mock only the external helpers. Cover restored context, applied override headers, removed context headers, the
request ID fallback, and a missing context.

MDC is thread-local and outlives the test. When the production code does not clear it, clear it in the test:

```java
@AfterEach
void tearDown() {
    MDC.clear();
}
```

## Route loading and error handlers

For `CustomXmlRoutesBuilderLoader`, `ErrorHandlerFactory`, and the `SourceProcessingListener` notifications, test the
decision: `ignoreRouteLoadingErrors()` true and false, a successful and a failed source load, a missing route source, the
listener notified on start, success, and failure, and the resulting load state. Do not test Camel internals that change
between Camel versions, or the generated configurers under `target/generated-sources`.

## Checkpoint processors

For `ContextSaverProcessor` and `ContextLoaderProcessor`, use a real `Exchange` and `ObjectMappers.getCheckpointMapper()`
for normal behavior. Mock the mapper or a static utility only to force an error branch.

A processor that returns or subscribes to a Mutiny `Uni` follows `references/async-lifecycle-testing.md`.
