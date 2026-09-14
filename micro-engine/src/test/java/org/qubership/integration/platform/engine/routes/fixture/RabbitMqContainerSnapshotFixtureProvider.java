package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.MetricsCollector;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.SpringRabbitMQCustomComponent;
import org.qubership.integration.platform.engine.camel.processors.RabbitMqSenderProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class RabbitMqContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "rabbitmq-container";
    private static final String RABBITMQ_COMPONENT_NAME = "rabbitmq-custom";
    private static final String RABBITMQ_IMAGE = "rabbitmq:3.13-alpine";
    private static final String METRICS_COLLECTOR_PARAMETER = "metricsCollector";
    private static final String USERNAME = "snapshot";
    private static final String PASSWORD = "snapshot";
    private static final int AMQP_PORT = 5672;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECORD_POLL_INTERVAL = Duration.ofMillis(50);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new RabbitMqContainerSnapshotFixture(deploymentId, bindings);
    }

    private static final class RabbitMqContainerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, RabbitMqFixtureRuntime> runtimesByFixtureId = new LinkedHashMap<>();
        private GenericContainer<?> container;
        private Connection connection;

        private RabbitMqContainerSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                SnapshotFixtureRequestExpectation expectation =
                        SnapshotFixtureValidation.requireMessageExpectation(binding, "RabbitMQ container");
                if (expectation.getMethod() != null
                        || expectation.getPath() != null
                        || expectation.getQuery() != null
                        || expectation.getKey() != null) {
                    throw new IllegalArgumentException(
                            "RabbitMQ container fixture '" + binding.definition().getId()
                                    + "' does not support HTTP method, path, query, or key expectations."
                    );
                }
                runtimesByFixtureId.put(
                        binding.definition().getId(),
                        new RabbitMqFixtureRuntime(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            container = new GenericContainer<>(DockerImageName.parse(RABBITMQ_IMAGE))
                    .withExposedPorts(AMQP_PORT)
                    .withEnv("RABBITMQ_DEFAULT_USER", USERNAME)
                    .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                    .withStartupTimeout(STARTUP_TIMEOUT);
            container.start();

            ConnectionFactory connectionFactory = new ConnectionFactory();
            connectionFactory.setHost(container.getHost());
            connectionFactory.setPort(container.getMappedPort(AMQP_PORT));
            connectionFactory.setUsername(USERNAME);
            connectionFactory.setPassword(PASSWORD);
            connection = connectionFactory.newConnection();
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            registerRuntimeBeans(camelContext);
            camelContext.addComponent(
                    RABBITMQ_COMPONENT_NAME,
                    new SpringRabbitMQCustomComponent()
            );

            ModelCamelContext modelCamelContext = (ModelCamelContext) camelContext;
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                RabbitMqProducerNode producerNode = findProducerNode(
                        modelCamelContext.getRouteDefinitions(),
                        binding.definition()
                );
                RabbitMqEndpointUri endpointUri = RabbitMqEndpointUri.parse(
                        producerNode.definition().getUri(),
                        binding.definition().getId()
                );
                producerNode.definition().setUri(endpointUri.withContainerConnection(
                        container.getHost(),
                        container.getMappedPort(AMQP_PORT)
                ));
                requireMetricsCollector(
                        camelContext,
                        endpointUri,
                        binding.definition().getNodeId()
                );

                String queue = declareQueue(endpointUri);
                runtimesByFixtureId.get(binding.definition().getId()).configure(
                        endpointUri.exchange(),
                        queue
                );
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }

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
        public void verify() {
            runtimesByFixtureId.values().forEach(runtime -> runtime.verify(connection));
        }

        @Override
        public void close() throws Exception {
            Exception cleanupFailure = null;
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception exception) {
                    cleanupFailure = exception;
                } finally {
                    connection = null;
                }
            }
            if (container != null) {
                try {
                    container.stop();
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                } finally {
                    container = null;
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private String declareQueue(RabbitMqEndpointUri endpointUri) throws IOException, TimeoutException {
            try (Channel channel = connection.createChannel()) {
                channel.exchangeDeclare(
                        endpointUri.exchange(),
                        endpointUri.exchangeType(),
                        false
                );
                String queue = channel.queueDeclare().getQueue();
                channel.queueBind(
                        queue,
                        endpointUri.exchange(),
                        endpointUri.routingKey()
                );
                return queue;
            }
        }

        private void capturePreparedRequest(
                SnapshotFixtureBinding binding,
                Exchange exchange
        ) {
            runtimesByFixtureId.get(binding.definition().getId()).addPreparedRequest(
                    new PreparedRequest(immutableMap(exchange.getProperties()))
            );
        }
    }

    private static final class RabbitMqFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private String exchange;
        private String queue;

        private RabbitMqFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void configure(String exchange, String queue) {
            this.exchange = exchange;
            this.queue = queue;
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private void verify(Connection connection) {
            SnapshotFixtureRequestExpectation expectation = binding.interaction().getExpectedRequest();
            String fixtureId = binding.definition().getId();
            List<RabbitMqRecord> records = readRecords(
                    connection,
                    queue,
                    expectation.getCount()
            );
            assertEquals(
                    expectation.getCount(),
                    records.size(),
                    () -> "RabbitMQ container fixture '" + fixtureId
                            + "' received an unexpected number of records."
            );
            assertEquals(
                    records.size(),
                    preparedRequests.size(),
                    () -> "RabbitMQ container fixture '" + fixtureId
                            + "' prepared a record that did not reach RabbitMQ."
            );

            for (int index = 0; index < records.size(); index++) {
                verifyRecord(
                        fixtureId,
                        index + 1,
                        expectation,
                        records.get(index),
                        preparedRequests.get(index)
                );
            }
        }

        private void verifyRecord(
                String fixtureId,
                int recordNumber,
                SnapshotFixtureRequestExpectation expectation,
                RabbitMqRecord record,
                PreparedRequest preparedRequest
        ) {
            assertEquals(
                    expectation.getDestination(),
                    record.exchange(),
                    () -> recordFailure(fixtureId, recordNumber, "destination")
            );
            assertEquals(
                    exchange,
                    record.exchange(),
                    () -> recordFailure(fixtureId, recordNumber, "exchange")
            );
            if (expectation.hasBody()) {
                assertEquals(
                        expectedBody(expectation.getBody()),
                        record.body(),
                        () -> recordFailure(fixtureId, recordNumber, "body")
                );
            }
            SnapshotValueAssertions.assertMapValues(
                    expectation.getHeaders(),
                    record.headers(),
                    "RabbitMQ container fixture '" + fixtureId + "' record " + recordNumber
                            + " has an unexpected header"
            );
            SnapshotValueAssertions.assertMapValues(
                    expectation.getProperties(),
                    preparedRequest.properties(),
                    "RabbitMQ container fixture '" + fixtureId + "' record " + recordNumber
                            + " has an unexpected property"
            );
        }

        private Object expectedBody(Object body) {
            if (body == null) {
                return "";
            }
            if (body instanceof String) {
                return body;
            }
            try {
                return objectMapper.writeValueAsString(body);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Cannot serialize the expected RabbitMQ record body.",
                        exception
                );
            }
        }
    }

    private static List<RabbitMqRecord> readRecords(
            Connection connection,
            String queue,
            int expectedCount
    ) {
        List<RabbitMqRecord> records = new ArrayList<>();
        long deadline = System.nanoTime() + RECORD_TIMEOUT.toNanos();
        try (Channel channel = connection.createChannel()) {
            while (records.size() < expectedCount && System.nanoTime() < deadline) {
                GetResponse response = channel.basicGet(queue, true);
                if (response == null) {
                    pauseBeforeNextPoll();
                } else {
                    records.add(toRecord(response));
                }
            }

            GetResponse response;
            while ((response = channel.basicGet(queue, true)) != null) {
                records.add(toRecord(response));
            }
            return List.copyOf(records);
        } catch (IOException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Cannot read records from RabbitMQ queue '" + queue + "'.",
                    exception
            );
        }
    }

    private static void pauseBeforeNextPoll() {
        try {
            Thread.sleep(RECORD_POLL_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for RabbitMQ records.",
                    exception
            );
        }
    }

    private static RabbitMqRecord toRecord(GetResponse response) {
        return new RabbitMqRecord(
                response.getEnvelope().getExchange(),
                new String(response.getBody(), StandardCharsets.UTF_8),
                immutableRabbitHeaders(response)
        );
    }

    private static Map<String, Object> immutableRabbitHeaders(GetResponse response) {
        Map<String, Object> sourceHeaders = response.getProps().getHeaders();
        if (sourceHeaders == null || sourceHeaders.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> headers = new TreeMap<>();
        sourceHeaders.forEach((name, value) -> headers.put(name, normalizeRabbitValue(value)));
        return Collections.unmodifiableMap(headers);
    }

    private static Object normalizeRabbitValue(Object value) {
        if (value instanceof LongString longString) {
            return longString.toString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value;
    }

    private static void registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        Processor noOpProcessor = exchange -> {
        };

        bindProcessor(registry, "contextPropagationProcessor", noOpProcessor);
        bindProcessor(registry, "contextRestoreProcessor", noOpProcessor);
        bindProcessor(registry, "rabbitMqSenderProcessor", new RabbitMqSenderProcessor());
        bindProcessor(registry, "messagingXHeadersPropagationProcessor", noOpProcessor);
        bindProcessor(registry, "messagingXHeadersPropagationRestoreProcessor", noOpProcessor);
    }

    private static void bindProcessor(Registry registry, String name, Processor processor) {
        registry.bind(name, Processor.class, processor);
    }

    private static void requireMetricsCollector(
            CamelContext camelContext,
            RabbitMqEndpointUri endpointUri,
            String nodeId
    ) {
        Registry registry = camelContext.getRegistry();
        String reference = endpointUri.parameters().get(METRICS_COLLECTOR_PARAMETER);
        if (reference == null || !reference.startsWith("#")) {
            throw new IllegalArgumentException(
                    "RabbitMQ container fixture node '" + nodeId
                            + "' does not define a metrics collector reference."
            );
        }

        String beanName = reference.substring(1);
        if (registry.lookupByNameAndType(beanName, MetricsCollector.class) == null) {
            throw new IllegalStateException("Micro-engine snapshot is missing RabbitMQ metrics collector '" + beanName + "'.");
        }
    }

    private static RabbitMqProducerNode findProducerNode(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition definition
    ) {
        List<RabbitMqProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, definition.getNodeId())) {
            if (!(match.definition() instanceof ToDynamicDefinition toDynamicDefinition)) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + definition.getId() + "' node '"
                                + definition.getNodeId() + "' is not a dynamic endpoint."
                );
            }
            matchingNodes.add(new RabbitMqProducerNode(match.route(), toDynamicDefinition));
        }
        return SnapshotRouteNodes.requireSingle(
                matchingNodes,
                "RabbitMQ container fixture '" + definition.getId() + "' expected one dynamic endpoint node '"
                        + definition.getNodeId() + "' in deployment '" + definition.getDeploymentId() + "'"
        );
    }

    private static String recordFailure(
            String fixtureId,
            int recordNumber,
            String valueType
    ) {
        return "RabbitMQ container fixture '" + fixtureId + "' record " + recordNumber
                + " has an unexpected " + valueType + ".";
    }

    private record RabbitMqProducerNode(
            RouteDefinition route,
            ToDynamicDefinition definition
    ) {
    }

    private record PreparedRequest(
            Map<String, Object> properties
    ) {
    }

    private record RabbitMqRecord(
            String exchange,
            String body,
            Map<String, Object> headers
    ) {
    }

    private record RabbitMqEndpointUri(
            String endpoint,
            String exchange,
            String routingKey,
            String exchangeType,
            Map<String, String> parameters
    ) {
        private static RabbitMqEndpointUri parse(String uri, String fixtureId) {
            int queryStart = uri.indexOf('?');
            String endpoint = queryStart < 0 ? uri : uri.substring(0, queryStart);
            String query = queryStart < 0 ? "" : uri.substring(queryStart + 1);
            int schemeEnd = endpoint.indexOf(':');
            if (schemeEnd < 0
                    || !RABBITMQ_COMPONENT_NAME.equals(endpoint.substring(0, schemeEnd))) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' must target a rabbitmq-custom endpoint, but found '" + uri + "'."
                );
            }

            String exchange = endpoint.substring(schemeEnd + 1);
            requireStaticValue(exchange, fixtureId, "exchange");

            if (query.contains("RAW(")) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' does not support RAW endpoint parameters."
                );
            }
            Map<String, String> parameters = SnapshotEndpointParameters.parse(query, name ->
                    new IllegalArgumentException(
                            "RabbitMQ container fixture '" + fixtureId
                                    + "' does not support duplicate endpoint parameter '" + name + "'."
                    ));
            String routingKey = parameters.getOrDefault("routingKey", "");
            if (!routingKey.isEmpty()) {
                requireStaticValue(routingKey, fixtureId, "routing key");
            }
            String exchangeType = parameters.getOrDefault("exchangeType", "direct")
                    .toLowerCase(Locale.ROOT);
            return new RabbitMqEndpointUri(
                    endpoint,
                    exchange,
                    routingKey,
                    exchangeType,
                    parameters
            );
        }

        private String withContainerConnection(String host, int port) {
            Map<String, String> containerParameters = new LinkedHashMap<>(parameters);
            containerParameters.remove("sslProtocol");
            containerParameters.remove("trustManager");
            containerParameters.put("addresses", host + ':' + port);
            containerParameters.put("username", USERNAME);
            containerParameters.put("password", PASSWORD);
            containerParameters.put("vhost", "/");

            String query = containerParameters.entrySet().stream()
                    .map(entry -> entry.getKey() + '=' + entry.getValue())
                    .collect(Collectors.joining("&"));
            return endpoint + '?' + query;
        }

        private static void requireStaticValue(
                String value,
                String fixtureId,
                String valueName
        ) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' endpoint does not define an " + valueName + "."
                );
            }
            if (value.contains("${") || value.contains("{{")) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' requires a static " + valueName + "."
                );
            }
        }
    }
}
