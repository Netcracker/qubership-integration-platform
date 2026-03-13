package org.qubership.integration.platform.engine.camel.history;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.apache.camel.spi.MessageHistoryFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

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

    @Test
    void shouldCreateMessageHistoryWhenFilterMatches() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);
        MessageHistory messageHistory = mock(MessageHistory.class);
        NamedNode node = mock(NamedNode.class);
        Exchange exchange = mock(Exchange.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        when(filter.test(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(delegateFactory.newMessageHistory("route-1", node, exchange)).thenReturn(messageHistory);

        MessageHistory result = factory.newMessageHistory("route-1", node, exchange);

        assertSame(messageHistory, result);
        verify(delegateFactory).newMessageHistory("route-1", node, exchange);
    }

    @Test
    void shouldReturnNullWhenFilterDoesNotMatch() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);
        NamedNode node = mock(NamedNode.class);
        Exchange exchange = mock(Exchange.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        when(filter.test(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        MessageHistory result = factory.newMessageHistory("route-1", node, exchange);

        assertNull(result);
    }

    @Test
    void shouldPassFilteringEntityToPredicateWhenCreatingMessageHistory() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);
        NamedNode node = mock(NamedNode.class);
        Exchange exchange = mock(Exchange.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

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
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        when(delegateFactory.isCopyMessage()).thenReturn(true);

        assertTrue(factory.isCopyMessage());
    }

    @Test
    void shouldDelegateSetCopyMessage() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        factory.setCopyMessage(true);

        verify(delegateFactory).setCopyMessage(true);
    }

    @Test
    void shouldDelegateGetNodePattern() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        when(delegateFactory.getNodePattern()).thenReturn("direct:*");

        assertSame("direct:*", factory.getNodePattern());
    }

    @Test
    void shouldDelegateSetNodePattern() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        factory.setNodePattern("direct:*");

        verify(delegateFactory).setNodePattern("direct:*");
    }

    @Test
    void shouldDelegateGetCamelContext() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);
        CamelContext camelContext = mock(CamelContext.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        when(delegateFactory.getCamelContext()).thenReturn(camelContext);

        assertSame(camelContext, factory.getCamelContext());
    }

    @Test
    void shouldDelegateSetCamelContext() {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);
        CamelContext camelContext = mock(CamelContext.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        factory.setCamelContext(camelContext);

        verify(delegateFactory).setCamelContext(camelContext);
    }

    @Test
    void shouldDelegateStart() throws Exception {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        factory.start();

        verify(delegateFactory).start();
    }

    @Test
    void shouldDelegateStop() throws Exception {
        @SuppressWarnings("unchecked")
        Predicate<FilteringMessageHistoryFactory.FilteringEntity> filter = mock(Predicate.class);
        MessageHistoryFactory delegateFactory = mock(MessageHistoryFactory.class);

        FilteringMessageHistoryFactory factory = new FilteringMessageHistoryFactory(filter, delegateFactory);

        factory.stop();

        verify(delegateFactory).stop();
    }
}
