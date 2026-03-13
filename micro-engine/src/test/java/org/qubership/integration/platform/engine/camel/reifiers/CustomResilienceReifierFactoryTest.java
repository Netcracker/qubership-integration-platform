package org.qubership.integration.platform.engine.camel.reifiers;

import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomResilienceReifierFactoryTest {

    @Test
    void shouldCreateProcessorUsingCustomResilienceReifier() throws Exception {
        CustomResilienceReifierFactory factory = new CustomResilienceReifierFactory();
        Route route = mock(Route.class);
        CircuitBreakerDefinition definition = mock(CircuitBreakerDefinition.class);
        Processor processor = mock(Processor.class);

        try (MockedConstruction<CustomResilienceReifier> construction = mockConstruction(
                CustomResilienceReifier.class,
                (mock, context) -> when(mock.createProcessor()).thenReturn(processor)
        )) {
            Processor result = factory.doCreateProcessor(route, definition);

            assertSame(processor, result);
            assertSame(1, construction.constructed().size());
        }
    }

    @Test
    void shouldPropagateExceptionWhenCustomResilienceReifierFailsToCreateProcessor() throws Exception {
        CustomResilienceReifierFactory factory = new CustomResilienceReifierFactory();
        Route route = mock(Route.class);
        CircuitBreakerDefinition definition = mock(CircuitBreakerDefinition.class);
        Exception exception = new Exception("boom");

        try (MockedConstruction<CustomResilienceReifier> construction = mockConstruction(
                CustomResilienceReifier.class,
                (mock, context) -> when(mock.createProcessor()).thenThrow(exception)
        )) {
            Exception thrown = assertThrows(
                    Exception.class,
                    () -> factory.doCreateProcessor(route, definition)
            );

            assertSame(exception, thrown);
            assertSame(1, construction.constructed().size());
        }
    }
}
