# Camel testing

Read this file when Java unit test work involves Apache Camel APIs or Camel-based application code.

Use this reference for tests around:

* Camel `Processor`, `Predicate`, `AggregationStrategy`, interceptors, filters, and route-related helpers
* `Exchange`, `Message`, `CamelContext`, `TypeConverter`, headers, properties, and body handling
* context propagation, MDC propagation, request IDs, checkpoint processors, and message helper utilities
* Camel error handling helpers and source-loading logic
* classes that use Camel static helpers or Camel-specific utility methods

For shared helper names and imports, read `references/project-test-helpers.md`.

## Testing level

Prefer plain unit tests for Camel processors and helper classes.

A plain unit test is usually enough when the test verifies:

* headers
* exchange properties
* message body
* MDC state
* processor side effects
* exception/fallback behavior
* interactions with mocked dependencies
* transformation logic around an `Exchange`

Use a real Camel runtime only when Camel route execution, route startup, endpoint resolution, or framework integration is the behavior under test.

For ordinary processor tests, instantiate the processor directly and call:

```java
processor.process(exchange);
```

Then assert the observable exchange state.

## Exchange and Message setup

Prefer project helpers over manual deep mocking.

Read `references/project-test-helpers.md` before adding local `Exchange`, `Message`, `CamelContext`, or `TypeConverter` helpers.

Use a real Exchange when the test needs real state transitions:

```java
Exchange exchange = MockExchanges.defaultExchange();
exchange.getMessage().setBody("body");
exchange.getMessage().setHeader("X-Request-Id", "request-id");

processor.process(exchange);

assertEquals("request-id", exchange.getMessage().getHeader("X-Request-Id"));
```

Use mocked Exchange and Message only when the test needs to verify a specific interaction that is easier to express with Mockito.
Avoid over-mocking these Camel types when real exchange state makes the test clearer:

* `Exchange`
* `Message`
* `CamelContext`
* `TypeConverter`

## Headers, properties, and body assertions

Assert public Camel state instead of implementation details.

Prefer assertions against:

* `exchange.getMessage().getHeader(...)`
* `exchange.getMessage().getBody(...)`
* `exchange.getProperty(...)`
* `exchange.getException()`
* `exchange.getMessage().getHeaders()`
* emitted or removed context headers
* dependency calls caused by exchange processing

Use exact expected values when possible. When asserting removed headers or properties, check absence directly:

```java
assertNull(exchange.getMessage().getHeader("X-Some-Header"));
```

When asserting a set of propagated headers, prefer checking the relevant keys and values instead of comparing a large mutable map unless full-map behavior is the contract.

## Context propagation and MDC

For context propagation processors, prefer real Exchange state plus focused mocks for external helpers. Verify observable behavior:

* request context is restored
* override headers are applied
* context headers are removed
* expected headers are preserved
* MDC values are set or cleared
* request ID fallback behavior works
* null or missing context does not break processing

For MDC-related tests, clean MDC state after each test if the production code does not already do it.

Use `@AfterEach` cleanup when needed:

```java
@AfterEach
void tearDown() {
    MDC.clear();
}
```

Do not let MDC state leak between tests.

## Static Camel or project helpers

Static mocking is allowed for targeted branches, but it is not the default.

Before using `mockStatic(...)`, check whether the behavior can be tested through:

* real Exchange state
* dependency interaction
* a project test helper
* a small public seam
* direct processor output

When static mocking is necessary:

* use try-with-resources
* keep the static mock scope inside one test
* mock only the static methods required by that test
* avoid sharing static mocks through fields or setup methods
* close the mock before asserting unrelated behavior

Example pattern:

```java
try (MockedStatic<MessageHelper> messageHelper = mockStatic(MessageHelper.class)) {
    messageHelper.when(() -> MessageHelper.getBodyAsString(exchange)).thenReturn("body");

    processor.process(exchange);

    assertEquals("body", exchange.getProperty("processedBody"));
}
```

