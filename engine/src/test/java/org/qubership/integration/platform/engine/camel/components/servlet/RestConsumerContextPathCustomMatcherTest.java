package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.support.RestConsumerContextPathMatcher.ConsumerPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestConsumerContextPathCustomMatcherTest {

    private final ConsumerPath<HttpConsumer> older = consumerPath("/orders", 100L);
    private final ConsumerPath<HttpConsumer> newer = consumerPath("/Orders", 200L);

    @Test
    void picksTheConsumerSpelledLikeTheRequest() {
        assertSame(older, RestConsumerContextPathCustomMatcher.matchBestPath("GET", "/orders", List.of(older, newer)));
        assertSame(newer, RestConsumerContextPathCustomMatcher.matchBestPath("GET", "/Orders", List.of(older, newer)));
    }

    @Test
    void picksTheNewestConsumerForAnotherSpelling() {
        assertSame(newer, RestConsumerContextPathCustomMatcher.matchBestPath("GET", "/ORDERS", List.of(older, newer)));
    }

    @Test
    void picksTheConsumerSpelledLikeTheRequestNextToAPathWithParameters() {
        ConsumerPath<HttpConsumer> byId = consumerPath("/orders/{id}", 300L);

        assertSame(older, RestConsumerContextPathCustomMatcher.matchBestPath("GET", "/orders", List.of(older, newer, byId)));
    }

    @Test
    void picksTheParameterizedConsumerSpelledLikeTheRequest() {
        ConsumerPath<HttpConsumer> olderById = consumerPath("/customer/{id}", 100L);
        ConsumerPath<HttpConsumer> newerById = consumerPath("/Customer/{id}", 200L);

        assertSame(olderById, RestConsumerContextPathCustomMatcher.matchBestPath("GET", "/customer/1", List.of(olderById, newerById)));
    }

    @SuppressWarnings("unchecked")
    private static ConsumerPath<HttpConsumer> consumerPath(String path, long creationTime) {
        ServletCustomConsumer consumer = mock(ServletCustomConsumer.class);
        when(consumer.getCreationTime()).thenReturn(creationTime);
        ConsumerPath<HttpConsumer> consumerPath = mock(ConsumerPath.class);
        when(consumerPath.getConsumerPath()).thenReturn(path);
        when(consumerPath.getConsumer()).thenReturn(consumer);
        return consumerPath;
    }
}
