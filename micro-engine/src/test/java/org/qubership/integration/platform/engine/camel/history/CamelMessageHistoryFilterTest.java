package org.qubership.integration.platform.engine.camel.history;

import org.apache.camel.NamedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelMessageHistoryFilterTest {

    private CamelMessageHistoryFilter filter;

    @Mock
    FilteringMessageHistoryFactory.FilteringEntity filteringEntity;
    @Mock
    NamedNode node;

    @BeforeEach
    void setUp() {
        filter = new CamelMessageHistoryFilter();

        when(filteringEntity.node()).thenReturn(node);
    }

    @Test
    void shouldReturnTrueWhenNodeLabelMatchesHttpTriggerProcessor() {
        when(node.getLabel()).thenReturn("ref:httpTriggerProcessor");

        assertTrue(filter.test(filteringEntity));
    }

    @Test
    void shouldReturnFalseWhenNodeLabelDoesNotMatchHttpTriggerProcessor() {
        when(node.getLabel()).thenReturn("ref:anotherProcessor");

        assertFalse(filter.test(filteringEntity));
    }
}
