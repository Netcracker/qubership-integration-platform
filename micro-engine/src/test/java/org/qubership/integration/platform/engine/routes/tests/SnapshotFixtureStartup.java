package org.qubership.integration.platform.engine.routes.tests;

import org.qubership.integration.platform.engine.routes.fixture.SnapshotFixture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class SnapshotFixtureStartup {
    static final String TIMEOUT_PROPERTY = "route.contract.setup.timeout.seconds";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotFixtureStartup.class);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private final Duration timeout;
    private final Duration cleanupTimeout;

    SnapshotFixtureStartup(Duration timeout, Duration cleanupTimeout) {
        this.timeout = requirePositive(timeout);
        this.cleanupTimeout = requirePositive(cleanupTimeout);
    }

    static SnapshotFixtureStartup fromSystemProperties() {
        String configured = System.getProperty(TIMEOUT_PROPERTY);
        Duration timeout = DEFAULT_TIMEOUT;
        if (configured != null) {
            try {
                timeout = requirePositive(Duration.ofSeconds(Long.parseLong(configured.strip())));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw new IllegalArgumentException("System property '" + TIMEOUT_PROPERTY
                        + "' must be a positive integer number of seconds, but was '" + configured + "'.", exception);
            }
        }
        return new SnapshotFixtureStartup(timeout, CLEANUP_TIMEOUT);
    }

    void start(List<SnapshotFixture> fixtures, String scenarioName) throws Exception {
        if (fixtures.isEmpty()) {
            return;
        }
        List<SnapshotFixture> ownedFixtures = List.copyOf(fixtures);
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<String> stage = new AtomicReference<>("starting fixtures");
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "snapshot-fixture-startup");
            thread.setContextClassLoader(contextClassLoader);
            thread.setDaemon(true);
            return thread;
        });
        Future<?> startup = executor.submit(() -> {
            for (SnapshotFixture fixture : ownedFixtures) {
                if (abandoned.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Snapshot fixture startup was canceled.");
                }
                stage.set("starting fixture for deployment '" + fixture.getDeploymentId() + "'");
                LOG.info("Snapshot {}: {}.", scenarioName, stage.get());
                fixture.start();
            }
            return null;
        });
        try {
            try {
                startup.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                throw new AssertionError("Snapshot " + scenarioName + " setup timed out after "
                        + timeout.toMillis() + " ms while " + stage.get() + ".", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof Exception startupFailure) {
                    throw startupFailure;
                }
                if (exception.getCause() instanceof Error startupFailure) {
                    throw startupFailure;
                }
                throw new IllegalStateException("Snapshot fixture startup failed.", exception.getCause());
            }
        } catch (Exception | Error failure) {
            abandoned.set(true);
            startup.cancel(true);
            // Cleanup follows startup on the same worker, even when Docker ignores interruption.
            Future<?> cleanup = executor.submit(() -> {
                closeFixtures(ownedFixtures);
                return null;
            });
            executor.shutdown();
            try {
                cleanup.get(cleanupTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                failure.addSuppressed(new IllegalStateException("Snapshot " + scenarioName
                        + " fixture cleanup is still pending after " + cleanupTimeout.toMillis()
                        + " ms; the startup worker retains ownership until cleanup finishes.", exception));
            } catch (ExecutionException exception) {
                failure.addSuppressed(exception.getCause());
            } catch (InterruptedException exception) {
                failure.addSuppressed(exception);
                Thread.currentThread().interrupt();
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        } finally {
            executor.shutdown();
        }
    }

    private static void closeFixtures(List<SnapshotFixture> fixtures) throws Exception {
        Throwable failure = null;
        for (int index = fixtures.size() - 1; index >= 0; index--) {
            try {
                fixtures.get(index).close();
            } catch (Exception | AssertionError exception) {
                LOG.warn("Snapshot fixture cleanup failed for deployment {}.",
                        fixtures.get(index).getDeploymentId(), exception);
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof AssertionError assertionError) {
            throw assertionError;
        }
    }

    private static Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Snapshot fixture timeout must be positive.");
        }
        duration.toNanos();
        return duration;
    }
}
