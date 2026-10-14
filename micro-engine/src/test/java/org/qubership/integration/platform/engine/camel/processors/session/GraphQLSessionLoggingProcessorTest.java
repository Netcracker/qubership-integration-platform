package org.qubership.integration.platform.engine.camel.processors.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.graphql.GraphqlComponent;
import org.apache.camel.component.graphql.GraphqlEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultInterceptSendToEndpoint;
import org.apache.camel.util.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.graphql.GraphqlCustomComponent;
import org.qubership.integration.platform.engine.camel.components.graphql.GraphqlCustomEndpoint;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphQLSessionLoggingProcessorTest {

    private static final String QUERY = "query Hero($id: ID!) { hero(id: $id) { name } }";
    private static final String OPERATION_NAME = "Hero";
    private static final String ENDPOINT_URI = "graphql-custom:http://localhost/graphql"
            + "?operationName=" + OPERATION_NAME
            + "&queryHeader=" + CamelConstants.Headers.GQL_QUERY_HEADER
            + "&variablesHeader=" + CamelConstants.Headers.GQL_VARIABLES_HEADER;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.getObjectMapper();

    private GraphQLSessionLoggingProcessor processor;

    @Mock
    private ChainRuntimePropertiesService propertiesService;
    @Mock
    private CamelDebugger camelDebugger;

    private DefaultCamelContext camelContext;
    private GraphqlCustomComponent graphqlComponent;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        graphqlComponent = new GraphqlCustomComponent();
        graphqlComponent.setCamelContext(camelContext);
        camelContext.addComponent("graphql-custom", graphqlComponent);
        processor = new GraphQLSessionLoggingProcessor(propertiesService);
    }

    @Test
    void shouldLeaveBodyUnchangedWhenDebuggerIsUnavailable() throws Exception {
        Object originalBody = new Object();
        Exchange exchange = createExchange(originalBody);

        processor.process(exchange);

        assertSame(originalBody, exchange.getMessage().getBody());
        verifyNoInteractions(propertiesService);
    }

    @Test
    void shouldLeaveBodyUnchangedWhenPayloadLoggingIsDisabled() throws Exception {
        Object originalBody = new Object();
        Exchange exchange = createExchange(originalBody);
        stubLogging(exchange, LogLoggingLevel.WARN, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        processor.process(exchange);

        assertSame(originalBody, exchange.getMessage().getBody());
    }

    @Test
    void shouldBuildRequestBodyWhenApplicationBodyLoggingIsEnabled() throws Exception {
        Exchange exchange = createGraphqlExchange(ENDPOINT_URI);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        processor.process(exchange);

        assertRequestBody(exchange);
    }

    @Test
    void shouldLeaveBodyUnchangedWhenApplicationBodyLoggingIsExcluded() throws Exception {
        Object originalBody = new Object();
        Exchange exchange = createExchange(originalBody);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.HEADERS), false);

        processor.process(exchange);

        assertSame(originalBody, exchange.getMessage().getBody());
    }

    @Test
    void shouldBuildRequestBodyWhenSampledInfoSessionLoggingIsEnabled() throws Exception {
        Exchange exchange = createGraphqlExchange(ENDPOINT_URI);
        stubLogging(exchange, LogLoggingLevel.WARN, SessionsLoggingLevel.INFO, Set.of(), true);

        processor.process(exchange);

        assertRequestBody(exchange);
    }

    @Test
    void shouldBuildRequestBodyForUnsampledErrorSession() throws Exception {
        Exchange exchange = createGraphqlExchange(ENDPOINT_URI);
        stubLogging(exchange, LogLoggingLevel.WARN, SessionsLoggingLevel.ERROR, Set.of(), false);

        processor.process(exchange);

        assertRequestBody(exchange);
    }

    @Test
    void shouldFailWithoutGraphqlEndpointUriProperty() {
        Object originalBody = new Object();
        Exchange exchange = createExchange(originalBody);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> processor.process(exchange));

        assertTrue(exception.getMessage().contains(CamelConstants.Properties.GQL_ENDPOINT_URI));
        assertSame(originalBody, exchange.getMessage().getBody());
    }

    @Test
    void shouldUnwrapInterceptedEndpoint() throws Exception {
        String endpointUri = "graphql-custom:http://localhost/wrapped";
        GraphqlCustomEndpoint originalEndpoint = createHeaderEndpoint(endpointUri);
        DefaultInterceptSendToEndpoint interceptedEndpoint =
                new DefaultInterceptSendToEndpoint(originalEndpoint, false);
        camelContext.addEndpoint(endpointUri, interceptedEndpoint);
        Exchange exchange = createGraphqlExchange(endpointUri);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        processor.process(exchange);

        assertRequestBody(exchange);
    }

    @Test
    void shouldRejectNonCustomGraphqlEndpoint() throws Exception {
        String endpointUri = "graphql:http://localhost/graphql";
        GraphqlComponent component = new GraphqlComponent();
        component.setCamelContext(camelContext);
        GraphqlEndpoint endpoint = new GraphqlEndpoint(endpointUri, component);
        endpoint.setHttpUri(URI.create("http://localhost/graphql"));
        camelContext.addEndpoint(endpointUri, endpoint);
        Exchange exchange = createGraphqlExchange(endpointUri);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> processor.process(exchange));

        assertTrue(exception.getMessage().contains(GraphqlCustomEndpoint.class.getName()));
    }

    @Test
    void shouldRejectQuerySourcedFromExchangeBody() {
        String endpointUri = "graphql-custom:http://localhost/graphql";
        Exchange exchange = createExchange(QUERY);
        exchange.setProperty(CamelConstants.Properties.GQL_ENDPOINT_URI, endpointUri);
        stubLogging(exchange, LogLoggingLevel.INFO, SessionsLoggingLevel.OFF, Set.of(LogPayload.BODY), false);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> processor.process(exchange));

        assertTrue(exception.getMessage().contains("queries sourced from the exchange body"));
        assertEquals(QUERY, exchange.getMessage().getBody());
    }

    private Exchange createGraphqlExchange(String endpointUri) {
        JsonObject variables = new JsonObject();
        variables.put("id", "42");

        Exchange exchange = createExchange("original-body");
        exchange.setProperty(CamelConstants.Properties.GQL_ENDPOINT_URI, endpointUri);
        exchange.getMessage().setHeader(CamelConstants.Headers.GQL_QUERY_HEADER, QUERY);
        exchange.getMessage().setHeader(CamelConstants.Headers.GQL_VARIABLES_HEADER, variables);
        return exchange;
    }

    private Exchange createExchange(Object body) {
        Exchange exchange = MockExchanges.defaultExchange(camelContext, ExchangePattern.InOnly);
        exchange.getMessage().setBody(body);
        return exchange;
    }

    private GraphqlCustomEndpoint createHeaderEndpoint(String endpointUri) {
        GraphqlCustomEndpoint endpoint = new GraphqlCustomEndpoint(endpointUri, graphqlComponent);
        endpoint.setHttpUri(URI.create("http://localhost/graphql"));
        endpoint.setOperationName(OPERATION_NAME);
        endpoint.setQueryHeader(CamelConstants.Headers.GQL_QUERY_HEADER);
        endpoint.setVariablesHeader(CamelConstants.Headers.GQL_VARIABLES_HEADER);
        return endpoint;
    }

    private void stubLogging(
            Exchange exchange,
            LogLoggingLevel logLevel,
            SessionsLoggingLevel sessionLevel,
            Set<LogPayload> logPayload,
            boolean sessionShouldBeLogged
    ) {
        camelContext.setDebugger(camelDebugger);
        exchange.setProperty(CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED, sessionShouldBeLogged);

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(logLevel)
                .sessionsLoggingLevel(sessionLevel)
                .logPayload(logPayload)
                .build();

        when(propertiesService.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
    }

    private static void assertRequestBody(Exchange exchange) throws Exception {
        String requestBodyText = exchange.getMessage().getBody(String.class);
        JsonNode requestBody = OBJECT_MAPPER.readTree(requestBodyText);

        assertEquals("application/json", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
        assertTrue(requestBodyText.lines().count() > 1);
        assertEquals(QUERY, requestBody.path("query").textValue());
        assertEquals(OPERATION_NAME, requestBody.path("operationName").textValue());
        assertEquals("42", requestBody.path("variables").path("id").textValue());
    }
}
