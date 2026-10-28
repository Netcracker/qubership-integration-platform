# Async and lifecycle testing

Read this file when Java unit test work involves:
* asynchronous behavior
* background loops
* threads
* executors
* schedulers
* timers
* reporters
* listeners

Use this reference for tests around:

* background threads
* daemon threads
* infinite or long-running loops
* `Executor` and `ExecutorService`
* `ScheduledExecutorService`
* timers and schedulers
* reporters and listeners
* start/stop lifecycle methods
* `@PostConstruct` and shutdown hooks
* Mockito `timeout(...)`
* `Thread.sleep(...)`
* `CountDownLatch`
* Mutiny `Uni`
* async callbacks and subscriptions

## Goal

Prefer deterministic async tests.

A good async unit test should:

* trigger the async behavior intentionally
* observe a specific result
* avoid waiting for real time when possible
* clean up threads, schedulers, timers, and global state
* finish consistently on a slow CI worker
* fail quickly when the expected behavior does not happen

Treat tests that depend on timing alone as flaky until proven otherwise.

## Default approach

Prefer making async behavior controllable in tests.

Good test seams include:

* injectable `Executor`
* injectable `ExecutorService`
* injectable `ScheduledExecutorService`
* injectable scheduler
* injectable clock
* direct executor for unit tests
* explicit `start()`, `stop()`, or `close()` lifecycle methods
* callback/latch synchronization
* package-visible test seam when appropriate

For test-only tasks, do not add production seams unless the user explicitly asks for production changes. If the current design makes deterministic testing difficult,
report the issue separately and suggest a small seam.

## Direct execution

When production code accepts an `Executor`, prefer a direct executor in unit tests when it preserves the behavior under test.

Example:

```java
Executor directExecutor = Runnable::run;
```

This makes the test synchronous and avoids timing assertions. Use direct execution when the test only needs to verify what the submitted task does.
Use a real executor only when concurrent execution itself is the behavior under test.

## Executor cleanup

When a test creates an executor, the test owns its cleanup.

Use `@AfterEach` cleanup:

```java
private ExecutorService executor;

@AfterEach
void tearDown() {
    if (executor != null) {
        executor.shutdownNow();
    }
}
```

For scheduled executors:

```java
private ScheduledExecutorService scheduler;

@AfterEach
void tearDown() {
    if (scheduler != null) {
        scheduler.shutdownNow();
    }
}
```

After shutdown, avoid assertions that depend on tasks continuing to run.

## Scheduled tasks

For scheduled behavior, prefer controllable scheduling over real delays.

Good options:

* inject a scheduler and capture submitted `Runnable`
* call the scheduled task directly
* use a fake scheduler
* use a direct executor when schedule timing is not the behavior
* isolate delay calculation from task execution

When testing delay calculation, assert the submitted delay and unit. When testing task behavior, invoke the captured task directly.

Example:

```java
ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

verify(scheduler).scheduleAtFixedRate(
        taskCaptor.capture(),
        eq(0L),
        eq(10L),
        eq(TimeUnit.SECONDS)
);

taskCaptor.getValue().run();

verify(reporter).report();
```

Use the exact scheduling method and argument types from the current production code.

## Background loops

For code that starts a background loop, tests should have a way to stop the loop.

Before testing a loop, inspect whether production code has:

* stop flag
* `close()`
* `stop()`
* `shutdown()`
* interruption handling
* executor shutdown
* bounded iteration mode
* lifecycle owner

If no stop mechanism exists, treat the test as high-risk. For test-only tasks, report the missing lifecycle seam instead of adding a new uncontrolled async test.

A loop test should verify:

* loop starts when expected
* one iteration performs the expected behavior
* loop stops when requested
* resources are cleaned up after the test

## Daemon threads

Daemon threads can hide test leaks.

* Avoid tests that start daemon threads without an explicit stop path.
* If existing code starts a daemon thread, verify cleanup through the public lifecycle when possible.
* If no lifecycle exists, report the issue as a testability risk.
* A test should not rely on JVM shutdown to clean up background work.

## Mockito timeout

Use Mockito `timeout(...)` only when deterministic triggering is not practical.

Prefer:

* direct invocation
* captured `Runnable`
* direct executor
* latch synchronization
* explicit lifecycle control

When `timeout(...)` is necessary:

* keep timeout small
* verify one meaningful interaction
* avoid combining it with uncontrolled infinite loops
* ensure the background worker stops after the assertion
* add cleanup in `@AfterEach` if needed

Example:

```java
verify(listener, timeout(500)).onStateChanged(expectedState);
```

Avoid using `timeout(...)` to paper over missing synchronization.

## Thread.sleep

Avoid `Thread.sleep(...)` in unit tests.

Prefer:

* `CountDownLatch`
* captured tasks
* direct executor
* fake clock
* Awaitility if the project already uses it
* explicit lifecycle methods

Use sleep only as a last resort for legacy code where no synchronization point exists. When sleep is unavoidable, keep it short and explain the limitation in the test or summary.

## CountDownLatch

Use `CountDownLatch` to wait for a specific event instead of waiting for arbitrary time.

Example:

```java
CountDownLatch latch = new CountDownLatch(1);

doAnswer(invocation -> {
    latch.countDown();
    return null;
}).when(listener).onStateChanged(expectedState);

service.start();

assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
```

Always use bounded timeouts. After the latch completes or times out, stop the async component.

## Mutiny Uni

For code that uses Mutiny `Uni`, keep the test deterministic. Use real `Uni` instances when possible:

```java
Uni<String> uni = Uni.createFrom().item("value");
```

Use failed `Uni` instances for failure branches:

```java
Uni<String> uni = Uni.createFrom().failure(new RuntimeException("failure"));
```

When deep chained `Uni` calls are only infrastructure, use Mockito `RETURNS_DEEP_STUBS` if it keeps the test smaller and clearer. Verify subscription behavior when subscribing is part of the production logic.

Good branches to cover:

* successful item
* failure
* null item if supported by the code path
* fallback branch
* subscription happens
* subscription is skipped when preconditions are missing

If Vert.x Mutiny `Buffer` is involved, use the Mutiny import:

```java
import io.vertx.mutiny.core.buffer.Buffer;
```

Avoid mixing Vert.x core `Buffer` with Mutiny `Buffer`.

## Reporters and listeners

For reporter/listener services, prefer testing one iteration or one event.

Good behaviors to cover:

* listener is notified on start
* listener is notified on success
* listener is notified on failure
* reporter builds expected state
* reporter skips reporting when disabled
* reporter handles dependency failure
* reporter stops cleanly
* repeated reporting does not leak state

For a reporter loop, separate tests into:

* scheduling/lifecycle behavior
* one reporting iteration
* error handling branch
* shutdown behavior

Avoid one large test that starts the full loop and waits for multiple real intervals.

## Lifecycle cleanup

Any test that starts async work should clean it up.

Cleanup may include:

* calling `stop()`
* calling `close()`
* shutting down executor
* shutting down scheduler
* interrupting worker thread through public lifecycle
* clearing MDC
* clearing static state
* restoring system properties

Use `@AfterEach` when cleanup must happen even after assertion failures.

Example:

```java
private EngineStateReporter reporter;

@AfterEach
void tearDown() {
    if (reporter != null) {
        reporter.stop();
    }
}
```

Use the actual lifecycle method from the current production class.

## MDC and context in async tests

Async code can leak MDC or context state between tests.

When async code touches MDC:

* assert expected MDC behavior in the executing thread when possible
* clear MDC after the test
* avoid assuming MDC propagates across threads unless that propagation is the behavior under test
* verify context snapshot or propagation objects instead of thread-local internals when possible

Cleanup:

```java
@AfterEach
void tearDown() {
    MDC.clear();
}
```

## Shared state and parallel test safety

Assume tests may eventually run in parallel.

Async tests should avoid shared mutable state:

* static counters
* static flags
* shared executors
* shared schedulers
* shared listeners
* shared system properties
* shared MDC state
* shared temporary files

If shared state is unavoidable, isolate it and restore it. Use resource locks or same-thread execution only when the test genuinely requires global state.

## Refactoring recommendations

When async code is hard to test, suggest a small seam.

Useful seams:

* inject `Executor`
* inject `ScheduledExecutorService`
* inject `Clock`
* inject scheduler/factory
* expose `stop()` or `close()`
* split one loop iteration into a package-visible method
* extract task body into a collaborator
* return a lifecycle handle from `start()`

For test-only tasks, report these recommendations without changing production code. For explicit refactoring tasks, keep the seam small and preserve existing public behavior.

## Good async coverage targets

Good branches to cover:

* async task submitted
* async task skipped when disabled
* scheduled task registered
* one scheduled task iteration succeeds
* one scheduled task iteration fails
* dependency failure is handled
* listener receives success event
* listener receives failure event
* service starts once
* service stops cleanly
* repeated stop is safe
* executor is shut down
* scheduler is shut down
* `Uni` success branch
* `Uni` failure branch
* timeout/fallback branch

Avoid tests that only start async code without asserting a meaningful result.

## What to inspect before editing

Before adding or changing an async or lifecycle test:

1. read the production class
2. identify where async work starts
3. identify how async work stops
4. inspect whether executors, schedulers, timers, or threads are created directly
5. check whether the task body can be invoked directly
6. check whether a direct executor or captured `Runnable` can make the test deterministic
7. inspect existing nearby tests for cleanup patterns
8. identify MDC, static state, system properties, and other thread-local/global state
9. plan cleanup before editing
10. choose the smallest behavior branch to cover

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* `Thread.sleep(...)` as synchronization
* Mockito `timeout(...)` as the first choice
* starting an infinite loop without a stop path
* leaving daemon threads running after the test
* creating executors without shutdown
* testing multiple real timer intervals
* relying on CI timing
* asserting behavior before async work has a synchronization point
* leaking MDC or system properties between tests
* using broad integration-style async tests for simple task-body logic

## Output expectations for async test work

When reporting async or lifecycle test changes, mention:

* what async behavior was covered
* whether the test uses direct execution, captured task, latch, scheduler, or timeout
* how threads, executors, schedulers, MDC, or global state are cleaned up
* whether a lifecycle or testability seam is still missing
* which command was run
* any remaining flakiness risk
