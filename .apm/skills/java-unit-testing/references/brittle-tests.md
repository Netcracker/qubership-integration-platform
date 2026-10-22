# Brittle test handling

Read this file when Java unit test work involves brittle, implementation-coupled, reflection-heavy, static-mocking-heavy, or flaky tests.

Use this reference when existing or planned tests involve:

* `mockStatic(...)`
* `mockConstruction(...)`
* `getDeclaredField(...)`
* `setAccessible(true)`
* assertions against private fields
* assertions against third-party internal builder state
* direct inspection of implementation details
* large mock graphs
* tests that fail after harmless refactoring
* tests that verify internal steps instead of public behavior
* tests that need production seams to become maintainable

## Goal

Prefer tests that verify public behavior and observable contracts. A good unit test should survive reasonable internal refactoring when external behavior remains unchanged.
The test should explain what the class promises, not how the class happens to implement that promise today.

## Warning signs

Treat these patterns as maintainability risks:

* excessive `mockStatic(...)`
* reflection-based access to private fields or methods
* `setAccessible(true)`
* assertions against private fields
* assertions against internal third-party builder state
* constructor mocking for normal happy-path behavior
* verifying every internal helper call
* deep mock chains that mirror implementation structure
* tests that require changing private field names or private method names
* broad test setup that makes simple behavior hard to understand

A warning sign does not automatically mean the test must be rewritten. It means the agent should check whether a more stable behavior-level test is practical.

## Preferred assertion targets

Before using reflection or static mocking, check whether the same behavior can be verified through observable state.

Prefer assertions on:

* returned values
* thrown exceptions
* public object state
* produced configuration objects
* produced clients or factories
* dependency interactions through public collaborators
* emitted events
* persisted or published state
* Camel `Exchange` headers, properties, body, or exception state
* registry/cache hit or miss behavior
* listener notifications
* fallback behavior
* skipped downstream calls

Example preference:

```java
Result result = service.process(input);

assertEquals("expected", result.status());
```

Prefer this over asserting a private field that happened to store the status during processing.

## Reflection policy

Reflection is a last resort.

Use reflection only when:

* no stable public behavior exists
* the branch is important enough to cover
* the user explicitly wants legacy coverage for that branch
* a production seam is out of scope for the task

When reflection is unavoidable:

* keep reflection in a small helper method
* read only the minimum required internal state
* avoid coupling to several private fields at once
* explain why public behavior was not enough in the final summary
* preserve existing reflection tests unless the task is specifically to improve maintainability

Prefer replacing reflection checks with public behavior checks during maintainability work.

## Private field assertions

Private field assertions are usually a sign that the test is verifying implementation instead of contract.

Before asserting a private field, ask:

* What public behavior changes when this field changes?
* Can that behavior be observed through a method result?
* Can it be observed through a produced object?
* Can it be observed through a dependency call?
* Can it be observed through Exchange state, emitted event, or stored result?
* Would a small test seam be cleaner?

Use private field assertions only for legacy code where no stable public signal exists and the user has asked for that coverage.

## Static mocking policy

Static mocking is allowed, but it is not the default.

Before using `mockStatic(...)`, check whether one of these options is cleaner:

* use a real object
* use an existing project test helper
* test through public behavior
* inject a collaborator
* extract static access behind a small wrapper/provider
* pass a controllable factory into the class under test

When static mocking is necessary:

* use try-with-resources
* keep the scope inside one test
* mock only the static method required by that branch
* avoid combining unrelated static mocks in one test
* verify only meaningful behavior
* close the static mock before unrelated assertions
* keep stubs local to the test that needs them

Example pattern:

```java
try (MockedStatic<SomeUtility> utility = mockStatic(SomeUtility.class)) {
    utility.when(() -> SomeUtility.resolve("input")).thenReturn("resolved");

    String result = service.process("input");

    assertEquals("resolved", result);
}
```

Use the actual utility class and method names from the current codebase.

## Constructor mocking policy

Constructor mocking is useful for legacy code that directly creates hard-to-control collaborators.

Before using `mockConstruction(...)`, check whether the behavior can be tested through:

* a real lightweight object
* an injected factory
* a public dependency interaction
* produced output
* configuration object state
* a smaller public seam

When construction mocking is necessary:

* keep the construction scope inside one test
* mock only the constructed type required by the branch
* assert the externally visible result
* avoid verifying every constructor argument unless constructor selection is the behavior under test
* prefer a small factory seam for future refactoring if this pattern repeats

Example pattern:

```java
try (MockedConstruction<SomeClient> clients = mockConstruction(SomeClient.class,
        (client, context) -> when(client.call()).thenReturn("value"))) {
    String result = service.load();

    assertEquals("value", result);
    assertEquals(1, clients.constructed().size());
}
```