Use the actual helper class and method names from the current codebase.

## TypeConverter behavior

When the production code depends on Camel `TypeConverter`, choose the simplest reliable setup. Use project helpers when available.
Use a real converter when normal conversion behavior matters.

Mock `TypeConverter` when the test must force:

* conversion failure
* null conversion result
* a specific converted value
* fallback behavior after conversion failure

Keep converter stubs local to the test that uses them.

## Exception and fallback branches

For exception branches, assert the contract visible to callers.

Depending on the class under test, this may be:

* the thrown exception
* an exception stored on the Exchange
* an error header or property
* a fallback body
* a call to an error handler
* a load-state transition
* a log-relevant state change
* a skipped downstream dependency call

Prefer `assertThrows(...)` when the processor is expected to throw.
Prefer exchange-state assertions when the processor catches exceptions and stores error state in Camel.

## Processors that interact with `Uni`

For processors or services that interact with Mutiny `Uni`, keep the test deterministic.
When deep chained Uni calls are only infrastructure, use Mockito `RETURNS_DEEP_STUBS` where it keeps the test smaller and clearer.
Verify subscription behavior when subscribing is part of the production logic.
Ensure the correct Vert.x Mutiny Buffer import is used when relevant:

```java
import io.vertx.mutiny.core.buffer.Buffer;
```

Avoid mixing Vert.x core `Buffer` with Mutiny `Buffer`.

## Route and DSL definitions

For route-definition, DSL, or source-loading logic, test the smallest stable contract.

Prefer verifying:

* the produced route definition count
* route IDs
* selected error handler behavior
* source load state
* listener notifications
* route definitions added to the expected context
* exception handling behavior when route loading fails

Avoid testing Camel internals that can change across Camel versions.
When generated Camel classes or generated configurers are involved, avoid adding unit tests for generated code itself.
Test the project code that uses generated classes.

## Error handler and source loading tests

For error-handler factories and source-loading code, focus on the decision behavior.

Good branches to cover:

* ignore route-loading errors enabled
* ignore route-loading errors disabled
* successful source load
* failed source load
* missing route source
* listener notified on start, success, or failure
* load state changed to expected value

Prefer asserting the public result or state transition over verifying every internal logging or helper call.

## Checkpoint processor tests

For checkpoint processors, prefer:

* real Exchange where possible
* real checkpoint mapper when normal JSON/checkpoint behavior matters
* static mocking only for targeted utility branches
* focused assertions on headers, properties, checkpoint payload, and downstream calls

Read `references/project-test-helpers.md` for ObjectMapper and Exchange helpers.
Use real mapper helpers for normal behavior.
Mock mapper or static utilities only to force error/fallback branches.

## What to inspect before editing

Before adding or changing a Camel-related test:

1. read the production class
2. identify whether it is a pure processor/helper or route/runtime integration code
3. inspect existing nearby Camel tests
4. choose real Exchange or mocked Exchange intentionally
5. load `references/project-test-helpers.md` if shared helpers are needed
6. identify observable headers, properties, body, MDC state, exceptions, and dependency interactions
7. check whether static mocking can be avoided
8. keep the test focused on one behavior branch

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* deep mocking `Exchange` and `Message` when a real Exchange would work
* asserting private fields of processors
* testing Camel framework internals instead of project behavior
* using static mocks for normal Exchange/header/body behavior
* leaving MDC uncleared after tests
* using Mockito `timeout(...)` for processor logic that can be invoked directly
* starting a Camel runtime for a class that can be tested as a plain unit
* weakening assertions to make a complex mock setup pass

## Output expectations for Camel test work

When reporting Camel-related test changes, mention:

* whether a real or mocked Exchange was used
* which headers, properties, body values, or MDC state were covered
* whether static mocking was added or avoided
* which command was run
* whether any Camel/runtime behavior remains untested because it would require an integration-level test
