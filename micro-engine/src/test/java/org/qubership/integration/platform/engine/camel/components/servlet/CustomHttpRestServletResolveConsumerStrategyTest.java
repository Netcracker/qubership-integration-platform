package org.qubership.integration.platform.engine.camel.components.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.camel.component.servlet.ServletEndpoint;
import org.apache.camel.http.common.HttpConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomHttpRestServletResolveConsumerStrategyTest {

    CustomHttpRestServletResolveConsumerStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new CustomHttpRestServletResolveConsumerStrategy();
    }

    @Test
    void shouldReturnNullWhenPathIsNull() {
        HttpConsumer result = strategy.resolvePath(null, "GET", Map.of());

        assertNull(result);
    }

    @Test
    void shouldReturnMatchingConsumerWhenResolvePathCalled() {
        HttpConsumer consumer = consumer("/orders", "GET", false);

        HttpConsumer result = strategy.resolvePath("/orders", "GET", Map.of("orders", consumer));

        assertSame(consumer, result);
    }

    @Test
    void shouldReturnNullWhenNoConsumerMatchesPathOrMethod() {
        HttpConsumer consumer = consumer("/orders", "GET", false);

        HttpConsumer result = strategy.resolvePath("/customers", "POST", Map.of("orders", consumer));

        assertNull(result);
    }

    @Test
    void shouldResolveUsingRequestPathInfoWhenDoResolveCalled() {
        ExposedCustomHttpRestServletResolveConsumerStrategy strategy =
                new ExposedCustomHttpRestServletResolveConsumerStrategy();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn("/orders");

        HttpConsumer consumer = consumer("/orders", "GET", false);

        HttpConsumer result = strategy.resolve(request, "GET", Map.of("orders", consumer));

        assertSame(consumer, result);
    }

    private static HttpConsumer consumer(String path, String httpMethodRestrict, boolean matchOnUriPrefix) {
        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletEndpoint endpoint = mock(ServletEndpoint.class);

        when(consumer.getPath()).thenReturn(path);
        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getHttpMethodRestrict()).thenReturn(httpMethodRestrict);
        when(endpoint.isMatchOnUriPrefix()).thenReturn(matchOnUriPrefix);

        return consumer;
    }

    private static class ExposedCustomHttpRestServletResolveConsumerStrategy
            extends CustomHttpRestServletResolveConsumerStrategy {

        private HttpConsumer resolve(HttpServletRequest request, String method, Map<String, HttpConsumer> consumers) {
            return super.doResolve(request, method, consumers);
        }
    }
}
