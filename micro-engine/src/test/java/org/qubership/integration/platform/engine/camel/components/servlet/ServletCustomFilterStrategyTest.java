package org.qubership.integration.platform.engine.camel.components.servlet;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.context.propagation.ContextPropsProvider;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ServletCustomFilterStrategyTest {

    @Test
    void shouldReturnExpectedValueWhenOutHeaderPresentInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(Set.of("x-correlation-id"));

        ExposedServletCustomFilterStrategy strategy = createStrategy(contextPropsProvider);

        boolean expected = strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-correlation-id"));
    }

    @Test
    void shouldReturnExpectedValueWhenOutHeaderMissingInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(Set.of("x-correlation-id"));

        ExposedServletCustomFilterStrategy strategy = createStrategy(contextPropsProvider);

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-another-header"));
    }

    @Test
    void shouldReturnExpectedValueWhenDownstreamHeadersAreNull() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(null);

        ExposedServletCustomFilterStrategy strategy = createStrategy(contextPropsProvider);

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-correlation-id"));
    }

    @Test
    void shouldReturnExpectedValueWhenDirectionIsInEvenIfHeaderPresentInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);

        ExposedServletCustomFilterStrategy strategy = createStrategy(contextPropsProvider);

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterIn("x-correlation-id"));
    }

    private static ExposedServletCustomFilterStrategy createStrategy(ContextPropsProvider provider) {
        return new ExposedServletCustomFilterStrategy(provider);
    }

    private static class ExposedServletCustomFilterStrategy extends ServletCustomFilterStrategy {

        private ExposedServletCustomFilterStrategy(ContextPropsProvider contextPropsProvider) {
            super(contextPropsProvider);
        }

        private boolean shouldFilterOut(String headerName) {
            return super.extendedFilter(Direction.OUT, headerName, "value", null);
        }

        private boolean shouldFilterIn(String headerName) {
            return super.extendedFilter(Direction.IN, headerName, "value", null);
        }

        private boolean filterOnMatchValue() {
            return super.isFilterOnMatch();
        }

        private Collection<String> outFilterHeaders() {
            return super.getOutFilter();
        }
    }
}
