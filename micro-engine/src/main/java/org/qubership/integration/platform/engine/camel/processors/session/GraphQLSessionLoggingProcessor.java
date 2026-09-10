package org.qubership.integration.platform.engine.camel.processors.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.InterceptSendToEndpoint;
import org.apache.camel.util.json.JsonObject;
import org.apache.camel.util.json.Jsoner;
import org.qubership.integration.platform.engine.camel.components.graphql.GraphqlCustomEndpoint;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;

/**
 * Reconstructs the GraphQL HTTP request body before a {@code graphql-custom} endpoint is invoked.
 *
 * <p>The route must put the URI of the upcoming endpoint into
 * {@link CamelConstants.Properties#GQL_ENDPOINT_URI} before invoking this processor.
 * Query and variables must come from endpoint options or headers because this processor replaces the exchange body.
 */
@ApplicationScoped
@Named("graphQLSessionLoggingProcessor")
public class GraphQLSessionLoggingProcessor implements Processor {

    private final ChainRuntimePropertiesService propertiesService;

    @Inject
    public GraphQLSessionLoggingProcessor(ChainRuntimePropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (!isPayloadLoggingEnabled(exchange)) {
            return;
        }

        GraphqlCustomEndpoint endpoint = getEndpoint(exchange);
        String requestBody = buildRequestBody(
                getQuery(exchange, endpoint),
                endpoint.getOperationName(),
                getVariables(exchange, endpoint));

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(requestBody);
    }

    private boolean isPayloadLoggingEnabled(Exchange exchange) {
        if (!(exchange.getContext().getDebugger() instanceof CamelDebugger)) {
            return false;
        }

        ChainRuntimeProperties runtimeProperties = propertiesService.getRuntimeProperties(exchange);
        SessionsLoggingLevel sessionLevel = runtimeProperties.calculateSessionLevel(exchange);
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                false,
                Boolean.class);
        boolean sessionPayloadLoggingEnabled = SessionsLoggingLevel.hasPayload(
                sessionLevel,
                ChainElementType.isElementForInfoSessionsLevel(ChainElementType.GRAPHQL_SENDER))
                && (sessionLevel == SessionsLoggingLevel.ERROR || sessionShouldBeLogged);

        boolean applicationPayloadLoggingEnabled = runtimeProperties.getLogLoggingLevel().isInfoLevel()
                && isBodyLoggingEnabled(runtimeProperties);

        return applicationPayloadLoggingEnabled || sessionPayloadLoggingEnabled;
    }

    private static boolean isBodyLoggingEnabled(ChainRuntimeProperties runtimeProperties) {
        return runtimeProperties.getLogPayload() == null
                ? runtimeProperties.isLogPayloadEnabled()
                : runtimeProperties.getLogPayload().contains(LogPayload.BODY);
    }

    private static String buildRequestBody(String query, String operationName, JsonObject variables) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.put("query", query);
        jsonObject.put("operationName", operationName);
        jsonObject.put("variables", variables != null ? variables : new JsonObject());
        return Jsoner.prettyPrint(jsonObject.toJson());
    }

    private GraphqlCustomEndpoint getEndpoint(Exchange exchange) {
        String endpointUri = exchange.getProperty(CamelConstants.Properties.GQL_ENDPOINT_URI, String.class);
        if (endpointUri == null || endpointUri.isBlank()) {
            throw new IllegalStateException(
                    "Cannot resolve GraphQL endpoint: exchange property '"
                            + CamelConstants.Properties.GQL_ENDPOINT_URI + "' is missing or blank.");
        }

        Endpoint endpoint = exchange.getContext().getEndpoint(endpointUri);
        while (endpoint instanceof InterceptSendToEndpoint interceptedEndpoint) {
            Endpoint originalEndpoint = interceptedEndpoint.getOriginalEndpoint();
            if (originalEndpoint == endpoint) {
                throw new IllegalStateException(
                        "Cannot resolve GraphQL endpoint: intercepted endpoint refers to itself.");
            }
            endpoint = originalEndpoint;
        }

        if (!(endpoint instanceof GraphqlCustomEndpoint graphqlEndpoint)) {
            throw new IllegalStateException(
                    "Cannot resolve GraphQL endpoint: configured endpoint has type "
                            + (endpoint == null ? "null" : endpoint.getClass().getName())
                            + ", but " + GraphqlCustomEndpoint.class.getName() + " is required.");
        }

        return graphqlEndpoint;
    }

    private static String getQuery(Exchange exchange, GraphqlCustomEndpoint endpoint) {
        if (endpoint.getQuery() != null) {
            return endpoint.getQuery();
        }
        if (endpoint.getQueryHeader() != null) {
            return exchange.getIn().getHeader(endpoint.getQueryHeader(), String.class);
        }

        throw new IllegalStateException(
                "Cannot reconstruct GraphQL request body: queries sourced from the exchange body are not supported.");
    }

    private static JsonObject getVariables(Exchange exchange, GraphqlCustomEndpoint endpoint) {
        JsonObject variables = null;
        if (endpoint.getVariables() != null) {
            variables = endpoint.getVariables();
        } else if (endpoint.getVariablesHeader() != null) {
            variables = exchange.getIn().getHeader(endpoint.getVariablesHeader(), JsonObject.class);
        } else if (exchange.getIn().getBody() instanceof JsonObject) {
            throw new IllegalStateException(
                    "Cannot reconstruct GraphQL request body: variables sourced from the exchange body are not supported.");
        }
        return variables;
    }
}
