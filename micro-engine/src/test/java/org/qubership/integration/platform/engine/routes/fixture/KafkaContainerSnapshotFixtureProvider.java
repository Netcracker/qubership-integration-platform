package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.apache.http.HttpHeaders;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jspecify.annotations.NonNull;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class KafkaContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "kafka-container";
    private static final String KAFKA_COMPONENT_NAME = "kafka-custom";
    private static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
    private static final String KAFKA_CLIENT_FACTORY_PARAMETER = "kafkaClientFactory";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECORD_POLL_TIMEOUT = Duration.ofMillis(500);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new KafkaContainerSnapshotFixture(deploymentId, bindings);
    }

    private static final class KafkaContainerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, KafkaFixtureRuntime> runtimesByFixtureId = new LinkedHashMap<>();
        private KafkaContainer container;

        private KafkaContainerSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                SnapshotFixtureRequestExpectation expectation =
                        SnapshotFixtureValidation.requireMessageExpectation(binding, "Kafka container");
                if (expectation.getMethod() != null
                        || expectation.getPath() != null
                        || expectation.getQuery() != null) {
                    throw new IllegalArgumentException(
                            "Kafka container fixture '" + binding.definition().getId()
                                    + "' does not support HTTP method, path, or query expectations."
                    );
                }
                runtimesByFixtureId.put(
                        binding.definition().getId(),
                        new KafkaFixtureRuntime(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() {
            container = new KafkaContainer(KAFKA_IMAGE)
                    .withStartupTimeout(STARTUP_TIMEOUT);
            container.start();
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            CamelExchangeContextPropagation contextPropagation = registerRuntimeBeans(camelContext);
            camelContext.addComponent(KAFKA_COMPONENT_NAME, new KafkaCustomComponent(camelContext));

            ModelCamelContext modelCamelContext = (ModelCamelContext) camelContext;
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                KafkaProducerNode producerNode = findProducerNode(
                        modelCamelContext.getRouteDefinitions(),
                        binding.definition()
                );
                KafkaEndpointUri endpointUri = KafkaEndpointUri.parse(
                        producerNode.definition().getUri(),
                        binding.definition().getId()
                );
                producerNode.definition().setUri(endpointUri.withBroker(container.getBootstrapServers()));
                requireKafkaClientFactory(camelContext, endpointUri);

                runtimesByFixtureId.get(binding.definition().getId()).setDestination(
                        endpointUri.destination()
                );
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    advice.weaveAddFirst()
                            .process(exchange -> initializeRequestContext(contextPropagation, exchange));
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
            runtimesByFixtureId.values().forEach(runtime -> runtime.verify(container.getBootstrapServers()));
        }

        @Override
        public void close() {
            if (container != null) {
                try {
                    container.stop();
                } finally {
                    container = null;
                }
            }
        }

        private void capturePreparedRequest(SnapshotFixtureBinding binding, Exchange exchange) {
            Object body = exchange.getMessage().getBody();
            if (body instanceof Iterable<?> || body instanceof Iterator<?>) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + binding.definition().getId()
                                + "' does not support iterable request bodies."
                );
            }
            runtimesByFixtureId.get(binding.definition().getId()).addPreparedRequest(
                    new PreparedRequest(immutableMap(exchange.getProperties()))
            );
        }
    }

    private static final class KafkaFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private String destination;

        private KafkaFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void setDestination(String destination) {
            this.destination = destination;
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private void verify(String bootstrapServers) {
            SnapshotFixtureRequestExpectation expectation = binding.interaction().getExpectedRequest();
            String fixtureId = binding.definition().getId();
            List<ConsumerRecord<String, String>> records = readRecords(
                    bootstrapServers,
                    destination
            );
            assertEquals(
                    expectation.getCount(),
                    records.size(),
                    () -> "Kafka container fixture '" + fixtureId
                            + "' received an unexpected number of records."
            );
            assertEquals(
                    records.size(),
                    preparedRequests.size(),
                    () -> "Kafka container fixture '" + fixtureId
                            + "' prepared a record that did not reach Kafka."
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
                ConsumerRecord<String, String> record,
                PreparedRequest preparedRequest
        ) {
            assertEquals(
                    expectation.getDestination(),
                    record.topic(),
                    () -> recordFailure(fixtureId, recordNumber, "destination")
            );
            if (expectation.getKey() != null) {
                assertEquals(
                        expectation.getKey(),
                        record.key(),
                        () -> recordFailure(fixtureId, recordNumber, "key")
                );
            }
            if (expectation.hasBody()) {
                assertEquals(
                        expectedBody(expectation.getBody()),
                        record.value(),
                        () -> recordFailure(fixtureId, recordNumber, "body")
                );
            }
            assertExpectedValues(
                    fixtureId,
                    recordNumber,
                    "header",
                    expectation.getHeaders(),
                    immutableKafkaHeaders(record)
            );
            assertExpectedValues(
                    fixtureId,
                    recordNumber,
                    "property",
                    expectation.getProperties(),
                    preparedRequest.properties()
            );
        }

        private Object expectedBody(Object body) {
            if (body == null) {
                return null;
            }
            if (body instanceof String) {
                return body;
            }
            try {
                return objectMapper.writeValueAsString(body);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Cannot serialize the expected Kafka record body.", exception);
            }
        }
    }

    private static List<ConsumerRecord<String, String>> readRecords(
            String bootstrapServers,
            String destination
    ) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> topicPartitions = consumer.partitionsFor(destination, METADATA_TIMEOUT).stream()
                    .map(partition -> new TopicPartition(destination, partition.partition()))
                    .toList();
            if (topicPartitions.isEmpty()) {
                return List.of();
            }

            consumer.assign(topicPartitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicPartitions, METADATA_TIMEOUT);
            consumer.seekToBeginning(topicPartitions);

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + METADATA_TIMEOUT.toNanos();
            while (!hasReachedEndOffsets(consumer, endOffsets) && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polledRecords = consumer.poll(RECORD_POLL_TIMEOUT);
                polledRecords.forEach(record -> {
                    TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                    if (record.offset() < endOffsets.getOrDefault(topicPartition, 0L)) {
                        records.add(record);
                    }
                });
            }
            if (!hasReachedEndOffsets(consumer, endOffsets)) {
                throw new IllegalStateException(
                        "Timed out while reading records from Kafka destination '" + destination + "'."
                );
            }
            return List.copyOf(records);
        }
    }

    private static boolean hasReachedEndOffsets(
            KafkaConsumer<String, String> consumer,
            Map<TopicPartition, Long> endOffsets
    ) {
        return endOffsets.entrySet().stream()
                .allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private static void requireKafkaClientFactory(CamelContext camelContext, KafkaEndpointUri endpointUri) {
        Registry registry = camelContext.getRegistry();
        String reference = endpointUri.parameters().get(KAFKA_CLIENT_FACTORY_PARAMETER);
        if (reference == null || !reference.startsWith("#")) {
            return;
        }

        String beanName = reference.substring(1);
        if (registry.lookupByNameAndType(beanName, KafkaBGClientFactory.class) == null) {
            throw new IllegalStateException("Micro-engine snapshot is missing Kafka client factory '" + beanName + "'.");
        }
    }

    private static CamelExchangeContextPropagation registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        CDI<Object> container = CDI.current();
        bindCdiProcessor(container, registry, "contextPropagationProcessor");
        bindCdiProcessor(container, registry, "contextRestoreProcessor");
        bindCdiProcessor(container, registry, "kafkaSenderProcessor");
        bindCdiProcessor(container, registry, "messagingXHeadersPropagationProcessor");
        bindCdiProcessor(container, registry, "messagingXHeadersPropagationRestoreProcessor");
        return requiredCdiBean(container, CamelExchangeContextPropagation.class);
    }

    private static void bindCdiProcessor(CDI<Object> cdi, Registry registry, String name) {
        Instance<Processor> processors = cdi.select(Processor.class, NamedLiteral.of(name));
        if (!processors.isResolvable()) {
            throw new IllegalStateException(
                    "Kafka container fixture cannot resolve CDI processor '" + name + "'."
            );
        }
        registry.bind(name, Processor.class, processors.get());
    }

    private static <T> T requiredCdiBean(CDI<Object> cdi, Class<T> beanType) {
        Instance<T> beans = cdi.select(beanType);
        if (!beans.isResolvable()) {
            throw new IllegalStateException(
                    "Kafka container fixture cannot resolve CDI bean '" + beanType.getName() + "'."
            );
        }
        return beans.get();
    }

    private static void initializeRequestContext(
            CamelExchangeContextPropagation contextPropagation,
            Exchange exchange
    ) {
        Map<String, Object> headers = exchange.getMessage().getHeaders();
        contextPropagation.initRequestContext(headers);
        Object authorization = exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION);
        contextPropagation.removeContextHeaders(headers);
        if (authorization != null) {
            exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        exchange.setProperty(
                REQUEST_CONTEXT_PROPAGATION_SNAPSHOT,
                contextPropagation.createContextSnapshot()
        );
    }

    private static KafkaProducerNode findProducerNode(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition definition
    ) {
        List<KafkaProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, definition.getNodeId())) {
            if (!(match.definition() instanceof ToDynamicDefinition toDynamicDefinition)) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + definition.getId() + "' node '"
                                + definition.getNodeId() + "' is not a dynamic endpoint."
                );
            }
            matchingNodes.add(new KafkaProducerNode(match.route(), toDynamicDefinition));
        }
        return SnapshotRouteNodes.requireSingle(
                matchingNodes,
                "Kafka container fixture '" + definition.getId() + "' expected one dynamic endpoint node '"
                        + definition.getNodeId() + "' in deployment '" + definition.getDeploymentId() + "'"
        );
    }

    private static void assertExpectedValues(
            String fixtureId,
            int recordNumber,
            String valueType,
            Map<String, Object> expectedValues,
            Map<String, Object> actualValues
    ) {
        expectedValues.forEach((name, expectedValue) -> {
            if (expectedValue == null) {
                assertFalse(
                        actualValues.containsKey(name),
                        () -> "Kafka container fixture '" + fixtureId + "' record " + recordNumber
                                + " has unexpected " + valueType + " '" + name + "'."
                );
                return;
            }
            assertEquals(
                    expectedValue,
                    actualValues.get(name),
                    () -> "Kafka container fixture '" + fixtureId + "' record " + recordNumber
                            + " has an unexpected " + valueType + " '" + name + "'."
            );
        });
    }

    private static String recordFailure(String fixtureId, int recordNumber, String valueType) {
        return "Kafka container fixture '" + fixtureId + "' record " + recordNumber
                + " has an unexpected " + valueType + ".";
    }

    private static Map<String, Object> immutableKafkaHeaders(ConsumerRecord<String, String> record) {
        Map<String, List<String>> headerValues = new TreeMap<>();
        for (Header header : record.headers()) {
            headerValues.computeIfAbsent(header.key(), ignored -> new ArrayList<>())
                    .add(new String(header.value(), StandardCharsets.UTF_8));
        }

        Map<String, Object> headers = new TreeMap<>();
        headerValues.forEach((name, values) -> headers.put(
                name,
                values.size() == 1 ? values.getFirst() : List.copyOf(values)
        ));
        return Collections.unmodifiableMap(headers);
    }

    private record KafkaProducerNode(
            RouteDefinition route,
            ToDynamicDefinition definition
    ) {
    }

    private record PreparedRequest(
            Map<String, Object> properties
    ) {
    }

    private record KafkaEndpointUri(
            String endpoint,
            String destination,
            Map<String, String> parameters
    ) {
        private static KafkaEndpointUri parse(String uri, String fixtureId) {
            int queryStart = uri.indexOf('?');
            String endpoint = queryStart < 0 ? uri : uri.substring(0, queryStart);
            String query = queryStart < 0 ? "" : uri.substring(queryStart + 1);
            String destination = getDestination(uri, fixtureId, endpoint);

            if (query.contains("RAW(")) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + fixtureId
                                + "' does not support RAW endpoint parameters."
                );
            }
            Map<String, String> parameters = SnapshotEndpointParameters.parse(query, name ->
                    new IllegalArgumentException(
                            "Kafka container fixture '" + fixtureId
                                    + "' does not support duplicate endpoint parameter '" + name + "'."
                    ));
            return new KafkaEndpointUri(
                    endpoint,
                    destination,
                    parameters
            );
        }

        private static @NonNull String getDestination(String uri, String fixtureId, String endpoint) {
            int schemeEnd = endpoint.indexOf(':');
            if (schemeEnd < 0 || !KAFKA_COMPONENT_NAME.equals(endpoint.substring(0, schemeEnd))) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + fixtureId
                                + "' must target a kafka-custom endpoint, but found '" + uri + "'."
                );
            }

            return getDestination(fixtureId, endpoint, schemeEnd);
        }

        private static @NonNull String getDestination(String fixtureId, String endpoint, int schemeEnd) {
            String destination = endpoint.substring(schemeEnd + 1);
            if (destination.isBlank()) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + fixtureId + "' endpoint does not define a topic."
                );
            }
            if (destination.contains("${") || destination.contains("{{")) {
                throw new IllegalArgumentException(
                        "Kafka container fixture '" + fixtureId + "' requires a static topic."
                );
            }
            return destination;
        }

        private String withBroker(String bootstrapServers) {
            Map<String, String> containerParameters = new LinkedHashMap<>(parameters);
            containerParameters.keySet().removeIf(KafkaEndpointUri::isAuthenticationParameter);
            putParameter(containerParameters, "brokers", stripProtocol(bootstrapServers));
            putParameter(containerParameters, "securityProtocol", "PLAINTEXT");

            StringBuilder uri = new StringBuilder(endpoint);
            containerParameters.forEach((name, value) -> uri
                    .append(uri.indexOf("?") < 0 ? '?' : '&')
                    .append(name)
                    .append('=')
                    .append(value));
            return uri.toString();
        }

        private static boolean isAuthenticationParameter(String name) {
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (normalizedName.startsWith("sasl") || normalizedName.startsWith("ssl")) {
                return true;
            }
            if (!normalizedName.startsWith("additionalproperties.")) {
                return false;
            }

            String additionalPropertyName = normalizedName.substring("additionalproperties.".length());
            return additionalPropertyName.startsWith("sasl")
                    || additionalPropertyName.startsWith("ssl")
                    || additionalPropertyName.equals("security.protocol")
                    || additionalPropertyName.equals("securityprotocol")
                    || additionalPropertyName.equals("bootstrap.servers")
                    || additionalPropertyName.equals("bootstrapservers");
        }

        private static void putParameter(Map<String, String> parameters, String name, String value) {
            String existingName = parameters.keySet().stream()
                    .filter(parameterName -> parameterName.equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(name);
            parameters.put(existingName, value);
        }

        private static String stripProtocol(String bootstrapServers) {
            int protocolSeparator = bootstrapServers.indexOf("://");
            return protocolSeparator < 0
                    ? bootstrapServers
                    : bootstrapServers.substring(protocolSeparator + 3);
        }
    }
}
