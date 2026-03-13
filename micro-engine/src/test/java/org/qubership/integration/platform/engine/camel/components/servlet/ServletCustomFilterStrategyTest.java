package org.qubership.integration.platform.engine.camel.components.servlet;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextPropsProvider;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ServletCustomFilterStrategyTest {

    @Test
    void shouldAddStaticFilteredHeadersToOutFilterWhenCreated() {
        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.empty());

        Collection<String> outFilter = strategy.outFilterHeaders();

        assertTrue(outFilter.contains("span-id"));
        assertTrue(outFilter.contains("trace-id"));
        assertTrue(outFilter.contains("x-requestedsystem"));
    }

    @Test
    void shouldReturnExpectedValueWhenOutHeaderPresentInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(Set.of("x-correlation-id"));

        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.of(contextPropsProvider));

        boolean expected = strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-correlation-id"));
    }

    @Test
    void shouldReturnExpectedValueWhenOutHeaderMissingInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(Set.of("x-correlation-id"));

        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.of(contextPropsProvider));

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-another-header"));
    }

    @Test
    void shouldReturnExpectedValueWhenOutHeaderCheckedAndContextProviderMissing() {
        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.empty());

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-correlation-id"));
    }

    @Test
    void shouldReturnExpectedValueWhenDownstreamHeadersAreNull() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);
        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(null);

        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.of(contextPropsProvider));

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterOut("x-correlation-id"));
    }

    @Test
    void shouldReturnExpectedValueWhenDirectionIsInEvenIfHeaderPresentInContext() {
        ContextPropsProvider contextPropsProvider = mock(ContextPropsProvider.class);

        ExposedServletCustomFilterStrategy strategy = createStrategy(Optional.of(contextPropsProvider));

        boolean expected = !strategy.filterOnMatchValue();

        assertEquals(expected, strategy.shouldFilterIn("x-correlation-id"));
    }

    private static ExposedServletCustomFilterStrategy createStrategy(Optional<ContextPropsProvider> provider) {
        @SuppressWarnings("unchecked")
        Instance<ContextPropsProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtilMock = mockStatic(InjectUtil.class)) {
            injectUtilMock.when(() -> InjectUtil.injectOptional(instance)).thenReturn(provider);
            return new ExposedServletCustomFilterStrategy(instance);
        }
    }

    private static class ExposedServletCustomFilterStrategy extends ServletCustomFilterStrategy {

        private ExposedServletCustomFilterStrategy(Instance<ContextPropsProvider> contextPropsProvider) {
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
