package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.Endpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphqlCustomComponentTest {

    private GraphqlCustomComponent component;

    @BeforeEach
    void setUp() {
        component = new GraphqlCustomComponent();
        component.setCamelContext(new DefaultCamelContext());
    }

    @Test
    void shouldCreateGraphqlCustomEndpointAndSetHttpUriFromRemaining() throws Exception {
        Endpoint ep = component.createEndpoint(
                "graphql-custom:http://localhost/graphql",
                "http://localhost/graphql",
                new HashMap<>()
        );

        assertNotNull(ep);
        assertInstanceOf(GraphqlCustomEndpoint.class, ep);

        GraphqlCustomEndpoint endpoint = (GraphqlCustomEndpoint) ep;
        assertEquals(new URI("http://localhost/graphql"), endpoint.getHttpUri());
    }

    @Test
    void shouldApplyPropertiesToEndpointWhenParametersProvided() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "query { test }");
        params.put("proxyHost", "proxy.local:3128");

        Endpoint ep = component.createEndpoint(
                "graphql-custom:http://localhost/graphql",
                "http://localhost/graphql",
                params
        );

        GraphqlCustomEndpoint endpoint = (GraphqlCustomEndpoint) ep;

        assertEquals("query { test }", endpoint.getQuery());
        assertEquals("proxy.local:3128", endpoint.getProxyHost());
        assertEquals(new URI("http://localhost/graphql"), endpoint.getHttpUri());
    }
}
