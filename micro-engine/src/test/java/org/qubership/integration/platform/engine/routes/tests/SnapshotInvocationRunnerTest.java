package org.qubership.integration.platform.engine.routes.tests;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SnapshotInvocationRunnerTest {
    @Mock
    private ExecutorService executor;
    @Mock
    private Future<String> future;
    @Mock
    private SnapshotInvocationRunner.TimeoutCleanup cleanup;

    @ParameterizedTest
    @ValueSource(ints = {5, 120})
    void shouldRestoreConfiguredTimeoutForFollowingTasksWhenInvocationOverridesIt(int timeoutSeconds) throws Exception {
        Duration configuredTimeout = Duration.ofSeconds(45);
        Duration terminationTimeout = Duration.ofSeconds(5);
        Callable<String> invocation = () -> "completed";
        when(executor.submit(invocation)).thenReturn(future);
        when(future.get(Duration.ofSeconds(timeoutSeconds).toNanos(), TimeUnit.NANOSECONDS)).thenReturn("overridden");
        when(future.get(configuredTimeout.toNanos(), TimeUnit.NANOSECONDS)).thenReturn("configured");
        when(executor.awaitTermination(terminationTimeout.toNanos(), TimeUnit.NANOSECONDS)).thenReturn(true);

        try (SnapshotInvocationRunner runner = new SnapshotInvocationRunner(
                configuredTimeout, terminationTimeout, executor)) {
            assertEquals("overridden", runner.execute(timeoutSeconds, () -> "broker restart", invocation, cleanup));
            assertEquals("configured", runner.execute(null, () -> "next invocation", invocation, cleanup));
            assertEquals("configured", runner.execute(() -> "fixture verification", invocation, cleanup));
            assertEquals(0, runner.getSubmittedInvocationCount());
        }

        verify(future).get(Duration.ofSeconds(timeoutSeconds).toNanos(), TimeUnit.NANOSECONDS);
        verify(future, times(2)).get(configuredTimeout.toNanos(), TimeUnit.NANOSECONDS);
        verify(future, never()).cancel(true);
        verifyNoInteractions(cleanup);
        verify(executor).shutdownNow();
    }

    @Test
    void shouldReportInvocationTimeoutAndAbortWhenVerificationTimesOut() throws Exception {
        Duration terminationTimeout = Duration.ofSeconds(5);
        Callable<String> invocation = () -> "completed";
        TimeoutException timeout = new TimeoutException("broker restart did not finish");
        IOException cleanupFailure = new IOException("cleanup failed");
        when(executor.submit(invocation)).thenReturn(future);
        when(future.get(Duration.ofSeconds(120).toNanos(), TimeUnit.NANOSECONDS)).thenThrow(timeout);
        when(executor.awaitTermination(terminationTimeout.toNanos(), TimeUnit.NANOSECONDS)).thenReturn(true);
        doThrow(cleanupFailure).when(cleanup).run();

        try (SnapshotInvocationRunner runner = new SnapshotInvocationRunner(
                SnapshotInvocationRunner.DEFAULT_TIMEOUT, terminationTimeout, executor)) {
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> runner.execute(120, () -> "Invocation verification", invocation, cleanup));

            assertEquals("Invocation verification timed out after 120000 ms.", failure.getMessage());
            assertSame(timeout, failure.getCause());
            assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
            assertEquals(0, runner.getSubmittedInvocationCount());
            IllegalStateException closed = assertThrows(IllegalStateException.class,
                    () -> runner.execute(() -> "next invocation", invocation, cleanup));
            assertEquals("Snapshot invocation runner is closed.", closed.getMessage());
        }

        verify(future, atLeastOnce()).cancel(true);
        verify(executor).shutdownNow();
        verify(cleanup).run();
        verify(executor).awaitTermination(terminationTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
