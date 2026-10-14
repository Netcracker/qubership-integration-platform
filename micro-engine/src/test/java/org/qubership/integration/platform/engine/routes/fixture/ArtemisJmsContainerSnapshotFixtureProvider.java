package org.qubership.integration.platform.engine.routes.fixture;

import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class ArtemisJmsContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "artemis-jms-container";

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
        private final SnapshotJmsBrokerControl broker = new SnapshotJmsBrokerControl();
        private final SnapshotContextRuntime contextRuntime;
        private Connection verificationConnection;

        private ArtemisJmsContainerSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            contextRuntime = new SnapshotContextRuntime(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                validateBinding(binding);
                runtimesByFixtureId.put(binding.definition().getId(), new JmsFixtureRuntime(binding));
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            broker.start();
            verificationConnection = broker.createVerificationConnection();
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) {
            SnapshotJmsInitialContextFactory.activate(SnapshotJmsInitialContextFactory.PROVIDER_URL, broker.connectionFactory());
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> deploymentRoutes) throws Exception {
            contextRuntime.configure(camelContext, deploymentRoutes);
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                JmsProducerNode producer = findProducerNode(deploymentRoutes, binding.definition());
                JmsEndpointUri endpoint = JmsEndpointUri.parse(producer.definition().getUri(), binding.definition());
                JmsComponent component = camelContext.getRegistry().lookupByNameAndType(endpoint.componentName(), JmsComponent.class);
                if (component == null) {
                    throw new IllegalStateException("Artemis JMS fixture '" + binding.definition().getId()
                            + "' requires generated JMS component '" + endpoint.componentName() + "'.");
                }
                assertSame(broker.connectionFactory(), component.getConfiguration().getConnectionFactory(),
                        "Generated JMS component must use the fixture's JNDI connection factory.");
                runtimesByFixtureId.get(binding.definition().getId()).configure(endpoint, verificationConnection);
                bindingsByRoute.computeIfAbsent(producer.route(), ignored -> new ArrayList<>()).add(binding);
            }
            verificationConnection.start();
            for (var entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId()).before().process(exchange -> capturePreparedRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            contextRuntime.beforeInvocation();
            Map<String, Object> brokerProperties = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                Map<String, Object> properties = JmsSnapshotRecords.responseProperties(binding.interaction(invocation.getId()).getResponse());
                if (properties.containsKey("brokerFault")) {
                    brokerProperties.put("brokerFault", properties.get("brokerFault"));
                }
                JmsFixtureRuntime runtime = runtimesByFixtureId.get(binding.definition().getId());
                runtime.preparedRequests.clear();
                SnapshotJmsInitialContextFactory.applyBinding(SnapshotJmsInitialContextFactory.PROVIDER_URL,
                        runtime.endpoint.destinationName(), properties);
            }
            if (SnapshotJmsBrokerControl.disconnectsObservers(brokerProperties)) {
                closeObservers();
            }
            broker.beforeInvocation(brokerProperties);
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            if (broker.restoreAfterInvocation()) {
                verificationConnection = broker.createVerificationConnection();
                for (JmsFixtureRuntime runtime : runtimesByFixtureId.values()) {
                    runtime.openObservers(verificationConnection);
                }
                verificationConnection.start();
            }
            for (SnapshotFixtureBinding binding : bindings) {
                runtimesByFixtureId.get(binding.definition().getId()).verify(binding.interaction(invocation.getId()), invocation.getId());
            }
            contextRuntime.verifyInvocation(invocation);
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() throws Exception {
            SnapshotJmsInitialContextFactory.deactivate(SnapshotJmsInitialContextFactory.PROVIDER_URL, broker.connectionFactory());
            Exception cleanupFailure = null;
            try {
                closeObservers();
            } catch (Exception exception) {
                cleanupFailure = exception;
            }
            try {
                broker.close();
            } catch (Exception exception) {
                cleanupFailure = appendFailure(cleanupFailure, exception);
            } finally {
                contextRuntime.close();
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private void closeObservers() throws Exception {
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
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private void capturePreparedRequest(SnapshotFixtureBinding binding, Exchange exchange) {
            contextRuntime.senderEntered(binding, exchange);
            runtimesByFixtureId.get(binding.definition().getId()).preparedRequests
                    .add(new PreparedRequest(immutableMap(exchange.getProperties())));
        }
    }

    private static final class JmsFixtureRuntime implements AutoCloseable {
        private final SnapshotFixtureBinding binding;
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private final List<Session> sessions = new ArrayList<>();
        private final List<MessageConsumer> consumers = new ArrayList<>();
        private JmsEndpointUri endpoint;
        private String observedDestination;
        private int subscriberCount;

        private JmsFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void configure(JmsEndpointUri endpoint, Connection connection) throws Exception {
            this.endpoint = endpoint;
            List<SnapshotFixtureInteraction> interactions = List.copyOf(binding.interactionsByInvocationId().values());
            observedDestination = interactions.stream().map(SnapshotFixtureInteraction::getExpectedRequest)
                    .filter(request -> request.getCount() > 0).map(SnapshotFixtureRequestExpectation::getDestination)
                    .findFirst().orElse(endpoint.destinationName());
            subscriberCount = (Integer) JmsSnapshotRecords.responseProperties(interactions.getFirst().getResponse())
                    .getOrDefault("subscriberCount", 1);
            if (!"topic".equals(endpoint.destinationType()) && subscriberCount != 1) {
                throw new IllegalArgumentException("Multiple JMS subscribers require a topic endpoint.");
            }
            for (SnapshotFixtureInteraction interaction : interactions) {
                assertEquals(subscriberCount, JmsSnapshotRecords.responseProperties(interaction.getResponse())
                        .getOrDefault("subscriberCount", 1), "JMS subscriberCount must remain constant across invocations.");
            }
            openObservers(connection);
        }

        private void openObservers(Connection connection) throws Exception {
            for (int index = 0; index < subscriberCount; index++) {
                Session session = connection.createSession(Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                Destination destination = "topic".equals(endpoint.destinationType())
                        ? session.createTopic(observedDestination) : session.createQueue(observedDestination);
                consumers.add(session.createConsumer(destination));
            }
        }

        private void verify(SnapshotFixtureInteraction interaction, String invocationId) {
            String description = "Artemis JMS fixture '" + binding.definition().getId() + "' invocation '" + invocationId + "'";
            int expectedSends = JmsSnapshotRecords.expectedSendCount(interaction);
            assertEquals(expectedSends, preparedRequests.size(), description + " prepared an unexpected number of sends.");
            for (PreparedRequest request : preparedRequests) {
                SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getProperties(), request.properties(),
                        description + " prepared property");
            }
            for (int index = 0; index < consumers.size(); index++) {
                String subscriber = description + " subscriber " + (index + 1);
                JmsSnapshotRecords.assertRecords(interaction, JmsSnapshotRecords.read(consumers.get(index),
                        interaction.getExpectedRequest().getCount(), expectedSends > 0, subscriber), subscriber);
            }
            Map<String, Object> properties = JmsSnapshotRecords.responseProperties(interaction.getResponse());
            if (properties.containsKey("expectedJndiLookupCount")) {
                assertEquals(properties.get("expectedJndiLookupCount"), SnapshotJmsInitialContextFactory.lookupCount(
                        SnapshotJmsInitialContextFactory.PROVIDER_URL, endpoint.destinationName()), description + " JNDI lookup count");
            }
        }

        @Override
        public void close() throws Exception {
            Exception cleanupFailure = null;
            for (MessageConsumer consumer : consumers) {
                try {
                    consumer.close();
                } catch (Exception exception) {
                    cleanupFailure = appendFailure(cleanupFailure, exception);
                }
            }
            consumers.clear();
            for (Session session : sessions) {
                try {
                    session.close();
                } catch (Exception exception) {
                    cleanupFailure = appendFailure(cleanupFailure, exception);
                }
            }
            sessions.clear();
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
            SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
            String label = "Artemis JMS fixture '" + binding.definition().getId() + "'";
            if (expected == null || expected.getDestination() == null) {
                throw new IllegalArgumentException(label + " must define an expected request destination.");
            }
            if (expected.getMethod() != null || expected.getPath() != null || expected.getQuery() != null || expected.getKey() != null) {
                throw new IllegalArgumentException(label + " does not support HTTP method, path, query, or key expectations.");
            }
            JmsSnapshotRecords.responseProperties(interaction.getResponse());
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

    private record JmsEndpointUri(
            String componentName,
            String destinationType,
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
            if (!"queue".equals(destinationType) && !"topic".equals(destinationType)) {
                throw new IllegalArgumentException(
                        "Artemis JMS container fixture '" + fixtureDefinition.getId()
                                + "' supports queue or topic endpoints, but found destination type '"
                                + destinationType + "'."
                );
            }

            String destinationName = endpoint.substring(destinationTypeEnd + 1);
            requireStaticDestination(destinationName, fixtureDefinition.getId());
            return new JmsEndpointUri(componentName, destinationType, destinationName);
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
