package org.qubership.integration.platform.engine.camel.reifiers;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.StepDefinition;
import org.apache.camel.processor.StepProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomStepReifierTest {

    private CamelContext camelContext;
    private Route route;
    private StepDefinition definition;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        route = mock(Route.class);
        definition = new StepDefinition();
        definition.setId("step-1");

        when(route.getCamelContext()).thenReturn(camelContext);
    }

    @Test
    void shouldDelegateToCreateChildProcessorWithMandatoryTrueWhenCreateProcessorCalled() throws Exception {
        Processor childProcessor = mock(Processor.class);
        TestableCustomStepReifier reifier = new TestableCustomStepReifier(route, definition);
        reifier.childProcessorToReturn = childProcessor;

        Processor result = reifier.createProcessor();

        assertSame(childProcessor, result);
        assertTrue(reifier.createChildProcessorCalled);
        assertTrue(reifier.lastCreateChildProcessorMandatory);
    }

    @Test
    void shouldReturnNullWhenCreateCompositeProcessorCalledWithEmptyList() throws Exception {
        TestableCustomStepReifier reifier = new TestableCustomStepReifier(route, definition);

        Processor result = reifier.invokeCreateCompositeProcessor(List.of());

        assertNull(result);
    }

    @Test
    void shouldCreateStepProcessorWhenCreateCompositeProcessorCalledWithProcessors() throws Exception {
        TestableCustomStepReifier reifier = new TestableCustomStepReifier(route, definition);
        Processor processor = mock(Processor.class);

        Processor result = reifier.invokeCreateCompositeProcessor(List.of(processor));

        assertInstanceOf(StepProcessor.class, result);
    }

    private static class TestableCustomStepReifier extends CustomStepReifier {

        private Processor childProcessorToReturn;
        private boolean createChildProcessorCalled;
        private boolean lastCreateChildProcessorMandatory;

        private TestableCustomStepReifier(Route route, ProcessorDefinition<?> definition) {
            super(route, definition);
        }

        @Override
        protected Processor createChildProcessor(boolean mandatory) {
            this.createChildProcessorCalled = true;
            this.lastCreateChildProcessorMandatory = mandatory;
            return childProcessorToReturn;
        }

        private Processor invokeCreateCompositeProcessor(List<Processor> list) throws Exception {
            return super.createCompositeProcessor(list);
        }
    }
}
