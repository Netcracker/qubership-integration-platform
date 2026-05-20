package org.qubership.integration.platform.engine.camel.history;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.apache.camel.spi.MessageHistoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FilteringMessageHistoryFactoryTest {

    private FilteringMessageHistoryFactory factory;

    @Mock
    Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter;
    @Mock
    MessageHistoryFactory delegateFactory;
    @Mock
    MessageHistory messageHistory;
    @Mock
    NamedNode node;
    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.basic();
        factory = new FilteringMessageHistoryFactory(filter, delegateFactory);
    }

    @Test
    void shouldCreateMessageHistoryWhenFilterMatches() {
        when(filter.test(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(delegateFactory.newMessageHistory("route-1", node, exchange)).thenReturn(messageHistory);

        MessageHistory result = factory.newMessageHistory("route-1", node, exchange);

        assertSame(messageHistory, result);
        verify(delegateFactory).newMessageHistory("route-1", node, exchange);
    }

    @Test
    void shouldReturnNullWhenFilterDoesNotMatch() {
        when(filter.test(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        MessageHistory result = factory.newMessageHistory("route-1", node, exchange);

        assertNull(result);
    }

    @Test
    void shouldPassFilteringEntityToPredicateWhenCreatingMessageHistory() {
        when(filter.test(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            FilteringMessageHistoryFactory.FilteringEntity entity = invocation.getArgument(0);
            return "route-1".equals(entity.routeId())
                    && entity.node() == node
                    && entity.exchange() == exchange;
        });

        factory.newMessageHistory("route-1", node, exchange);

        verify(filter).test(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldDelegateIsCopyMessage() {
        when(delegateFactory.isCopyMessage()).thenReturn(true);

        assertTrue(factory.isCopyMessage());
    }

    @Test
    void shouldDelegateSetCopyMessage() {
        factory.setCopyMessage(true);

        verify(delegateFactory).setCopyMessage(true);
    }

    @Test
    void shouldDelegateGetNodePattern() {
        when(delegateFactory.getNodePattern()).thenReturn("direct:*");

        assertSame("direct:*", factory.getNodePattern());
    }

    @Test
    void shouldDelegateSetNodePattern() {
        factory.setNodePattern("direct:*");

        verify(delegateFactory).setNodePattern("direct:*");
    }

    @Test
    void shouldDelegateGetCamelContext() {
        CamelContext camelContext = mock(CamelContext.class);

        when(delegateFactory.getCamelContext()).thenReturn(camelContext);

        assertSame(camelContext, factory.getCamelContext());
    }

    @Test
    void shouldDelegateSetCamelContext() {
        CamelContext camelContext = mock(CamelContext.class);

        factory.setCamelContext(camelContext);

        verify(delegateFactory).setCamelContext(camelContext);
    }

    @Test
    void shouldDelegateStart() throws Exception {
        factory.start();

        verify(delegateFactory).start();
    }

    @Test
    void shouldDelegateStop() throws Exception {
        factory.stop();

        verify(delegateFactory).stop();
    }
}
