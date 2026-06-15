package org.qubership.integration.platform.engine.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextCustomizerTest {

    @Mock
    private MicrometerObservationTracer tracer;

    @Mock
    private CamelDebugger debugger;

    @Mock
    private CamelContext camelContext;

    private ContextCustomizer contextCustomizer;

    @BeforeEach
    void setUp() {
        contextCustomizer = new ContextCustomizer();
        contextCustomizer.tracer = tracer;
        contextCustomizer.debugger = debugger;
    }

    @Test
    void shouldInitializeTracerAndConfigureDebugger() {
        contextCustomizer.configure(camelContext);

        InOrder inOrder = inOrder(tracer, camelContext);
        inOrder.verify(tracer).init(camelContext);
        inOrder.verify(camelContext).setDebugger(debugger);
        inOrder.verify(camelContext).setDebugging(true);
    }

    @Test
    void shouldPropagateExceptionWhenTracerInitializationFails() {
        RuntimeException exception = new RuntimeException("Failed to initialize tracer");

        doThrow(exception).when(tracer).init(camelContext);

        RuntimeException result = assertThrows(RuntimeException.class, () -> contextCustomizer.configure(camelContext));

        assertSame(exception, result);
        verifyNoInteractions(debugger);
        verify(camelContext, org.mockito.Mockito.never()).setDebugger(debugger);
        verify(camelContext, org.mockito.Mockito.never()).setDebugging(true);
    }

    @Test
    void shouldPropagateExceptionWhenDebuggerSetupFails() {
        RuntimeException exception = new RuntimeException("Failed to set debugger");

        doThrow(exception).when(camelContext).setDebugger(debugger);

        RuntimeException result = assertThrows(RuntimeException.class, () -> contextCustomizer.configure(camelContext));

        assertSame(exception, result);
        verify(tracer).init(camelContext);
        verify(camelContext, org.mockito.Mockito.never()).setDebugging(true);
    }
}
