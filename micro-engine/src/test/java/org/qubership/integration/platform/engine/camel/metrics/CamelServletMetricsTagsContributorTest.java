package org.qubership.integration.platform.engine.camel.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.quarkus.micrometer.runtime.HttpServerMetricsTagsContributor;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.apache.camel.http.common.CamelServlet;
import org.apache.camel.http.common.HttpCommonEndpoint;
import org.apache.camel.http.common.HttpConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.servlet.CustomHttpRestServletResolveConsumerStrategy;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider;
import org.qubership.integration.platform.engine.registry.GatewayHttpRegistry;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelServletMetricsTagsContributorTest {

    private GatewayHttpRegistry httpRegistry;
    private CustomHttpRestServletResolveConsumerStrategy resolveConsumerStrategy;
    private CamelServletMetricsTagsContributor contributor;

    @BeforeEach
    void setUp() {
        httpRegistry = mock(GatewayHttpRegistry.class);
        resolveConsumerStrategy = mock(CustomHttpRestServletResolveConsumerStrategy.class);
        contributor = new CamelServletMetricsTagsContributor(
                "camel-servlet",
                "/camel",
                httpRegistry,
                resolveConsumerStrategy
        );
    }

    @Test
    void shouldReturnEmptyTagsWhenPathDoesNotStartWithCamelRoutesPrefix() {
        HttpServerMetricsTagsContributor.Context context = contextWithPath("/api/orders");

        Tags result = contributor.contribute(context);

        assertEquals(Map.of(), toMap(result));
        verify(httpRegistry, never()).getCamelServlet("camel-servlet");
        verify(resolveConsumerStrategy, never()).resolvePath(anyString(), anyString(), anyMap());
    }

    @Test
    void shouldReturnEmptyTagsWhenNoConsumerResolved() {
        HttpServerMetricsTagsContributor.Context context = context("/camel/orders", HttpMethod.POST);
        CamelServlet camelServlet = mock(CamelServlet.class);

        when(httpRegistry.getCamelServlet("camel-servlet")).thenReturn(camelServlet);
        when(camelServlet.getConsumers()).thenReturn(Map.of());
        when(resolveConsumerStrategy.resolvePath("/orders", "POST", Map.of())).thenReturn(null);

        Tags result = contributor.contribute(context);

        assertEquals(Map.of(), toMap(result));
    }

    @Test
    void shouldReturnUriTagWhenConsumerResolvedWithRegularEndpoint() {
        HttpServerMetricsTagsContributor.Context context = context("/camel/orders", HttpMethod.GET);
        CamelServlet camelServlet = mock(CamelServlet.class);
        HttpConsumer consumer = mock(HttpConsumer.class);
        HttpCommonEndpoint endpoint = mock(HttpCommonEndpoint.class);
        Map<String, HttpConsumer> consumers = Map.of("orders", consumer);

        when(httpRegistry.getCamelServlet("camel-servlet")).thenReturn(camelServlet);
        when(camelServlet.getConsumers()).thenReturn(consumers);
        when(resolveConsumerStrategy.resolvePath("/orders", "GET", consumers)).thenReturn(consumer);
        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getPath()).thenReturn("/orders");

        Tags result = contributor.contribute(context);

        assertEquals(
                Map.of("uri", "/camel/orders"),
                toMap(result)
        );
    }

    @Test
    void shouldReturnUriTagOnlyWhenServletCustomEndpointHasNoTagsProvider() {
        HttpServerMetricsTagsContributor.Context context = context("/camel/orders", HttpMethod.GET);
        CamelServlet camelServlet = mock(CamelServlet.class);
        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletCustomEndpoint endpoint = mock(ServletCustomEndpoint.class);
        Map<String, HttpConsumer> consumers = Map.of("orders", consumer);

        when(httpRegistry.getCamelServlet("camel-servlet")).thenReturn(camelServlet);
        when(camelServlet.getConsumers()).thenReturn(consumers);
        when(resolveConsumerStrategy.resolvePath("/orders", "GET", consumers)).thenReturn(consumer);
        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getPath()).thenReturn("/orders");
        when(endpoint.getTagsProvider()).thenReturn(null);

        Tags result = contributor.contribute(context);

        assertEquals(
                Map.of("uri", "/camel/orders"),
                toMap(result)
        );
    }

    @Test
    void shouldReturnUriAndCustomTagsWhenServletCustomEndpointHasTagsProvider() {
        HttpServerMetricsTagsContributor.Context context = context("/camel/orders", HttpMethod.PUT);
        CamelServlet camelServlet = mock(CamelServlet.class);
        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletCustomEndpoint endpoint = mock(ServletCustomEndpoint.class);
        ServletTagsProvider tagsProvider = mock(ServletTagsProvider.class);
        Map<String, HttpConsumer> consumers = Map.of("orders", consumer);

        when(httpRegistry.getCamelServlet("camel-servlet")).thenReturn(camelServlet);
        when(camelServlet.getConsumers()).thenReturn(consumers);
        when(resolveConsumerStrategy.resolvePath("/orders", "PUT", consumers)).thenReturn(consumer);
        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getPath()).thenReturn("/orders");
        when(endpoint.getTagsProvider()).thenReturn(tagsProvider);
        when(tagsProvider.get()).thenReturn(
                java.util.List.of(
                        Tag.of("system", "billing"),
                        Tag.of("operation", "createOrder")
                )
        );

        Tags result = contributor.contribute(context);

        assertEquals(
                Map.of(
                        "uri", "/camel/orders",
                        "system", "billing",
                        "operation", "createOrder"
                ),
                toMap(result)
        );
    }

    private static HttpServerMetricsTagsContributor.Context contextWithPath(String path) {
        HttpServerMetricsTagsContributor.Context context = mock(HttpServerMetricsTagsContributor.Context.class);
        HttpServerRequest request = mock(HttpServerRequest.class);

        when(context.request()).thenReturn(request);
        when(request.path()).thenReturn(path);

        return context;
    }

    private static HttpServerMetricsTagsContributor.Context context(String path, HttpMethod method) {
        HttpServerMetricsTagsContributor.Context context = mock(HttpServerMetricsTagsContributor.Context.class);
        HttpServerRequest request = mock(HttpServerRequest.class);

        when(context.request()).thenReturn(request);
        when(request.path()).thenReturn(path);
        when(request.method()).thenReturn(method);

        return context;
    }

    private static Map<String, String> toMap(Tags tags) {
        return tags.stream()
                .collect(Collectors.toMap(
                        Tag::getKey,
                        Tag::getValue,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
    }
}
