package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpHeaderFilterStrategy;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GraphqlCustomProducerTest {

    private static final String GRAPHQL_URI = "http://localhost:8080/graphql";

    private CamelContext camelContext;
    private GraphqlCustomProducer producer;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();

        GraphqlCustomComponent component = new GraphqlCustomComponent();
        component.setCamelContext(camelContext);

        GraphqlCustomEndpoint endpoint = new GraphqlCustomEndpoint("graphql-custom:" + GRAPHQL_URI, component);
        endpoint.setCamelContext(camelContext);
        endpoint.setHttpUri(URI.create(GRAPHQL_URI));
        endpoint.setQueryHeader("CamelGraphQLQuery");
        endpoint.setVariablesHeader("CamelGraphQLVariables");
        endpoint.setHeaderFilterStrategy(new HttpHeaderFilterStrategy());

        producer = new GraphqlCustomProducer(endpoint);
    }

    @AfterEach
    void tearDown() {
        camelContext.stop();
    }

    @Test
    void shouldTransferAuthorizationHeader() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("Authorization", "Bearer token-123");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        producer.copyExchangeHeaders(exchange, request);

        assertEquals("Bearer token-123", request.getFirstHeader("Authorization").getValue());
    }

    @Test
    void shouldTransferContextHeaders() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("X-Request-Id", "request-1");
        exchange.getMessage().setHeader("Business-Request-Id", "business-1");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        producer.copyExchangeHeaders(exchange, request);

        assertEquals("request-1", request.getFirstHeader("X-Request-Id").getValue());
        assertEquals("business-1", request.getFirstHeader("Business-Request-Id").getValue());
    }

    @Test
    void shouldNotOverwriteJsonContentType() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("Content-Type", "text/plain");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        request.setHeader("Content-Type", "application/json");
        producer.copyExchangeHeaders(exchange, request);

        assertEquals(1, request.getHeaders("Content-Type").length);
        assertEquals("application/json", request.getFirstHeader("Content-Type").getValue());
    }

    @Test
    void shouldSkipInternalCamelHeaders() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("CamelHttpUri", "http://other-host/graphql");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        producer.copyExchangeHeaders(exchange, request);

        assertNull(request.getFirstHeader("CamelHttpUri"));
    }

    @Test
    void shouldSkipGraphqlOperationHeaders() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("CamelGraphQLQuery", "query { hero { name } }");
        exchange.getMessage().setHeader("CamelGraphQLVariables", "{}");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        producer.copyExchangeHeaders(exchange, request);

        assertNull(request.getFirstHeader("CamelGraphQLQuery"));
        assertNull(request.getFirstHeader("CamelGraphQLVariables"));
    }

    @Test
    void shouldSkipGraphqlOperationHeadersWithOverriddenNames() {
        GraphqlCustomEndpoint endpoint = producer.getEndpoint();
        endpoint.setQueryHeader("gqlQuery");
        endpoint.setVariablesHeader("gqlVariables");

        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader("gqlQuery", "query { hero { name } }");
        exchange.getMessage().setHeader("gqlVariables", "{}");

        HttpPost request = new HttpPost(GRAPHQL_URI);
        producer.copyExchangeHeaders(exchange, request);

        assertNull(request.getFirstHeader("gqlQuery"));
        assertNull(request.getFirstHeader("gqlVariables"));
    }
}
