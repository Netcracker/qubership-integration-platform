package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.support.RestConsumerContextPathMatcher.ConsumerPath;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RestConsumerContextPathCustomMatcherTest {

    @Test
    void shouldReturnNewestConsumerWhenDirectPathsMatch() {
        ServletCustomConsumer olderConsumer = mock(ServletCustomConsumer.class);
        when(olderConsumer.getCreationTime()).thenReturn(100L);

        ServletCustomConsumer newerConsumer = mock(ServletCustomConsumer.class);
        when(newerConsumer.getCreationTime()).thenReturn(200L);

        ConsumerPath<HttpConsumer> olderPath = consumerPath("/orders", "GET", false, olderConsumer);
        ConsumerPath<HttpConsumer> newerPath = consumerPath("/orders", "GET", false, newerConsumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "GET",
                "/orders",
                List.of(olderPath, newerPath)
        );

        assertSame(newerPath, result);
    }

    @Test
    void shouldMatchConsumerWhenRestrictMethodIsNull() {
        ServletCustomConsumer consumer = mock(ServletCustomConsumer.class);

        ConsumerPath<HttpConsumer> path = consumerPath("/orders", null, false, consumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "POST",
                "/orders",
                List.of(path)
        );

        assertSame(path, result);
    }

    @Test
    void shouldReturnMatchingConsumerForOptionsWhenNoMethodSpecificCandidateFound() {
        ServletCustomConsumer consumer = mock(ServletCustomConsumer.class);

        ConsumerPath<HttpConsumer> path = consumerPath("/orders", "GET", false, consumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "OPTIONS",
                "/orders",
                List.of(path)
        );

        assertSame(path, result);
    }

    @Test
    void shouldPreferConsumerWithFewestWildcardsWhenMultipleWildcardPathsMatch() {
        ServletCustomConsumer oneWildcardConsumer = mock(ServletCustomConsumer.class);
        ServletCustomConsumer twoWildcardsConsumer = mock(ServletCustomConsumer.class);

        ConsumerPath<HttpConsumer> oneWildcardPath =
                consumerPath("/orders/{id}/details", "GET", false, oneWildcardConsumer);
        ConsumerPath<HttpConsumer> twoWildcardsPath =
                consumerPath("/orders/{id}/{section}", "GET", false, twoWildcardsConsumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "GET",
                "/orders/42/details",
                List.of(twoWildcardsPath, oneWildcardPath)
        );

        assertSame(oneWildcardPath, result);
    }

    @Test
    void shouldReturnSingleRemainingWildcardCandidateWhenOnlyOneWildcardCandidateMatches() {
        ServletCustomConsumer matchingConsumer = mock(ServletCustomConsumer.class);
        ServletCustomConsumer nonMatchingConsumer = mock(ServletCustomConsumer.class);

        ConsumerPath<HttpConsumer> matchingPath =
                consumerPath("/orders/{id}", "GET", false, matchingConsumer);
        ConsumerPath<HttpConsumer> nonMatchingPath =
                consumerPath("/customers/{id}", "GET", false, nonMatchingConsumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "GET",
                "/orders/42",
                List.of(matchingPath, nonMatchingPath)
        );

        assertSame(matchingPath, result);
    }

    @Test
    void shouldReturnNullWhenNoPathsMatch() {
        ServletCustomConsumer consumer = mock(ServletCustomConsumer.class);

        ConsumerPath<HttpConsumer> path = consumerPath("/orders", "GET", false, consumer);

        ConsumerPath<HttpConsumer> result = RestConsumerContextPathCustomMatcher.matchBestPath(
                "POST",
                "/customers",
                List.of(path)
        );

        assertNull(result);
    }

    private static ConsumerPath<HttpConsumer> consumerPath(
            String consumerPath,
            String restrictMethod,
            boolean matchOnUriPrefix,
            HttpConsumer consumer
    ) {
        @SuppressWarnings("unchecked")
        ConsumerPath<HttpConsumer> path = mock(ConsumerPath.class, withSettings().lenient());

        lenient().when(path.getConsumerPath()).thenReturn(consumerPath);
        lenient().when(path.getRestrictMethod()).thenReturn(restrictMethod);
        lenient().when(path.isMatchOnUriPrefix()).thenReturn(matchOnUriPrefix);
        lenient().when(path.getConsumer()).thenReturn(consumer);

        return path;
    }
}
