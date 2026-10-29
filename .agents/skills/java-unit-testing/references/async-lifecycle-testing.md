# Async and lifecycle testing

Read this file when the micro-engine class under test starts a thread, an executor, or a scheduler, or returns a Mutiny
`Uni`. A test that depends on timing alone is flaky until proven otherwise.

## Run the task body directly

Make the async part synchronous instead of waiting for it.

* When the class accepts an `Executor`, pass `Runnable::run`. Use a real executor only when concurrency is the
  behavior under test.
* When the class schedules work, capture the task and run it:

```java
ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
verify(scheduler).scheduleAtFixedRate(task.capture(), eq(0L), eq(10L), eq(TimeUnit.SECONDS));

task.getValue().run();

verify(reporter).report();
```

Assert the delay and unit in one test, and the task body in another. Use the scheduling method and argument types the
production code calls.

## Waiting for a real thread

Where no direct path exists, wait on a bounded `CountDownLatch`, or on Mockito `timeout(...)` for one interaction. Do
not use `Thread.sleep(...)` as synchronization: it is slow on a fast machine and flaky on a slow CI worker.

```java
CountDownLatch latch = new CountDownLatch(1);
doAnswer(invocation -> {
    latch.countDown();
    return null;
}).when(listener).onStateChanged(expectedState);

service.start();

assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
```

## Stopping what the test started

The test that creates an executor, a scheduler, or a worker shuts it down in `@AfterEach`, so cleanup runs after a
failed assertion too: `shutdownNow()` for an executor, and the class's own `stop()` or `close()` for a worker.

Some classes have no stop path. `EngineStateReporter` extends `Thread`, starts itself in its constructor, and swallows
the `InterruptedException` from `take()` inside an endless loop, so `interrupt()` does not stop it while it waits for a
state. `Thread.stop()` throws `UnsupportedOperationException` on Java 21. Do not unit-test such a loop by starting it.
Test what one iteration calls, such as `EngineStateService.updateState(...)`, and name the missing lifecycle seam in
the summary.

MDC cleanup is in `references/camel-testing.md`; system property cleanup is in
`references/config-producer-customizer-testing.md`.

## Mutiny `Uni`

Build real `Uni` instances: `Uni.createFrom().item("value")` for success and
`Uni.createFrom().failure(new RuntimeException("failure"))` for the failure branch. When a chain of `Uni` calls is only
infrastructure, `RETURNS_DEEP_STUBS` can keep the test short. Where subscribing is part of the production logic, verify
that the subscription happens, or that it is skipped when a precondition is missing.

For a Vert.x buffer, import `io.vertx.mutiny.core.buffer.Buffer`, not the Vert.x core `Buffer`.
