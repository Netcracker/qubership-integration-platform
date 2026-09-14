package org.qubership.integration.platform.engine.routes.tests;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

final class SnapshotInvocationRunner implements AutoCloseable {
    static final String TIMEOUT_PROPERTY = "route.contract.invocation.timeout.seconds";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

    private final Duration timeout;
    private final Duration terminationTimeout;
    private final ExecutorService executor;
    private final Object lifecycleMonitor = new Object();
    private final Set<Future<?>> submittedInvocations = new HashSet<>();
    private boolean closed;

    SnapshotInvocationRunner(Duration timeout) {
        this(timeout, DEFAULT_TERMINATION_TIMEOUT);
    }

    SnapshotInvocationRunner(Duration timeout, Duration terminationTimeout) {
        this.timeout = requirePositive(timeout, "Invocation timeout");
        this.terminationTimeout = requirePositive(terminationTimeout, "Invocation termination timeout");
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapshot-invocation");
            thread.setContextClassLoader(contextClassLoader);
            thread.setDaemon(true);
            return thread;
        });
    }

    static SnapshotInvocationRunner fromSystemProperties() {
        String configuredTimeout = System.getProperty(TIMEOUT_PROPERTY);
        if (configuredTimeout == null) {
            return new SnapshotInvocationRunner(DEFAULT_TIMEOUT);
        }

        long timeoutSeconds;
        try {
            timeoutSeconds = Long.parseLong(configuredTimeout.strip());
        } catch (NumberFormatException exception) {
            throw invalidTimeoutProperty(configuredTimeout, exception);
        }
        if (timeoutSeconds <= 0) {
            throw invalidTimeoutProperty(configuredTimeout, null);
        }
        return new SnapshotInvocationRunner(Duration.ofSeconds(timeoutSeconds));
    }

    <T> T execute(
            Supplier<String> executionName,
            Callable<T> invocation,
            TimeoutCleanup timeoutCleanup
    ) throws Exception {
        Objects.requireNonNull(executionName, "executionName");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(timeoutCleanup, "timeoutCleanup");
        Future<T> future = submit(invocation);
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            AssertionError timeoutFailure = new AssertionError(
                    executionName.get() + " timed out after " + timeout.toMillis() + " ms.",
                    exception
            );
            cancelAndCleanUp(future, timeoutCleanup, timeoutFailure);
            throw timeoutFailure;
        } catch (InterruptedException exception) {
            cancelAndCleanUp(future, timeoutCleanup, exception);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception invocationException) {
                throw invocationException;
            }
            if (cause instanceof Error invocationError) {
                throw invocationError;
            }
            throw new IllegalStateException(executionName.get() + " failed with an unsupported throwable.", cause);
        } finally {
            forget(future);
        }
    }

    Duration getTimeout() {
        return timeout;
    }

    int getSubmittedInvocationCount() {
        synchronized (lifecycleMonitor) {
            return submittedInvocations.size();
        }
    }

    @Override
    public void close() {
        if (!stopExecutor()) {
            return;
        }
        try {
            if (!executor.awaitTermination(terminationTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "Snapshot invocation worker did not stop within "
                                + terminationTimeout.toMillis() + " ms."
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping the snapshot invocation worker.",
                    exception
            );
        }
    }

    private void cancelAndCleanUp(
            Future<?> future,
            TimeoutCleanup timeoutCleanup,
            Throwable primaryFailure
    ) {
        future.cancel(true);
        stopExecutor();
        try {
            timeoutCleanup.run();
        } catch (Exception cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }

        try {
            if (!executor.awaitTermination(terminationTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                primaryFailure.addSuppressed(new IllegalStateException(
                        "Snapshot invocation worker did not stop within "
                                + terminationTimeout.toMillis() + " ms."
                ));
            }
        } catch (InterruptedException interruptedCleanup) {
            Thread.currentThread().interrupt();
            primaryFailure.addSuppressed(interruptedCleanup);
        }
    }

    private <T> Future<T> submit(Callable<T> invocation) {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("Snapshot invocation runner is closed.");
            }
            Future<T> future = executor.submit(invocation);
            submittedInvocations.add(future);
            return future;
        }
    }

    private void forget(Future<?> future) {
        synchronized (lifecycleMonitor) {
            submittedInvocations.remove(future);
        }
    }

    private boolean stopExecutor() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return false;
            }
            closed = true;
            submittedInvocations.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            return true;
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large.", exception);
        }
        return value;
    }

    private static IllegalArgumentException invalidTimeoutProperty(
            String configuredTimeout,
            Exception cause
    ) {
        String message = "System property '" + TIMEOUT_PROPERTY
                + "' must be a positive integer number of seconds, but was '"
                + configuredTimeout + "'.";
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }

    @FunctionalInterface
    interface TimeoutCleanup {
        void run() throws Exception;
    }
}
