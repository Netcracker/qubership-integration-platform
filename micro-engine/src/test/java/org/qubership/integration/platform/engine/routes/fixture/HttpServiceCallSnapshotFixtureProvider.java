package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.Registry;
import org.qubership.integration.platform.engine.camel.processors.HttpProducerCharsetProcessor;
import org.qubership.integration.platform.engine.camel.processors.HttpSenderProcessor;
import org.qubership.integration.platform.engine.camel.processors.QueryParameterFilterProcessor;
import org.qubership.integration.platform.engine.camel.processors.SetCaughtHttpExceptionContextProcessor;
import org.qubership.integration.platform.engine.camel.processors.ThrowCaughtExceptionProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class HttpServiceCallSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "http-service-call";
    private static final String REQUEST_NODE_PREFIX = "Request--";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        bindings.forEach(binding -> binding.interactionsByInvocationId().values()
                .forEach(interaction -> validateInteraction(binding.definition().getId(), interaction)));
        return new HttpServiceCallSnapshotFixture(deploymentId, bindings);
    }

    private static void validateInteraction(
            String fixtureId,
            SnapshotFixtureInteraction interaction
    ) {
        SnapshotFixtureResponse response = interaction.getResponse();
        if (response != null && !response.getProperties().isEmpty()) {
            throw new IllegalArgumentException(
                    "HTTP Service Call fixture '" + fixtureId + "' does not support response properties."
            );
        }
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        if (expectation != null && expectation.getDestination() != null) {
            throw new IllegalArgumentException(
                    "HTTP Service Call fixture '" + fixtureId
                            + "' does not support request destination expectations."
            );
        }
        if (expectation != null && expectation.getKey() != null) {
            throw new IllegalArgumentException(
                    "HTTP Service Call fixture '" + fixtureId
                            + "' does not support request key expectations."
            );
        }
    }

    private static final class HttpServiceCallSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, ServiceCallStub> stubsByFixtureId = new LinkedHashMap<>();

        private HttpServiceCallSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                stubsByFixtureId.put(
                        binding.definition().getId(),
                        new ServiceCallStub(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws IOException {
            for (ServiceCallStub stub : stubsByFixtureId.values()) {
                stub.start();
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            registerRuntimeBeans(camelContext, bindings);

            ModelCamelContext modelCamelContext = (ModelCamelContext) camelContext;
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                RouteDefinition route = findRequestRoute(
                        modelCamelContext.getRouteDefinitions(),
                        binding.definition()
                );
                bindingsByRoute.computeIfAbsent(route, ignored -> new ArrayList<>()).add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        String requestNodeId = requestNodeId(binding.definition());
                        advice.weaveById(requestNodeId)
                                .before()
                                .process(exchange -> redirectRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            for (SnapshotFixtureBinding binding : bindings) {
                SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
                if (interaction != null) {
                    stubsByFixtureId.get(binding.definition().getId()).beforeInvocation(interaction);
                }
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            for (SnapshotFixtureBinding binding : bindings) {
                SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
                if (interaction != null) {
                    stubsByFixtureId.get(binding.definition().getId()).verify(
                            interaction,
                            invocation.getId()
                    );
                }
            }
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() {
            List<ServiceCallStub> stubs = new ArrayList<>(stubsByFixtureId.values());
            for (int index = stubs.size() - 1; index >= 0; index--) {
                stubs.get(index).close();
            }
        }

        private void redirectRequest(SnapshotFixtureBinding binding, Exchange exchange) {
            stubsByFixtureId.get(binding.definition().getId()).redirect(exchange);
        }
    }

    private static final class ServiceCallStub implements AutoCloseable {
        private static final String SERVICE_CALL_ADDRESS = "serviceCallAddress";
        private static final String SERVICE_CALL_URL = "serviceCallUrl";

        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private WireMockServer server;
        private int httpRequestBaseline;
        private int preparedRequestBaseline;

        private ServiceCallStub(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void start() {
            server = new WireMockServer(options()
                    .dynamicPort()
                    .bindAddress("127.0.0.1"));
            server.start();
        }

        private void beforeInvocation(SnapshotFixtureInteraction interaction) throws IOException {
            SnapshotFixtureResponse response = interaction.getResponse();
            if (response == null) {
                throw new IllegalArgumentException(
                        "HTTP Service Call fixture '" + binding.definition().getId()
                                + "' must define a response for every invocation."
                );
            }

            httpRequestBaseline = server.getAllServeEvents().size();
            preparedRequestBaseline = preparedRequests.size();
            server.resetMappings();
            ResponseDefinitionBuilder responseDefinition = aResponse()
                    .withStatus(response.getStatus())
                    .withBody(responseBody(response));
            response.getHeaders().forEach((name, value) ->
                    responseDefinition.withHeader(name, String.valueOf(value)));
            server.stubFor(any(anyUrl()).willReturn(responseDefinition));
        }

        private void redirect(Exchange exchange) {
            String pathAndQuery = resolvePathAndQuery(exchange);
            preparedRequests.add(new PreparedRequest(
                    immutableMap(exchange.getProperties())
            ));
            exchange.getMessage().setHeader(
                    Exchange.HTTP_URI,
                    server.baseUrl() + pathAndQuery
            );
        }

        private byte[] responseBody(SnapshotFixtureResponse response) throws IOException {
            Object body = response.getBody();
            if (body == null) {
                return new byte[0];
            }
            if (body instanceof byte[] bytes) {
                return bytes;
            }
            if (body instanceof String string) {
                return string.getBytes(StandardCharsets.UTF_8);
            }
            return objectMapper.writeValueAsBytes(body);
        }

        private void verify(
                SnapshotFixtureInteraction interaction,
                String invocationId
        ) {
            SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
            if (expectation == null) {
                return;
            }

            String fixtureId = binding.definition().getId();
            List<LoggedRequest> httpRequests = SnapshotHttpRequests.since(server, httpRequestBaseline);
            List<PreparedRequest> invocationPreparedRequests = List.copyOf(
                    preparedRequests.subList(preparedRequestBaseline, preparedRequests.size())
            );
            assertEquals(
                    expectation.getCount(),
                    httpRequests.size(),
                    () -> "HTTP Service Call fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' received an unexpected number of requests."
            );
            assertEquals(
                    httpRequests.size(),
                    invocationPreparedRequests.size(),
                    () -> "HTTP Service Call fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' prepared a request that did not reach the stub server."
            );
            for (int index = 0; index < httpRequests.size(); index++) {
                verifyRequest(
                        fixtureId,
                        index + 1,
                        expectation,
                        httpRequests.get(index),
                        invocationPreparedRequests.get(index)
                );
            }
        }

        private void verifyRequest(
                String fixtureId,
                int requestNumber,
                SnapshotFixtureRequestExpectation expectation,
                LoggedRequest request,
                PreparedRequest preparedRequest
        ) {
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
                String requestBody = request.getBody().length == 0 ? null : request.getBodyAsString();
                assertEquals(
                        expectedBody(expectation.getBody(), requestBody),
                        requestBody,
                        () -> requestFailure(fixtureId, requestNumber, "body")
                );
            }
            SnapshotValueAssertions.assertMapValues(
                    expectation.getHeaders(),
                    SnapshotHttpRequests.immutableHeaders(request.getHeaders()),
                    "HTTP Service Call fixture '" + fixtureId + "' request " + requestNumber
                            + " has an unexpected header"
            );
            SnapshotValueAssertions.assertMapValues(
                    expectation.getProperties(),
                    preparedRequest.properties(),
                    "HTTP Service Call fixture '" + fixtureId + "' request " + requestNumber
                            + " has an unexpected property"
            );
        }

        private Object expectedBody(Object expectedBody, String actualBody) {
            if (expectedBody == null || expectedBody instanceof String || actualBody == null) {
                return expectedBody;
            }
            try {
                return objectMapper.writeValueAsString(expectedBody);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Cannot serialize the expected HTTP request body.", exception);
            }
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop();
            }
        }

        private static String resolvePathAndQuery(Exchange exchange) {
            String serviceCallUrl = requiredProperty(exchange, SERVICE_CALL_URL).strip();
            URI uri = URI.create(serviceCallUrl);
            if (uri.isAbsolute()) {
                String path = uri.getRawPath();
                return appendQuery(path == null || path.isEmpty() ? "/" : path, uri.getRawQuery());
            }

            String serviceCallAddress = requiredProperty(exchange, SERVICE_CALL_ADDRESS).strip();
            String pathAndQuery = serviceCallUrl;
            if (!serviceCallAddress.isEmpty() && serviceCallUrl.startsWith(serviceCallAddress)) {
                pathAndQuery = serviceCallUrl.substring(serviceCallAddress.length());
            }
            if (!pathAndQuery.startsWith("/")) {
                pathAndQuery = "/" + pathAndQuery;
            }
            return pathAndQuery;
        }

        private static String requiredProperty(Exchange exchange, String name) {
            String value = exchange.getProperty(name, String.class);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "HTTP Service Call fixture cannot redirect the request because exchange property '"
                                + name + "' is missing."
                );
            }
            return value;
        }

        private static String appendQuery(String path, String query) {
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }
    }

    private static void registerRuntimeBeans(
            CamelContext camelContext,
            List<SnapshotFixtureBinding> bindings
    ) {
        Registry registry = camelContext.getRegistry();
        Processor noOpProcessor = exchange -> {
        };

        bindProcessor(registry, "httpProducerCharsetProcessor", new HttpProducerCharsetProcessor());
        bindProcessor(registry, "queryParameterFilterProcessor", new QueryParameterFilterProcessor());
        bindProcessor(registry, "httpSenderProcessor", new HttpSenderProcessor());
        bindProcessor(
                registry,
                "setCaughtHttpExceptionContextProcessor",
                new SetCaughtHttpExceptionContextProcessor()
        );
        bindProcessor(registry, "throwCaughtExceptionProcessor", new ThrowCaughtExceptionProcessor());
        bindProcessor(registry, "contextPropagationProcessor", noOpProcessor);
        bindProcessor(registry, "contextRestoreProcessor", noOpProcessor);

        for (SnapshotFixtureBinding binding : bindings) {
            String beanName = binding.definition().getNodeId();
            if (registry.lookupByNameAndType(beanName, HttpClientConfigurer.class) == null) {
                throw new IllegalStateException(
                        "HTTP Service Call fixture '" + binding.definition().getId()
                                + "' requires generated HTTP client configurer '" + beanName + "'."
                );
            }
        }
    }

    private static void bindProcessor(Registry registry, String name, Processor processor) {
        registry.bind(name, Processor.class, processor);
    }

    private static RouteDefinition findRequestRoute(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition definition
    ) {
        return SnapshotRouteNodes.requireSingle(
                SnapshotRouteNodes.findById(routes, requestNodeId(definition)),
                "HTTP Service Call fixture '" + definition.getId() + "' expected one request node '"
                        + requestNodeId(definition) + "' in deployment '" + definition.getDeploymentId() + "'"
        ).route();
    }

    private static String requestNodeId(SnapshotFixtureDefinition definition) {
        return REQUEST_NODE_PREFIX + definition.getNodeId();
    }

    private static String requestFailure(String fixtureId, int requestNumber, String valueType) {
        return "HTTP Service Call fixture '" + fixtureId + "' request " + requestNumber
                + " has an unexpected " + valueType + ".";
    }

    private record PreparedRequest(
            Map<String, Object> properties
    ) {
    }
}
