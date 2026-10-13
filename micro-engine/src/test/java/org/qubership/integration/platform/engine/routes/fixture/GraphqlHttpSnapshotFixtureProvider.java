package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.trafficlistener.DoNothingWiremockNetworkTrafficListener;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.qubership.integration.platform.engine.camel.components.graphql.GraphqlCustomComponent;
import org.qubership.integration.platform.engine.camel.components.graphql.GraphqlCustomEndpoint;
import org.qubership.integration.platform.engine.camel.processors.GraphQLVariablesProcessor;
import org.qubership.integration.platform.engine.camel.processors.SetCaughtHttpExceptionContextProcessor;
import org.qubership.integration.platform.engine.camel.processors.ThrowCaughtExceptionProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.GraphQLSessionLoggingProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class GraphqlHttpSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "graphql-http";
    private static final String COMPONENT_NAME = "graphql-custom";
    private static final String ENDPOINT_PREFIX = COMPONENT_NAME + ':';
    private static final String QUERY_HEADER_PARAMETER = "queryHeader";
    private static final String VARIABLES_HEADER_PARAMETER = "variablesHeader";
    private static final String HTTP_CLIENT_CONFIGURER_PARAMETER = "httpClientConfigurer";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String QUERY_HEADER = "CamelGraphQLQuery";
    private static final String VARIABLES_HEADER = "CamelGraphQLVariables";
    private static final String EXPECTED_SEND_COUNT = "expectedSendCount";
    private static final String EXPECTED_CONNECTION_COUNT = "expectedConnectionCount";
    private static final String TRANSPORT_FAULT = "transportFault";
    private static final String EXPECTED_RESTORED_CONTEXT = "expectedRestoredContext";
    private static final Set<String> RESPONSE_PROPERTIES = Set.of(
            EXPECTED_SEND_COUNT, EXPECTED_CONNECTION_COUNT, TRANSPORT_FAULT, EXPECTED_RESTORED_CONTEXT);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supportsResponseDelay() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new GraphqlHttpSnapshotFixture(deploymentId, bindings);
    }

    private static final class GraphqlHttpSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, GraphqlServiceStub> stubsByFixtureId = new LinkedHashMap<>();
        private final SnapshotContextRuntime runtime;

        private GraphqlHttpSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            this.runtime = new SnapshotContextRuntime(this.bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                validateBinding(binding);
                stubsByFixtureId.put(
                        binding.definition().getId(),
                        new GraphqlServiceStub(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() {
            for (GraphqlServiceStub stub : stubsByFixtureId.values()) {
                stub.start();
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            registerRuntimeBeans(camelContext);
            camelContext.addComponent(COMPONENT_NAME, new GraphqlCustomComponent());

            ModelCamelContext modelCamelContext = (ModelCamelContext) camelContext;
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                SnapshotFixtureDefinition definition = binding.definition();
                GraphqlProducerNode producerNode = findProducerNode(
                        modelCamelContext.getRouteDefinitions(),
                        definition
                );
                GraphqlServiceStub stub = stubsByFixtureId.get(definition.getId());
                GraphqlEndpointUri endpointUri = GraphqlEndpointUri.parse(
                        producerNode.endpoint().getUri(),
                        definition
                );
                stub.endpointUri = endpointUri;
                producerNode.endpoint().setUri(endpointUri.redirectTo(stub.baseUrl()));

                if (camelContext.getRegistry().lookupByNameAndType(
                        definition.getNodeId(), HttpClientConfigurer.class
                ) == null) {
                    throw new IllegalStateException(
                            "GraphQL HTTP fixture '" + definition.getId()
                                    + "' requires generated HTTP client configurer '" + definition.getNodeId() + "'."
                    );
                }
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }

            runtime.configure(camelContext, List.copyOf(bindingsByRoute.keySet()));
            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId())
                                .before()
                                .process(exchange -> capturePreparedRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            runtime.beforeInvocation();
            for (SnapshotFixtureBinding binding : bindings) {
                GraphqlServiceStub stub = stubsByFixtureId.get(binding.definition().getId());
                stub.beforeInvocation(binding.interaction(invocation.getId()), invocation.getId());
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            for (SnapshotFixtureBinding binding : bindings) {
                GraphqlServiceStub stub = stubsByFixtureId.get(binding.definition().getId());
                stub.verify(binding.interaction(invocation.getId()), invocation.getId());
            }
            runtime.verifyInvocation(invocation);
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() {
            try {
                List<GraphqlServiceStub> stubs = new ArrayList<>(stubsByFixtureId.values());
                for (int index = stubs.size() - 1; index >= 0; index--) {
                    stubs.get(index).close();
                }
            } finally {
                runtime.close();
            }
        }

        private void capturePreparedRequest(
                SnapshotFixtureBinding binding,
                Exchange exchange
        ) {
            runtime.senderEntered(binding, exchange);
            GraphqlServiceStub stub = stubsByFixtureId.get(binding.definition().getId());
            String destination = null;
            if (binding.interactionsByInvocationId().values().stream()
                    .anyMatch(interaction -> interaction.getExpectedRequest().getDestination() != null)) {
                String uri = exchange.getContext().resolveLanguage("simple")
                        .createExpression(stub.endpointUri.original()).evaluate(exchange, String.class);
                destination = exchange.getContext().getEndpoint(uri, GraphqlCustomEndpoint.class).getHttpUri().toString();
            }
            stub.addPreparedRequest(new PreparedRequest(immutableMap(exchange.getProperties()), destination));
        }
    }

    private static final class GraphqlServiceStub implements AutoCloseable {
        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private final AtomicInteger connectionCount = new AtomicInteger();
        private WireMockServer server;
        private GraphqlEndpointUri endpointUri;
        private int httpRequestBaseline;
        private int preparedRequestBaseline;

        private GraphqlServiceStub(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void start() {
            server = new WireMockServer(options()
                    .dynamicPort()
                    .bindAddress(LOOPBACK_HOST)
                    .networkTrafficListener(new DoNothingWiremockNetworkTrafficListener() {
                        @Override
                        public void opened(Socket socket) {
                            connectionCount.incrementAndGet();
                        }
                    }));
            server.start();
        }

        private String baseUrl() {
            return "http://" + LOOPBACK_HOST + ':' + server.port();
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private void beforeInvocation(
                SnapshotFixtureInteraction interaction,
                String invocationId
        ) throws IOException {
            if (interaction == null) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + binding.definition().getId()
                                + "' does not define an interaction for invocation '" + invocationId + "'."
                );
            }

            httpRequestBaseline = server.getAllServeEvents().size();
            preparedRequestBaseline = preparedRequests.size();
            server.resetMappings();

            SnapshotFixtureResponse response = interaction.getResponse();
            ResponseDefinitionBuilder responseDefinition = aResponse()
                    .withStatus(response.getStatus())
                    .withBody(responseBody(response));
            if (response.getDelayMillis() != null) {
                responseDefinition.withFixedDelay(response.getDelayMillis());
            }
            if (response.getProperties().containsKey(TRANSPORT_FAULT)) {
                responseDefinition.withFault(Fault.valueOf((String) response.getProperties().get(TRANSPORT_FAULT)));
            }
            if (!containsHeader(response.getHeaders(), "Content-Type")) {
                responseDefinition.withHeader("Content-Type", "application/json");
            }
            response.getHeaders().forEach((name, value) ->
                    responseDefinition.withHeader(name, String.valueOf(value)));
            server.stubFor(any(anyUrl()).willReturn(responseDefinition));
        }

        private byte[] responseBody(SnapshotFixtureResponse response) throws IOException {
            Object body = response.getBody();
            if (body == null) {
                return new byte[0];
            }
            if (body instanceof byte[] bytes) {
                return bytes.clone();
            }
            if (body instanceof String string) {
                return string.getBytes(StandardCharsets.UTF_8);
            }
            return objectMapper.writeValueAsBytes(body);
        }

        private void verify(
                SnapshotFixtureInteraction interaction,
                String invocationId
        ) throws IOException {
            SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
            String fixtureId = binding.definition().getId();

            List<LoggedRequest> invocationRequests = SnapshotHttpRequests.since(server, httpRequestBaseline);
            List<PreparedRequest> invocationPreparedRequests = List.copyOf(
                    preparedRequests.subList(preparedRequestBaseline, preparedRequests.size())
            );

            assertEquals(
                    expectation.getCount(),
                    invocationRequests.size(),
                    () -> "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' received an unexpected number of requests."
            );
            assertEquals(
                    interaction.getResponse().getProperties().getOrDefault(EXPECTED_SEND_COUNT, expectation.getCount()),
                    invocationPreparedRequests.size(),
                    () -> "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' entered the sender an unexpected number of times."
            );
            if (interaction.getResponse().getProperties().containsKey(EXPECTED_CONNECTION_COUNT)) {
                assertEquals(
                        interaction.getResponse().getProperties().get(EXPECTED_CONNECTION_COUNT),
                        connectionCount.get(),
                        () -> "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' opened an unexpected cumulative number of TCP connections."
                );
            }

            for (int index = 0; index < invocationPreparedRequests.size(); index++) {
                if (expectation.getDestination() != null) {
                    assertEquals(expectation.getDestination(), invocationPreparedRequests.get(index).destination(),
                            "GraphQL HTTP fixture '" + fixtureId + "' sender entry " + (index + 1)
                                    + " has an unexpected generated destination.");
                }
                SnapshotValueAssertions.assertMapValues(
                        expectation.getProperties(),
                        invocationPreparedRequests.get(index).properties(),
                        "GraphQL HTTP fixture '" + fixtureId + "' sender entry " + (index + 1)
                                + " has an unexpected property"
                );
            }
            for (int index = 0; index < invocationRequests.size(); index++) {
                verifyRequest(
                        fixtureId,
                        index + 1,
                        expectation,
                        invocationRequests.get(index)
                );
            }
        }

        private void verifyRequest(
                String fixtureId,
                int requestNumber,
                SnapshotFixtureRequestExpectation expectation,
                LoggedRequest request
        ) throws IOException {
            if (expectation.getMethod() != null) {
                assertEquals(
                        expectation.getMethod(),
                        request.getMethod().getName(),
                        () -> requestFailure(fixtureId, requestNumber, "method")
                );
            }
            if (expectation.getPath() != null) {
                assertEquals(
                        expectation.getPath(),
                        URI.create(request.getUrl()).getRawPath(),
                        () -> requestFailure(fixtureId, requestNumber, "path")
                );
            }
            if (expectation.getQuery() != null) {
                assertEquals(
                        expectation.getQuery(),
                        URI.create(request.getUrl()).getRawQuery(),
                        () -> requestFailure(fixtureId, requestNumber, "query")
                );
            }
            if (expectation.hasBody()) {
                JsonNode expectedBody = expectedJson(expectation.getBody());
                JsonNode actualBody = objectMapper.readTree(request.getBody());
                assertEquals(
                        expectedBody,
                        actualBody,
                        () -> requestFailure(fixtureId, requestNumber, "JSON body")
                );
            }
            SnapshotValueAssertions.assertMapValues(
                    expectation.getHeaders(),
                    SnapshotHttpRequests.immutableHeaders(request.getHeaders()),
                    "GraphQL HTTP fixture '" + fixtureId + "' request " + requestNumber
                            + " has an unexpected header"
            );
        }

        private JsonNode expectedJson(Object value) throws IOException {
            if (value instanceof String string) {
                return objectMapper.readTree(string);
            }
            return objectMapper.valueToTree(value);
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop();
                server = null;
            }
        }
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        String fixtureId = binding.definition().getId();
        binding.interactionsByInvocationId().forEach((invocationId, interaction) -> {
            if (interaction.getResponse() == null) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' must define a response."
                );
            }
            Map<String, Object> responseProperties = interaction.getResponse().getProperties();
            if (!RESPONSE_PROPERTIES.containsAll(responseProperties.keySet())) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' has an unsupported response property. Supported properties: " + RESPONSE_PROPERTIES + "."
                );
            }
            for (String countProperty : List.of(EXPECTED_SEND_COUNT, EXPECTED_CONNECTION_COUNT)) {
                if (responseProperties.containsKey(countProperty)
                        && (!(responseProperties.get(countProperty) instanceof Integer count) || count < 0)) {
                    throw new IllegalArgumentException(
                            "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                    + "' " + countProperty + " must be a nonnegative integer."
                    );
                }
            }
            if (responseProperties.containsKey(TRANSPORT_FAULT)
                    && !(Fault.EMPTY_RESPONSE.name().equals(responseProperties.get(TRANSPORT_FAULT))
                    || Fault.CONNECTION_RESET_BY_PEER.name().equals(responseProperties.get(TRANSPORT_FAULT)))) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' transportFault must be EMPTY_RESPONSE or CONNECTION_RESET_BY_PEER."
                );
            }
            if (responseProperties.containsKey(EXPECTED_RESTORED_CONTEXT)
                    && (!(responseProperties.get(EXPECTED_RESTORED_CONTEXT) instanceof Map<?, ?> context)
                    || context.keySet().stream().anyMatch(key -> !(key instanceof String)))) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' expectedRestoredContext must be a map with string keys."
                );
            }

            SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
            if (expectation == null) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' must define an expected request."
                );
            }
            if (expectation.getKey() != null) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId + "' invocation '" + invocationId
                                + "' does not support a key expectation."
                );
            }
        });
    }

    private static void registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        bindProcessor(
                registry,
                "graphQLSessionLoggingProcessor",
                new GraphQLSessionLoggingProcessor(new ChainRuntimePropertiesService())
        );
        bindProcessor(
                registry,
                "graphQLVariablesProcessor",
                new GraphQLVariablesProcessor(ObjectMappers.getObjectMapper())
        );
        bindProcessor(
                registry,
                "setCaughtHttpExceptionContextProcessor",
                new SetCaughtHttpExceptionContextProcessor()
        );
        bindProcessor(
                registry,
                "throwCaughtExceptionProcessor",
                new ThrowCaughtExceptionProcessor()
        );
    }

    private static void bindProcessor(Registry registry, String name, Processor processor) {
        registry.bind(name, Processor.class, processor);
    }

    private static GraphqlProducerNode findProducerNode(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition fixtureDefinition
    ) {
        List<GraphqlProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, fixtureDefinition.getNodeId())) {
            ToDynamicDefinition endpoint = SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findDynamicEndpoints(match.definition(), ENDPOINT_PREFIX),
                    "GraphQL HTTP fixture '" + fixtureDefinition.getId() + "' sender node '"
                            + fixtureDefinition.getNodeId()
                            + "' must contain exactly one GraphQL dynamic endpoint"
            );
            matchingNodes.add(new GraphqlProducerNode(match.route(), endpoint));
        }
        return SnapshotRouteNodes.requireSingle(
                matchingNodes,
                "GraphQL HTTP fixture '" + fixtureDefinition.getId()
                        + "' expected one sender node '" + fixtureDefinition.getNodeId()
                        + "' in deployment '" + fixtureDefinition.getDeploymentId() + "'"
        );
    }

    private static String requestFailure(String fixtureId, int requestNumber, String valueType) {
        return "GraphQL HTTP fixture '" + fixtureId + "' request " + requestNumber
                + " has an unexpected " + valueType + ".";
    }

    private static boolean containsHeader(Map<String, Object> headers, String expectedName) {
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }

    private record GraphqlProducerNode(
            RouteDefinition route,
            ToDynamicDefinition endpoint
    ) {
    }

    private record PreparedRequest(
            Map<String, Object> properties,
            String destination
    ) {
    }

    private record GraphqlEndpointUri(
            String original,
            String path,
            String query
    ) {
        private String redirectTo(String baseUrl) {
            return ENDPOINT_PREFIX + baseUrl + path + '?' + query;
        }

        private static GraphqlEndpointUri parse(
                String uri,
                SnapshotFixtureDefinition fixtureDefinition
        ) {
            String fixtureId = fixtureDefinition.getId();
            if (uri == null || !uri.startsWith(ENDPOINT_PREFIX)) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId
                                + "' must target a graphql-custom endpoint."
                );
            }

            int queryStart = uri.indexOf('?');
            if (queryStart < 0 || queryStart == uri.length() - 1) {
                throw new IllegalArgumentException(
                        "GraphQL HTTP fixture '" + fixtureId
                                + "' endpoint must define GraphQL query parameters."
                );
            }

            String target = uri.substring(ENDPOINT_PREFIX.length(), queryStart);
            String query = uri.substring(queryStart + 1);
            Map<String, String> parameters = SnapshotEndpointParameters.parse(query, name -> invalidEndpoint(
                    fixtureId,
                    "contains duplicate query parameter '" + name + "'"
            ));
            requireParameter(parameters, QUERY_HEADER_PARAMETER, QUERY_HEADER, fixtureId);
            requireParameter(parameters, VARIABLES_HEADER_PARAMETER, VARIABLES_HEADER, fixtureId);
            requireParameter(
                    parameters,
                    HTTP_CLIENT_CONFIGURER_PARAMETER,
                    "#" + fixtureDefinition.getNodeId(),
                    fixtureId
            );

            return new GraphqlEndpointUri(
                    uri,
                    extractPath(target, fixtureId),
                    query
            );
        }

        private static String extractPath(String target, String fixtureId) {
            try {
                String path = URI.create(target).getRawPath();
                return requirePath(path, fixtureId);
            } catch (IllegalArgumentException exception) {
                throw invalidEndpoint(fixtureId, "contains an invalid HTTP target", exception);
            }
        }

        private static String requirePath(String path, String fixtureId) {
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                throw invalidEndpoint(fixtureId, "must define an absolute request path");
            }
            return path;
        }

        private static void requireParameter(
                Map<String, String> parameters,
                String name,
                String expectedValue,
                String fixtureId
        ) {
            String actualValue = parameters.get(name);
            if (!expectedValue.equals(actualValue)) {
                throw invalidEndpoint(
                        fixtureId,
                        "query parameter '" + name + "' must equal '" + expectedValue + "'"
                );
            }
        }

        private static IllegalArgumentException invalidEndpoint(
                String fixtureId,
                String reason
        ) {
            return new IllegalArgumentException(
                    "GraphQL HTTP fixture '" + fixtureId + "' endpoint " + reason + "."
            );
        }

        private static IllegalArgumentException invalidEndpoint(
                String fixtureId,
                String reason,
                Exception cause
        ) {
            return new IllegalArgumentException(
                    "GraphQL HTTP fixture '" + fixtureId + "' endpoint " + reason + ".",
                    cause
            );
        }
    }
}
