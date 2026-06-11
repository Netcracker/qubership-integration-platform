package org.qubership.integration.platform.engine.camel.listeners;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.event.CamelContextStartedEvent;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.engine.EngineState;
import org.qubership.integration.platform.engine.state.EngineStateBuilder;
import org.qubership.integration.platform.engine.state.EngineStateReporter;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineStateUpdatingListenerTest {

    @Mock
    private EngineStateBuilder engineStateBuilder;

    @Mock
    private EngineStateReporter engineStateReporter;

    @Mock
    private CamelContext camelContext;

    @Mock
    private EngineState engineState;

    @Mock
    private CamelEvent camelEvent;

    private EngineStateUpdatingListener listener;

    @BeforeEach
    void setUp() {
        listener = new EngineStateUpdatingListener();
        listener.engineStateBuilder = engineStateBuilder;
        listener.engineStateReporter = engineStateReporter;
    }

    @Test
    void shouldBuildAndReportEngineStateWhenCamelContextStarted() throws Exception {
        CamelContextStartedEvent event = new CamelContextStartedEvent(camelContext);

        when(engineStateBuilder.build(camelContext)).thenReturn(engineState);

        listener.notify(event);

        verify(engineStateBuilder).build(camelContext);
        verify(engineStateReporter).addStateToQueue(engineState);
    }

    @Test
    void shouldIgnoreUnsupportedCamelEvent() throws Exception {
        listener.notify(camelEvent);

        verifyNoInteractions(engineStateBuilder, engineStateReporter);
    }

    @Test
    void shouldPropagateExceptionWhenEngineStateBuildFails() {
        CamelContextStartedEvent event = new CamelContextStartedEvent(camelContext);
        RuntimeException exception = new RuntimeException("Failed to build engine state");

        when(engineStateBuilder.build(camelContext)).thenThrow(exception);

        RuntimeException result = assertThrows(RuntimeException.class, () -> listener.notify(event));

        assertSame(exception, result);
        verifyNoInteractions(engineStateReporter);
    }

    @Test
    void shouldPropagateExceptionWhenEngineStateReportingFails() {
        CamelContextStartedEvent event = new CamelContextStartedEvent(camelContext);
        RuntimeException exception = new RuntimeException("Failed to report engine state");

        when(engineStateBuilder.build(camelContext)).thenReturn(engineState);
        doThrow(exception).when(engineStateReporter).addStateToQueue(engineState);

        RuntimeException result = assertThrows(RuntimeException.class, () -> listener.notify(event));

        assertSame(exception, result);
        verify(engineStateBuilder).build(camelContext);
    }
}