Use construction mocking for targeted branches, not as the default happy-path setup.

## Third-party internals

Avoid asserting private internals of third-party classes. This includes internal state of builders, clients, filters, and framework objects.

Prefer verifying:

* public configuration output
* public options exposed by the object
* effect on a request or response
* interaction with an injected factory or client
* produced wrapper behavior
* exception or fallback branch
* externally visible side effect

For builder-style APIs, prefer testing that the project code calls a wrapper/factory with expected inputs instead of reflecting into the built third-party object.
If the only possible assertion requires third-party internals, report that the code would benefit from a small wrapper or factory seam.

## Large mock graphs

Large mock graphs often mirror implementation too closely.

Before adding deep mocks, check whether the test can use:

* a real lightweight domain object
* a project helper
* a fixture builder
* a smaller collaborator mock
* a public result assertion
* a focused fake implementation

Prefer one or two meaningful mocks over a full tree of mocks. If a mock graph becomes hard to understand,
the test likely needs either a real object or a small production seam.

## Design seams

When code is hard to test, suggest a small seam instead of adding brittle tests.

Useful seams include:

* injectable wrapper for static/global access
* injectable factory for directly constructed clients or builders
* injectable provider for environment, hostname, system properties, or time
* injectable `Clock`
* injectable `Executor` or `ScheduledExecutorService`
* explicit lifecycle method such as `stop()` or `close()`
* package-visible method for one loop iteration
* small adapter around third-party internals

For test-only tasks, report the seam recommendation without changing production code.
For explicit refactoring tasks, keep the seam small and preserve public behavior.

## Existing brittle tests

When editing existing brittle tests, choose the smallest safe improvement.

Possible outcomes:

* leave the brittle test unchanged because the task is unrelated
* add new behavior-level tests next to existing brittle tests
* replace reflection with public behavior assertions
* replace static mocking with real objects or project helpers
* reduce static mock scope
* move repeated reflection into one local helper
* add cleanup for static/global state
* report a seam recommendation for later refactoring

Preserve working legacy tests unless the task is specifically to improve maintainability.
Do not rewrite a large brittle test class only because it looks ugly. Keep changes focused and reviewable.

## Maintainability findings

When analysis finds brittle tests, classify findings separately from normal test changes.

Use these categories:

* can be improved inside tests only
* requires a small production seam
* requires larger design refactoring
* not worth changing now

Report the finding with:

* class name
* brittle pattern
* risk
* suggested improvement
* whether production changes are required

Example:

```text
GraphqlCustomEndpointTest verifies internal HttpClientBuilder state through reflection. This can break after a harmless builder refactoring. A small client factory seam would let the test verify public configuration behavior instead.
```

Use actual class names from the current codebase.

## Coverage work and brittle branches

Coverage pressure should not produce fragile tests.

When a missed branch is hard to cover without reflection or static mocking:

1. check for a public behavior path first
2. check for existing project helpers
3. check whether a real object can reach the branch
4. use static mocking only for targeted error/fallback branches
5. report a seam recommendation if the branch is only reachable through internals

Prefer meaningful branch coverage over line execution.

Good brittle-risk branches to cover carefully:

* static utility failure
* directly constructed client failure
* hostname lookup failure
* system property fallback
* third-party builder configuration
* background loop stop behavior
* registry/cache miss with static access
* legacy private-state transition

Each test should assert the resulting public behavior.

## What to inspect before editing

Before adding or changing brittle tests:

1. read the production class
2. read the existing test class
3. identify the public contract
4. identify which internals the current test depends on
5. check whether project helpers already cover the setup
6. check whether real objects can replace mocks
7. check whether reflection can be replaced with public assertions
8. check whether static mocking can be scoped or avoided
9. check whether async/global resources need cleanup
10. plan the smallest safe change

If the test involves async behavior, read `references/async-lifecycle-testing.md`.
If the test involves Camel `Exchange`, read `references/camel-testing.md`.
If the test involves configuration objects or system properties, read `references/config-producer-customizer-testing.md`.

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* adding reflection because it is faster than finding the public contract
* using static mocks for normal happy-path behavior
* verifying implementation steps instead of outputs
* asserting third-party private fields
* building a deep mock graph before trying real objects
* changing production code during a test-only task
* weakening assertions to make a brittle setup pass
* rewriting a whole test class when a focused change is enough
* leaving static mocks, system properties, MDC, executors, or schedulers dirty after the test

## Output expectations for brittle test work

When reporting brittle-test work, mention:

* which brittle pattern was found
* whether it was kept, reduced, or replaced
* what public behavior is now covered
* whether static mocking, reflection, or construction mocking remains
* whether cleanup was added for global or async state
* whether a production seam is recommended
* which command was run
* any remaining maintainability risk
