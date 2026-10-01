package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.trafficlistener.DoNothingWiremockNetworkTrafficListener;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.qubership.integration.platform.engine.camel.processors.HttpProducerCharsetProcessor;
import org.qubership.integration.platform.engine.camel.processors.HttpSenderProcessor;
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
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class HttpSenderSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "http-sender-http";
    private static final String EXPECTED_SEND_COUNT = "expectedSendCount";
    private static final String EXPECTED_RESTORED_CONTEXT = "expectedRestoredContext";
    private static final String EXPECTED_CONNECTION_COUNT = "expectedConnectionCount";
    private static final String TRANSPORT_FAULT = "transportFault";
    private static final String CONNECTION_REFUSED = "CONNECTION_REFUSED";
    private static final String REDIRECTS = "redirects";
    private static final String EXPECTED_REQUESTS = "expectedRequests";
    private static final Set<String> RESPONSE_PROPERTIES = Set.of(EXPECTED_SEND_COUNT, EXPECTED_RESTORED_CONTEXT,
            EXPECTED_CONNECTION_COUNT, TRANSPORT_FAULT, REDIRECTS, EXPECTED_REQUESTS);
    private static final ObjectMapper EXPECTED_REQUEST_MAPPER = ObjectMappers.getObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

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
        bindings.forEach(HttpSenderSnapshotFixtureProvider::validateBinding);
        return new HttpSenderSnapshotFixture(deploymentId, bindings);
    }

    private static final class HttpSenderSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, HttpSenderStub> stubs = new LinkedHashMap<>();
        private final SnapshotContextRuntime runtime;

        private HttpSenderSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            this.runtime = new SnapshotContextRuntime(this.bindings, List.of(new HttpSenderSnapshotContextProvider()));
            this.bindings.forEach(binding -> stubs.put(binding.definition().getId(), new HttpSenderStub(binding)));
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() {
            stubs.values().forEach(HttpSenderStub::start);
        }

        @Override
        public void configure(CamelContext context) throws Exception {
            configure(context, ((ModelCamelContext) context).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext context, List<RouteDefinition> routes) throws Exception {
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                SnapshotFixtureDefinition definition = binding.definition();
                var sender = SnapshotRouteNodes.requireSingle(
                        SnapshotRouteNodes.findById(routes, definition.getNodeId()),
                        "HTTP sender fixture '" + definition.getId() + "' requires one sender node '"
                                + definition.getNodeId() + "' in deployment '" + deploymentId + "'"
                );
                ToDynamicDefinition endpoint = SnapshotRouteNodes.requireSingle(
                        SnapshotRouteNodes.findDynamicEndpoints(sender.definition(), "http:"),
                        "HTTP sender fixture '" + definition.getId() + "' requires one HTTP dynamic endpoint"
                );
                endpoint.setId(requestNodeId(definition));
                if (context.getRegistry().lookupByNameAndType(definition.getNodeId(), HttpClientConfigurer.class) == null) {
                    throw new IllegalStateException("HTTP sender fixture '" + definition.getId()
                            + "' requires generated HTTP client configurer '" + definition.getNodeId() + "'.");
                }
                bindingsByRoute.computeIfAbsent(sender.route(), ignored -> new ArrayList<>()).add(binding);
            }

            List<RouteDefinition> senderRoutes = List.copyOf(bindingsByRoute.keySet());
            SnapshotFixtureRouteScope.bind(context, senderRoutes, PROVIDER_ID + ':' + deploymentId, Map.of(
                    "httpSenderProcessor", new HttpSenderProcessor(),
                    "httpProducerCharsetProcessor", new HttpProducerCharsetProcessor(),
                    "setCaughtHttpExceptionContextProcessor", new SetCaughtHttpExceptionContextProcessor(),
                    "throwCaughtExceptionProcessor", new ThrowCaughtExceptionProcessor()
            ));
            runtime.configure(context, senderRoutes);
            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(context, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        HttpSenderStub stub = stubs.get(binding.definition().getId());
                        advice.weaveById(binding.definition().getNodeId()).before().process(exchange -> {
                            runtime.senderEntered(binding, exchange);
                            stub.senderEntered(exchange);
                        });
                        advice.weaveById(requestNodeId(binding.definition())).before().process(stub::redirect);
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            runtime.beforeInvocation();
            for (SnapshotFixtureBinding binding : bindings) {
                stubs.get(binding.definition().getId()).beforeInvocation(binding.interaction(invocation.getId()));
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            for (SnapshotFixtureBinding binding : bindings) {
                stubs.get(binding.definition().getId()).verify(binding.interaction(invocation.getId()), invocation.getId());
            }
            runtime.verifyInvocation(invocation);
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() {
            try {
                new ArrayList<>(stubs.values()).reversed().forEach(HttpSenderStub::close);
            } finally {
                runtime.close();
            }
        }
    }

    private static final class HttpSenderStub implements AutoCloseable {
        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<SenderEntry> senderEntries = new CopyOnWriteArrayList<>();
        private final Map<String, String> destinations = new ConcurrentHashMap<>();
        private final AtomicInteger connectionCount = new AtomicInteger();
        private WireMockServer server;
        private String baseUrl;
        private int port;
        private int requestBaseline;

        private HttpSenderStub(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void start() {
            server = new WireMockServer(options().port(port).bindAddress("127.0.0.1")
                    .networkTrafficListener(new DoNothingWiremockNetworkTrafficListener() {
                        @Override
                        public void opened(Socket socket) {
                            connectionCount.incrementAndGet();
                        }
                    }));
            server.start();
            port = server.port();
            baseUrl = "http://127.0.0.1:" + port;
        }

        private void beforeInvocation(SnapshotFixtureInteraction interaction) throws IOException {
            senderEntries.clear();
            destinations.clear();
            SnapshotFixtureResponse response = interaction.getResponse();
            if (CONNECTION_REFUSED.equals(response.getProperties().get(TRANSPORT_FAULT))) {
                requestBaseline = server.getAllServeEvents().size();
                server.stop();
                return;
            }
            if (!server.isRunning()) {
                start();
            }
            requestBaseline = server.getAllServeEvents().size();
            server.resetMappings();
            ResponseDefinitionBuilder responseDefinition = aResponse()
                    .withStatus(response.getStatus()).withBody(responseBody(response.getBody()));
            if (response.getDelayMillis() != null) {
                responseDefinition.withFixedDelay(response.getDelayMillis());
            }
            if (response.getProperties().get(TRANSPORT_FAULT) instanceof String fault) {
                responseDefinition.withFault(Fault.valueOf(fault));
            }
            response.getHeaders().forEach((name, value) -> responseDefinition.withHeader(name, String.valueOf(value)));
            List<Redirect> redirects = redirects(response.getProperties());
            String state = Scenario.STARTED;
            for (int index = 0; index < redirects.size(); index++) {
                Redirect redirect = redirects.get(index);
                String nextState = "redirect-" + (index + 1);
                server.stubFor(any(anyUrl()).inScenario(binding.definition().getId())
                        .whenScenarioStateIs(state).willSetStateTo(nextState)
                        .willReturn(aResponse().withStatus(redirect.status()).withHeader("Location", redirect.location())));
                state = nextState;
            }
            server.stubFor(any(anyUrl()).inScenario(binding.definition().getId())
                    .whenScenarioStateIs(state).willReturn(responseDefinition));
        }

        private void senderEntered(Exchange exchange) {
            senderEntries.add(new SenderEntry(exchange.getExchangeId(), immutableMap(exchange.getProperties())));
        }

        private void redirect(Exchange exchange) {
            String destination = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
            URI uri = URI.create(destination);
            destinations.put(exchange.getExchangeId(), destination);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            exchange.getMessage().setHeader(Exchange.HTTP_URI,
                    baseUrl + (path == null || path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query));
        }

        private byte[] responseBody(Object body) throws IOException {
            if (body == null) {
                return new byte[0];
            }
            if (body instanceof String string) {
                return string.getBytes(StandardCharsets.UTF_8);
            }
            return objectMapper.writeValueAsBytes(body);
        }

        private void verify(SnapshotFixtureInteraction interaction, String invocationId) throws IOException {
            String description = "HTTP sender fixture '" + binding.definition().getId() + "' invocation '" + invocationId + "'";
            SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
            Map<String, Object> properties = interaction.getResponse().getProperties();
            List<LoggedRequest> requests = SnapshotHttpRequests.since(server, requestBaseline);
            assertEquals(expected.getCount(), requests.size(), description + " received an unexpected number of requests.");
            assertEquals(properties.getOrDefault(EXPECTED_SEND_COUNT, expected.getCount()),
                    senderEntries.size(), description + " entered the sender an unexpected number of times.");
            if (properties.containsKey(EXPECTED_CONNECTION_COUNT)) {
                assertEquals(properties.get(EXPECTED_CONNECTION_COUNT), connectionCount.get(),
                        description + " opened an unexpected cumulative number of TCP connections.");
            }
            for (SenderEntry entry : senderEntries) {
                if (expected.getDestination() != null) {
                    assertEquals(expected.getDestination(), destinations.get(entry.exchangeId()),
                            description + " has an unexpected generated destination.");
                }
                SnapshotValueAssertions.assertMapValues(expected.getProperties(), entry.properties(),
                        description + " has an unexpected sender entry property");
            }
            if (properties.containsKey(EXPECTED_REQUESTS)) {
                List<SnapshotFixtureRequestExpectation> expectedRequests = expectedRequests(properties);
                assertEquals(expectedRequests.size(), requests.size(), description + " has an unexpected request sequence length.");
                verifyRequest(expected, requests.getFirst(), description + " initial request");
                for (int index = 0; index < requests.size(); index++) {
                    verifyRequest(expectedRequests.get(index), requests.get(index), description + " request " + (index + 1));
                }
            } else {
                for (LoggedRequest request : requests) {
                    verifyRequest(expected, request, description);
                }
            }
        }

        private void verifyRequest(SnapshotFixtureRequestExpectation expected, LoggedRequest request, String description)
                throws IOException {
            if (expected.getMethod() != null) {
                assertEquals(expected.getMethod(), request.getMethod().getName(), description + " has an unexpected method.");
            }
            URI requestUri = URI.create(request.getUrl());
            if (expected.getPath() != null) {
                assertEquals(expected.getPath(), requestUri.getRawPath(), description + " has an unexpected path.");
            }
            if (expected.getQuery() != null) {
                assertEquals(expected.getQuery(), requestUri.getRawQuery(), description + " has an unexpected query.");
            }
            if (expected.hasBody()) {
                Object body = expected.getBody();
                if (body == null || body instanceof String) {
                    assertEquals(body == null ? "" : body, request.getBodyAsString(), description + " has an unexpected body.");
                } else {
                    SnapshotValueAssertions.assertMatches(objectMapper.valueToTree(body), objectMapper.readTree(request.getBody()),
                            description + " has an unexpected JSON body.");
                }
            }
            SnapshotValueAssertions.assertMapValues(expected.getHeaders(), SnapshotHttpRequests.immutableHeaders(request.getHeaders()),
                    description + " has an unexpected header");
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        binding.interactionsByInvocationId().forEach((invocationId, interaction) -> {
            String description = "HTTP sender fixture '" + binding.definition().getId() + "' invocation '" + invocationId + "'";
            if (interaction.getResponse() == null || interaction.getExpectedRequest() == null) {
                throw new IllegalArgumentException(description + " must define a response and an expected request.");
            }
            Map<String, Object> properties = interaction.getResponse().getProperties();
            if (!RESPONSE_PROPERTIES.containsAll(properties.keySet())) {
                throw new IllegalArgumentException(description + " has an unsupported response property. Supported properties: "
                        + RESPONSE_PROPERTIES + ".");
            }
            for (String countProperty : List.of(EXPECTED_SEND_COUNT, EXPECTED_CONNECTION_COUNT)) {
                if (properties.containsKey(countProperty)
                        && (!(properties.get(countProperty) instanceof Integer count) || count < 0)) {
                    throw new IllegalArgumentException(description + " " + countProperty + " must be a nonnegative integer.");
                }
            }
            if (properties.containsKey(EXPECTED_RESTORED_CONTEXT)
                    && (!(properties.get(EXPECTED_RESTORED_CONTEXT) instanceof Map<?, ?> context)
                    || context.keySet().stream().anyMatch(key -> !(key instanceof String)))) {
                throw new IllegalArgumentException(description + " expectedRestoredContext must be a map with string keys.");
            }
            if (interaction.getExpectedRequest().getKey() != null) {
                throw new IllegalArgumentException(description + " does not support a key expectation.");
            }
            if (properties.containsKey(TRANSPORT_FAULT)
                    && !Set.of(Fault.EMPTY_RESPONSE.name(), Fault.CONNECTION_RESET_BY_PEER.name(), CONNECTION_REFUSED)
                    .contains(properties.get(TRANSPORT_FAULT))) {
                throw new IllegalArgumentException(description
                        + " transportFault must be EMPTY_RESPONSE, CONNECTION_RESET_BY_PEER, or CONNECTION_REFUSED.");
            }
            if (properties.containsKey(REDIRECTS)) {
                redirects(properties);
                if (!properties.containsKey(EXPECTED_REQUESTS)) {
                    throw new IllegalArgumentException(description + " redirects require expectedRequests for each request.");
                }
            }
            if (properties.containsKey(EXPECTED_REQUESTS)
                    && expectedRequests(properties).size() != interaction.getExpectedRequest().getCount()) {
                throw new IllegalArgumentException(description + " expectedRequests size must equal expectedRequest.count.");
            }
        });
    }

    private static List<Redirect> redirects(Map<String, Object> properties) {
        if (!properties.containsKey(REDIRECTS)) {
            return List.of();
        }
        if (!(properties.get(REDIRECTS) instanceof List<?> redirects) || redirects.isEmpty()) {
            throw new IllegalArgumentException("HTTP sender fixture redirects must be a nonempty list.");
        }
        return redirects.stream().map(value -> {
            if (!(value instanceof Map<?, ?> redirect) || !redirect.keySet().equals(Set.of("status", "location"))
                    || !(redirect.get("status") instanceof Integer status) || !Set.of(301, 302, 303, 307, 308).contains(status)
                    || !(redirect.get("location") instanceof String location) || !location.startsWith("/") || location.startsWith("//")) {
                throw new IllegalArgumentException("HTTP sender fixture redirects require a redirect status and an absolute path location.");
            }
            URI.create(location);
            return new Redirect(status, location);
        }).toList();
    }

    private static List<SnapshotFixtureRequestExpectation> expectedRequests(Map<String, Object> properties) {
        if (!(properties.get(EXPECTED_REQUESTS) instanceof List<?> requests) || requests.isEmpty()) {
            throw new IllegalArgumentException("HTTP sender fixture expectedRequests must be a nonempty list.");
        }
        return requests.stream().map(value -> {
            if (!(value instanceof Map<?, ?> request)
                    || !Set.of("method", "path", "query", "body", "headers").containsAll(request.keySet())) {
                throw new IllegalArgumentException("HTTP sender fixture expectedRequests entries support method, path, query, body, and headers.");
            }
            SnapshotFixtureRequestExpectation expected = EXPECTED_REQUEST_MAPPER
                    .convertValue(request, SnapshotFixtureRequestExpectation.class);
            if (expected.getMethod() == null || expected.getPath() == null || !expected.hasBody()) {
                throw new IllegalArgumentException("HTTP sender fixture expectedRequests entries require method, path, and body.");
            }
            return expected;
        }).toList();
    }

    private static String requestNodeId(SnapshotFixtureDefinition definition) {
        return "snapshot-http-request:" + definition.getNodeId();
    }

    private record SenderEntry(String exchangeId, Map<String, Object> properties) {
    }

    private record Redirect(int status, String location) {
    }
}
