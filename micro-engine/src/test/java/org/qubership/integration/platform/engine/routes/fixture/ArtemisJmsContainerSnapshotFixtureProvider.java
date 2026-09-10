package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.amqp.AMQPComponent;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.camel.spi.Registry;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class ArtemisJmsContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "artemis-jms-container";
    private static final String ARTEMIS_IMAGE = "apache/artemis:2.55.0-alpine";
    private static final String USERNAME = "snapshot";
    private static final String PASSWORD = "snapshot";
    private static final int AMQP_PORT = 61616;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXTRA_RECORD_TIMEOUT = Duration.ofMillis(250);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new ArtemisJmsContainerSnapshotFixture(deploymentId, bindings);
    }

    private static final class ArtemisJmsContainerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, JmsFixtureRuntime> runtimesByFixtureId = new LinkedHashMap<>();
        private GenericContainer<?> container;
        private ConnectionFactory connectionFactory;
        private Connection verificationConnection;

        private ArtemisJmsContainerSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                validateBinding(binding);
                runtimesByFixtureId.put(
                        binding.definition().getId(),
                        new JmsFixtureRuntime(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            container = new GenericContainer<>(DockerImageName.parse(ARTEMIS_IMAGE))
                    .withExposedPorts(AMQP_PORT)
                    .withEnv("ARTEMIS_USER", USERNAME)
                    .withEnv("ARTEMIS_PASSWORD", PASSWORD)
                    .withEnv("ANONYMOUS_LOGIN", Boolean.FALSE.toString())
                    .withStartupTimeout(STARTUP_TIMEOUT);
            container.start();

            String brokerUrl = "amqp://" + container.getHost() + ':' + container.getMappedPort(AMQP_PORT);
            connectionFactory = AMQPComponent.amqpComponent(
                    brokerUrl,
                    USERNAME,
                    PASSWORD
            ).getConnectionFactory();
            verificationConnection = connectionFactory.createConnection();
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) {
            SnapshotJmsInitialContextFactory.activate(SnapshotJmsInitialContextFactory.PROVIDER_URL, connectionFactory);
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> deploymentRoutes) throws Exception {
            requireRuntimeProcessors(camelContext);

            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                JmsProducerNode producerNode = findProducerNode(
                        deploymentRoutes,
                        binding.definition()
                );
                JmsEndpointUri endpointUri = JmsEndpointUri.parse(
                        producerNode.definition().getUri(),
                        binding.definition()
                );
                JmsComponent component = camelContext.getRegistry().lookupByNameAndType(
                        endpointUri.componentName(),
                        JmsComponent.class
                );
                if (component == null) {
                    throw new IllegalStateException(
                            "Artemis JMS container fixture '" + binding.definition().getId()
                                    + "' requires generated JMS component '" + endpointUri.componentName() + "'."
                    );
                }
                assertSame(connectionFactory, component.getConfiguration().getConnectionFactory(),
                        "Generated JMS component must use the fixture's JNDI connection factory.");

                Session session = verificationConnection.createSession(Session.AUTO_ACKNOWLEDGE);
                MessageConsumer consumer = session.createConsumer(
                        session.createQueue(endpointUri.destinationName())
                );
                runtimesByFixtureId.get(binding.definition().getId()).configure(
                        endpointUri.destinationName(),
                        session,
                        consumer
                );
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }
            verificationConnection.start();

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
            runtimesByFixtureId.values().forEach(JmsFixtureRuntime::verify);
        }

        @Override
        public void close() throws Exception {
            if (connectionFactory != null) {
                SnapshotJmsInitialContextFactory.deactivate(SnapshotJmsInitialContextFactory.PROVIDER_URL, connectionFactory);
            }
            Exception cleanupFailure = null;
            for (JmsFixtureRuntime runtime : runtimesByFixtureId.values()) {
                try {
                    runtime.close();
                } catch (Exception exception) {
                    cleanupFailure = appendFailure(cleanupFailure, exception);
                }
            }
            if (verificationConnection != null) {
                try {
                    verificationConnection.close();
                } catch (Exception exception) {
                    cleanupFailure = appendFailure(cleanupFailure, exception);
                } finally {
                    verificationConnection = null;
                    connectionFactory = null;
                }
            }
            if (container != null) {
                try {
                    container.stop();
                } catch (Exception exception) {
                    cleanupFailure = appendFailure(cleanupFailure, exception);
                } finally {
                    container = null;
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
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

    private static final class JmsFixtureRuntime implements AutoCloseable {
        private final SnapshotFixtureBinding binding;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private String destinationName;
        private Session session;
        private MessageConsumer consumer;

        private JmsFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void configure(
                String destinationName,
                Session session,
                MessageConsumer consumer
        ) {
            this.destinationName = destinationName;
            this.session = session;
            this.consumer = consumer;
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private void verify() {
            SnapshotFixtureRequestExpectation expectation = binding.interaction().getExpectedRequest();
            String fixtureId = binding.definition().getId();
            List<JmsRecord> records = readRecords(consumer, expectation.getCount(), fixtureId);
            assertEquals(
                    expectation.getCount(),
                    records.size(),
                    () -> "Artemis JMS container fixture '" + fixtureId
                            + "' received an unexpected number of records."
            );
            assertEquals(
                    records.size(),
                    preparedRequests.size(),
                    () -> "Artemis JMS container fixture '" + fixtureId
                            + "' prepared a record that did not reach Artemis."
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
                JmsRecord record,
                PreparedRequest preparedRequest
        ) {
            assertEquals(
                    expectation.getDestination(),
                    record.destinationName(),
                    () -> recordFailure(fixtureId, recordNumber, "destination")
            );
            assertEquals(
                    destinationName,
                    record.destinationName(),
                    () -> recordFailure(fixtureId, recordNumber, "queue")
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
                    record.properties(),
                    "Artemis JMS container fixture '" + fixtureId + "' record " + recordNumber
                            + " has an unexpected header"
            );
            SnapshotValueAssertions.assertMapValues(
                    expectation.getProperties(),
                    preparedRequest.properties(),
                    "Artemis JMS container fixture '" + fixtureId + "' record " + recordNumber
                            + " has an unexpected property"
            );
        }

        private String expectedBody(Object body) {
            if (body == null) {
                return "";
            }
            if (body instanceof String stringBody) {
                return stringBody;
            }
            try {
                return objectMapper.writeValueAsString(body);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Cannot serialize the expected Artemis JMS message body.",
                        exception
                );
            }
        }

        @Override
        public void close() throws JMSException {
            JMSException cleanupFailure = null;
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (JMSException exception) {
                    cleanupFailure = exception;
                } finally {
                    consumer = null;
                }
            }
            if (session != null) {
                try {
                    session.close();
                } catch (JMSException exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                } finally {
                    session = null;
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private static List<JmsRecord> readRecords(
            MessageConsumer consumer,
            int expectedCount,
            String fixtureId
    ) {
        List<JmsRecord> records = new ArrayList<>();
        long deadline = System.nanoTime() + RECORD_TIMEOUT.toNanos();
        try {
            while (records.size() < expectedCount && System.nanoTime() < deadline) {
                long remainingMillis = Math.max(
                        1,
                        Duration.ofNanos(deadline - System.nanoTime()).toMillis()
                );
                Message message = consumer.receive(remainingMillis);
                if (message == null) {
                    break;
                }
                records.add(toRecord(message, fixtureId));
            }

            Message extraMessage = consumer.receive(EXTRA_RECORD_TIMEOUT.toMillis());
            if (extraMessage != null) {
                records.add(toRecord(extraMessage, fixtureId));
                while ((extraMessage = consumer.receiveNoWait()) != null) {
                    records.add(toRecord(extraMessage, fixtureId));
                }
            }
            return List.copyOf(records);
        } catch (JMSException exception) {
            throw new IllegalStateException(
                    "Cannot read messages for Artemis JMS container fixture '" + fixtureId + "'.",
                    exception
            );
        }
    }

    private static JmsRecord toRecord(Message message, String fixtureId) throws JMSException {
        BytesMessage bytesMessage = assertInstanceOf(
                BytesMessage.class,
                message,
                () -> "Artemis JMS container fixture '" + fixtureId
                        + "' received a message that is not a BytesMessage."
        );
        return new JmsRecord(
                destinationName(message.getJMSDestination(), fixtureId),
                bytesMessageBody(bytesMessage, fixtureId),
                immutableJmsProperties(message)
        );
    }

    private static String destinationName(Destination destination, String fixtureId) throws JMSException {
        Queue queue = assertInstanceOf(
                Queue.class,
                destination,
                () -> "Artemis JMS container fixture '" + fixtureId
                        + "' received a message whose JMS destination is not a queue."
        );
        return queue.getQueueName();
    }

    private static String bytesMessageBody(BytesMessage message, String fixtureId) throws JMSException {
        long bodyLength = message.getBodyLength();
        if (bodyLength > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Artemis JMS container fixture '" + fixtureId
                            + "' received a message body larger than the supported test limit."
            );
        }
        byte[] body = message.getBody(byte[].class);
        if (body.length != bodyLength) {
            throw new IllegalStateException(
                    "Artemis JMS container fixture '" + fixtureId
                            + "' could not read the complete message body."
            );
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> immutableJmsProperties(Message message) throws JMSException {
        Map<String, Object> properties = new TreeMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            properties.put(name, message.getObjectProperty(name));
        }
        return Collections.unmodifiableMap(properties);
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        String fixtureId = binding.definition().getId();
        SnapshotFixtureRequestExpectation expectation =
                SnapshotFixtureValidation.requireMessageExpectation(binding, "Artemis JMS container");
        if (expectation.getMethod() != null
                || expectation.getPath() != null
                || expectation.getQuery() != null
                || expectation.getKey() != null) {
            throw new IllegalArgumentException(
                    "Artemis JMS container fixture '" + fixtureId
                            + "' does not support HTTP method, path, query, or key expectations."
            );
        }
    }

    private static void requireRuntimeProcessors(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        for (String name : List.of("contextPropagationProcessor", "contextRestoreProcessor")) {
            if (registry.lookupByNameAndType(name, Processor.class) == null) {
                throw new IllegalStateException("Artemis JMS container fixture requires runtime processor '"
                        + name + "'.");
            }
        }
    }

    private static JmsProducerNode findProducerNode(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition definition
    ) {
        List<JmsProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, definition.getNodeId())) {
            if (!(match.definition() instanceof ToDefinition toDefinition)) {
                throw new IllegalArgumentException(
                        "Artemis JMS container fixture '" + definition.getId() + "' node '"
                                + definition.getNodeId() + "' is not a static endpoint."
                );
            }
            matchingNodes.add(new JmsProducerNode(match.route(), toDefinition));
        }
        return SnapshotRouteNodes.requireSingle(
                matchingNodes,
                "Artemis JMS container fixture '" + definition.getId() + "' expected one endpoint node '"
                        + definition.getNodeId() + "' in deployment '" + definition.getDeploymentId() + "'"
        );
    }

    private static String recordFailure(
            String fixtureId,
            int recordNumber,
            String valueType
    ) {
        return "Artemis JMS container fixture '" + fixtureId + "' record " + recordNumber
                + " has an unexpected " + valueType + ".";
    }

    private static Exception appendFailure(Exception currentFailure, Exception newFailure) {
        if (currentFailure == null) {
            return newFailure;
        }
        currentFailure.addSuppressed(newFailure);
        return currentFailure;
    }

    private record JmsProducerNode(
            RouteDefinition route,
            ToDefinition definition
    ) {
    }

    private record PreparedRequest(
            Map<String, Object> properties
    ) {
    }

    private record JmsRecord(
            String destinationName,
            String body,
            Map<String, Object> properties
    ) {
    }

    private record JmsEndpointUri(
            String componentName,
            String destinationName
    ) {
        private static JmsEndpointUri parse(
                String uri,
                SnapshotFixtureDefinition fixtureDefinition
        ) {
            int queryStart = uri.indexOf('?');
            String endpoint = queryStart < 0 ? uri : uri.substring(0, queryStart);
            int componentEnd = endpoint.indexOf(':');
            int destinationTypeEnd = componentEnd < 0
                    ? -1
                    : endpoint.indexOf(':', componentEnd + 1);
            if (componentEnd < 0 || destinationTypeEnd < 0) {
                throw invalidEndpoint(fixtureDefinition, uri);
            }

            String componentName = endpoint.substring(0, componentEnd);
            String expectedComponentName = "jms-" + fixtureDefinition.getNodeId();
            if (!expectedComponentName.equals(componentName)) {
                throw invalidEndpoint(fixtureDefinition, uri);
            }

            String destinationType = endpoint.substring(componentEnd + 1, destinationTypeEnd);
            if (!"queue".equals(destinationType)) {
                throw new IllegalArgumentException(
                        "Artemis JMS container fixture '" + fixtureDefinition.getId()
                                + "' supports queue endpoints, but found destination type '"
                                + destinationType + "'."
                );
            }

            String destinationName = endpoint.substring(destinationTypeEnd + 1);
            requireStaticDestination(destinationName, fixtureDefinition.getId());
            return new JmsEndpointUri(componentName, destinationName);
        }

        private static IllegalArgumentException invalidEndpoint(
                SnapshotFixtureDefinition fixtureDefinition,
                String uri
        ) {
            return new IllegalArgumentException(
                    "Artemis JMS container fixture '" + fixtureDefinition.getId()
                            + "' expected endpoint component 'jms-" + fixtureDefinition.getNodeId()
                            + "', but found '" + uri + "'."
            );
        }

        private static void requireStaticDestination(String value, String fixtureId) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "Artemis JMS container fixture '" + fixtureId
                                + "' endpoint does not define a destination."
                );
            }
            if (value.contains("${") || value.contains("{{")) {
                throw new IllegalArgumentException(
                        "Artemis JMS container fixture '" + fixtureId
                                + "' requires a static destination."
                );
            }
        }
    }
}
