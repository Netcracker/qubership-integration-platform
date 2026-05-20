package org.qubership.integration.platform.engine.consul;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.engine.EngineState;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.state.EngineStateReporter;
import org.qubership.integration.platform.engine.state.EngineStateService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineStateReporterTest {

    private EngineStateReporter reporter;

    @Mock
    EngineStateService engineStateService;

    @Mock
    MetricsService metricsService;

    @Test
    void shouldAddStateToQueueWhenQueueHasCapacity() {
        reporter = new TestEngineStateReporter(engineStateService, metricsService);
        EngineState state = mock(EngineState.class);

        reporter.addStateToQueue(state);

        BlockingQueue<EngineState> queue = statesQueue(reporter);
        assertEquals(1, queue.size());
        assertSame(state, queue.peek());
    }

    @Test
    void shouldNotAddStateToQueueWhenQueueIsFull() {
        reporter = new TestEngineStateReporter(engineStateService, metricsService);

        for (int i = 0; i < EngineStateReporter.QUEUE_CAPACITY; i++) {
            reporter.addStateToQueue(mock(EngineState.class));
        }

        EngineState extraState = mock(EngineState.class);

        reporter.addStateToQueue(extraState);

        BlockingQueue<EngineState> queue = statesQueue(reporter);
        assertEquals(EngineStateReporter.QUEUE_CAPACITY, queue.size());
        assertFalse(queue.contains(extraState));
    }

    @Test
    void shouldUpdateStateAndDeploymentMetricsWhenStateTakenFromQueue() {
        reporter = new TestEngineStateReporter(engineStateService, metricsService);

        EngineState state = mock(EngineState.class);
        EngineDeployment firstDeployment = mock(EngineDeployment.class);
        EngineDeployment secondDeployment = mock(EngineDeployment.class);

        when(state.getDeployments()).thenReturn(Map.of(
                "first", firstDeployment,
                "second", secondDeployment
        ));

        reporter.addStateToQueue(state);
        startReporterLoop(reporter);

        verify(engineStateService, timeout(1000)).updateState(state);
        verify(metricsService, timeout(1000)).processChainsDeployments(firstDeployment);
        verify(metricsService, timeout(1000)).processChainsDeployments(secondDeployment);
    }

    @Test
    void shouldRetryReportingWhenUpdateStateFailsOnce() {
        reporter = new TestEngineStateReporter(engineStateService, metricsService);

        EngineState state = mock(EngineState.class);
        when(state.getDeployments()).thenReturn(Map.of());

        doThrow(new RuntimeException("Failed to report"))
                .doNothing()
                .when(engineStateService)
                .updateState(state);

        reporter.addStateToQueue(state);
        startReporterLoop(reporter);

        verify(engineStateService, timeout(EngineStateReporter.REPORT_RETRY_DELAY + 2000).times(2))
                .updateState(state);
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<EngineState> statesQueue(EngineStateReporter reporter) {
        try {
            Field field = EngineStateReporter.class.getDeclaredField("statesQueue");
            field.setAccessible(true);
            return (BlockingQueue<EngineState>) field.get(reporter);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void startReporterLoop(EngineStateReporter reporter) {
        Thread thread = new Thread(reporter::run);
        thread.setDaemon(true);
        thread.start();
    }

    private static class TestEngineStateReporter extends EngineStateReporter {

        TestEngineStateReporter(
                EngineStateService engineStateService,
                MetricsService metricsService
        ) {
            super(engineStateService, metricsService);
        }

        @Override
        public synchronized void start() {
        }
    }
}
