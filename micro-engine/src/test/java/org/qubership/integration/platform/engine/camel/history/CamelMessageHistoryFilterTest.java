package org.qubership.integration.platform.engine.camel.history;

import org.apache.camel.NamedNode;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelMessageHistoryFilterTest {

    @Test
    void shouldReturnTrueWhenNodeLabelMatchesHttpTriggerProcessor() {
        CamelMessageHistoryFilter filter = new CamelMessageHistoryFilter();
        FilteringMessageHistoryFactory.FilteringEntity filteringEntity = mock(FilteringMessageHistoryFactory.FilteringEntity.class);
        NamedNode node = mock(NamedNode.class);

        when(filteringEntity.node()).thenReturn(node);
        when(node.getLabel()).thenReturn("ref:httpTriggerProcessor");

        assertTrue(filter.test(filteringEntity));
    }

    @Test
    void shouldReturnFalseWhenNodeLabelDoesNotMatchHttpTriggerProcessor() {
        CamelMessageHistoryFilter filter = new CamelMessageHistoryFilter();
        FilteringMessageHistoryFactory.FilteringEntity filteringEntity = mock(FilteringMessageHistoryFactory.FilteringEntity.class);
        NamedNode node = mock(NamedNode.class);

        when(filteringEntity.node()).thenReturn(node);
        when(node.getLabel()).thenReturn("ref:anotherProcessor");

        assertFalse(filter.test(filteringEntity));
    }
}
