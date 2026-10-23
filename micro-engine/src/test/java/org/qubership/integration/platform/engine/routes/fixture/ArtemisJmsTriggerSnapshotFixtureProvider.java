package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsConsumer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.JmsProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.jms.weblogic.JndiDestinationResolver;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.naming.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtemisJmsTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_URL = "snapshot:jms-trigger";

    @Override
    public String getId() {
        return "artemis-jms-trigger";
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("Artemis JMS trigger requires at least one binding.");
        }
        bindings.forEach(binding -> binding.interactionsByInvocationId().values()
                .forEach(ArtemisJmsTriggerSnapshotFixtureProvider::validateInteraction));
        return new ArtemisJmsTriggerSnapshotFixture(deploymentId, bindings);
    }

    private static void validateInteraction(SnapshotFixtureInteraction interaction) {
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        if (expected == null || expected.getCount() < 0) {
            throw new IllegalArgumentException("Artemis JMS trigger requires a nonnegative expectedRequest.count including all invocation repetitions.");
        }
        if (expected.getMethod() != null || expected.getPath() != null || expected.getQuery() != null || expected.getKey() != null) {
            throw new IllegalArgumentException("Artemis JMS trigger supports expectedRequest count, destination, body, headers, and properties.");
        }
        if (interaction.hasExpectedExchanges() && interaction.getExpectedExchanges().stream()
                .anyMatch(exchange -> exchange.getExpectedFailure() != null)) {
            throw new IllegalArgumentException("JMS trigger input expectations cannot define expectedFailure.");
        }
        SnapshotFixtureResponse response = interaction.getResponse();
        if (response == null) {
            return;
        }
        if (response.hasExplicitStatus() || response.getBody() != null || !response.getHeaders().isEmpty()
                || response.getDelayMillis() != null
                || !Set.of("expectedBodyType", "expectedBodyBytes", "expectedAbsentHeaders", "consumerAction",
                        "expectedCompletionBody", "expectedCompletionHeaders", "expectedCompletionProperties", "expectedCompletions",
                        "expectedAcknowledgmentMode", "expectedTransacted", "expectedRedeliveryCount",
                        "expectedListenerTransacted", "maxDeliveryAttempts", "expectedDeadLetterCount").containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException("Artemis JMS trigger response must use supported observation and consumer controls.");
        }
        Map<String, Object> controls = response.getProperties();
        if (controls.containsKey("consumerAction") && !Set.of("stop-until-published", "expire-before-start", "restart-broker")
                .contains(controls.get("consumerAction"))) {
            throw new IllegalArgumentException("JMS trigger consumerAction must be stop-until-published, expire-before-start, or restart-broker.");
        }
        if (controls.containsKey("expectedBodyType") && !(controls.get("expectedBodyType") instanceof String)) {
            throw new IllegalArgumentException("Artemis JMS trigger expectedBodyType must be a class name.");
        }
        if (controls.containsKey("expectedBodyBytes")) {
            JmsTriggerSnapshotRuntime.bytes(controls.get("expectedBodyBytes"));
        }
        for (String key : List.of("expectedCompletionHeaders", "expectedCompletionProperties")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof Map<?, ?> values)
                    || values.keySet().stream().anyMatch(name -> !(name instanceof String)))) {
                throw new IllegalArgumentException("JMS trigger " + key + " must be a map with string keys.");
            }
        }
        for (String key : List.of("expectedTransacted", "expectedListenerTransacted")) {
            if (controls.containsKey(key) && !(controls.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("JMS trigger " + key + " must be a boolean.");
            }
        }
        if (controls.containsKey("expectedAcknowledgmentMode")
                && (!(controls.get("expectedAcknowledgmentMode") instanceof Integer mode) || mode < 0 || mode > 3)) {
            throw new IllegalArgumentException("JMS trigger expectedAcknowledgmentMode must be an integer from 0 to 3.");
        }
        if (controls.containsKey("expectedCompletions")) {
            if (!(controls.get("expectedCompletions") instanceof List<?> values)) {
                throw new IllegalArgumentException("JMS trigger expectedCompletions must be a list of exchange expectations.");
            }
            JmsTriggerSnapshotRuntime.completionExpectations(values);
        }
        if (controls.containsKey("expectedRedeliveryCount")) {
            if (!(controls.get("expectedRedeliveryCount") instanceof Integer count) || count < 0) {
                throw new IllegalArgumentException("JMS trigger expectedRedeliveryCount must be a nonnegative integer.");
            }
            if (count > 0 && (!(controls.get("expectedCompletions") instanceof List<?> values) || values.isEmpty())) {
                throw new IllegalArgumentException("JMS trigger redelivery requires expectedCompletions for each delivery attempt.");
            }
        }
        if (controls.containsKey("maxDeliveryAttempts")
                && (!(controls.get("maxDeliveryAttempts") instanceof Integer attempts) || attempts < 1)) {
            throw new IllegalArgumentException("JMS trigger maxDeliveryAttempts must be a positive integer.");
        }
        if (controls.containsKey("expectedDeadLetterCount")
                && (!(controls.get("expectedDeadLetterCount") instanceof Integer count) || count < 0)) {
            throw new IllegalArgumentException("JMS trigger expectedDeadLetterCount must be a nonnegative integer.");
        }
        if (controls.containsKey("expectedAbsentHeaders") && (!(controls.get("expectedAbsentHeaders") instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String)))) {
            throw new IllegalArgumentException("Artemis JMS trigger expectedAbsentHeaders must be a list of header names.");
        }
    }

    private static final class ArtemisJmsTriggerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final SnapshotJmsBrokerControl broker = new SnapshotJmsBrokerControl();
        private final List<ConsumerRuntime> consumers = new ArrayList<>();
        private CamelContext context;

        private record ConsumerRuntime(SnapshotFixtureBinding binding, RouteDefinition route, JmsTriggerSnapshotRuntime runtime) {
        }

        private ArtemisJmsTriggerSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            broker.start();
        }

        @Override
        public void beforeRouteLoad(CamelContext context) {
            SnapshotJmsInitialContextFactory.activate(PROVIDER_URL, broker.connectionFactory());
        }

        @Override
        public void configure(CamelContext context) throws Exception {
            configure(context, ((ModelCamelContext) context).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext context, List<RouteDefinition> routes) throws Exception {
            this.context = context;
            for (SnapshotFixtureBinding binding : bindings) {
                configureConsumer(context, routes, binding);
            }
            for (ConsumerRuntime consumer : consumers) {
                JmsTriggerSnapshotRuntime runtime = consumer.runtime();
                runtime.recipients(consumers.stream().map(ConsumerRuntime::runtime)
                        .filter(other -> other == runtime || runtime.sharesTopic(other)).toList());
            }
        }

        private void configureConsumer(CamelContext context, List<RouteDefinition> routes, SnapshotFixtureBinding binding) throws Exception {
            String nodeId = binding.definition().getNodeId();
            RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, nodeId),
                    "Artemis JMS trigger expected one node '" + nodeId + "'").route();
            String endpoint = route.getInput().getUri();
            int componentEnd = endpoint.indexOf(':');
            String destinationPart = componentEnd < 0 ? "" : endpoint.substring(componentEnd + 1);
            boolean topic = destinationPart.startsWith("topic:");
            if (!endpoint.startsWith("jms-") || (!topic && !destinationPart.startsWith("queue:"))) {
                throw new IllegalArgumentException("Artemis JMS trigger requires a generated JMS queue or topic consumer, but found '" + endpoint + "'.");
            }
            String componentName = endpoint.substring(0, componentEnd);
            String destination = destinationPart.substring("queue:".length()).split("\\?", 2)[0];
            JmsComponent component = context.getRegistry().lookupByNameAndType(componentName, JmsComponent.class);
            assertNotNull(component, "Artemis JMS trigger requires generated component '" + componentName + "'.");
            assertSame(broker.connectionFactory(), component.getConfiguration().getConnectionFactory(),
                    "Generated JMS trigger component must use the fixture's JNDI connection factory.");
            JndiDestinationResolver resolver = assertInstanceOf(JndiDestinationResolver.class,
                    component.getConfiguration().getDestinationResolver());
            assertEquals(PROVIDER_URL, resolver.getJndiTemplate().getEnvironment().get(Context.PROVIDER_URL),
                    "Generated JMS trigger component must use the trigger fixture's JNDI provider.");
            JmsTriggerSnapshotRuntime runtime = new JmsTriggerSnapshotRuntime(broker.createVerificationConnection(), destination, topic);
            consumers.add(new ConsumerRuntime(binding, route, runtime));
            List<Integer> deliveryLimits = binding.interactionsByInvocationId().values().stream()
                    .map(SnapshotFixtureInteraction::getResponse).filter(java.util.Objects::nonNull)
                    .map(response -> response.getProperties().get("maxDeliveryAttempts"))
                    .filter(java.util.Objects::nonNull).map(Integer.class::cast).distinct().toList();
            if (!deliveryLimits.isEmpty()) {
                int attempts = SnapshotRouteNodes.requireSingle(deliveryLimits,
                        "JMS trigger fixture must use one delivery limit for destination '" + destination + "'");
                broker.setMaxDeliveryAttempts(destination, attempts);
                runtime.enableDeadLetterChecks();
            }
            context.getRegistry().bind(JmsTriggerSnapshotRuntime.registryName(nodeId), runtime);
            SnapshotFixtureRouteScope.bind(context, List.of(route), deploymentId + ':' + binding.definition().getId(),
                    Map.of("jmsProcessor", new JmsProcessor(), "chainStartProcessor", new ChainStartProcessor(),
                            "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                            "chainFinishProcessor", (Processor) runtime::completed));
            AdviceWith.adviceWith(context, route, false, advice -> advice.weaveById(nodeId).before().process(runtime::received));
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                Map<String, Object> controls = controls(consumer, invocation);
                consumer.runtime().beforeInvocation(controls);
                DefaultMessageListenerContainer listener = listener(consumer);
                if (controls.containsKey("expectedListenerTransacted")) {
                    assertEquals(controls.get("expectedListenerTransacted"), listener.isSessionTransacted(),
                            "Generated JMS trigger listener transaction mode");
                }
                awaitConsumerReady(listener);
            }
            for (ConsumerRuntime consumer : consumers) {
                configureAction(consumer, controls(consumer, invocation));
            }
        }

        private Map<String, Object> controls(ConsumerRuntime consumer, SnapshotScenarioInvocation invocation) {
            SnapshotFixtureResponse response = consumer.binding().interaction(invocation.getId()).getResponse();
            return response == null ? Map.of() : response.getProperties();
        }

        private void configureAction(ConsumerRuntime consumer, Map<String, Object> controls) throws Exception {
            Object action = controls.get("consumerAction");
            JmsTriggerSnapshotRuntime runtime = consumer.runtime();
            RouteDefinition route = consumer.route();
            if ("stop-until-published".equals(action) || "expire-before-start".equals(action)) {
                context.getRouteController().stopRoute(route.getId());
                assertTrue(context.getRouteController().getRouteStatus(route.getId()).isStopped(),
                        "JMS consumer route must stop before messages are published.");
                runtime.afterPublish(messages -> {
                    runtime.assertNothingDelivered();
                    if ("expire-before-start".equals(action)) {
                        long expiration = messages.stream().mapToLong(JmsTriggerSnapshotRuntime.PublishedMessage::expiration).max().orElse(0);
                        assertTrue(expiration > 0, "Expiration verification requires a message with a positive TTL.");
                        await(() -> System.currentTimeMillis() >= expiration, "published JMS message expiration");
                        runtime.markExpired(messages);
                    }
                    context.getRouteController().startRoute(route.getId());
                    awaitConsumerReady(listener(consumer));
                });
            } else if ("restart-broker".equals(action)) {
                restartBroker();
            }
        }

        private void restartBroker() throws Exception {
            List<JmsConsumer> originalConsumers = consumers.stream().map(this::consumer).toList();
            List<DefaultMessageListenerContainer> listeners = new ArrayList<>();
            for (ConsumerRuntime consumer : consumers) {
                listeners.add(listener(consumer));
                consumer.runtime().close();
            }
            broker.beforeInvocation(Map.of("brokerFault", "CONNECTION_REFUSED"));
            listeners.forEach(listener -> await(listener::isRecovering, "JMS consumer recovery after broker shutdown"));
            assertTrue(broker.restoreAfterInvocation(), "Artemis must restart after the injected shutdown.");
            for (int index = 0; index < consumers.size(); index++) {
                ConsumerRuntime consumer = consumers.get(index);
                DefaultMessageListenerContainer listener = listeners.get(index);
                awaitConsumerReady(listener);
                assertSame(originalConsumers.get(index), consumer(consumer), "Broker recovery must keep the existing JMS consumer.");
                assertSame(listener, listener(consumer), "Broker recovery must keep the existing JMS listener container.");
                consumer.runtime().connect(broker.createVerificationConnection());
            }
        }

        private JmsConsumer consumer(ConsumerRuntime consumer) {
            return assertInstanceOf(JmsConsumer.class, context.getRoute(consumer.route().getId()).getConsumer());
        }

        private DefaultMessageListenerContainer listener(ConsumerRuntime consumer) throws Exception {
            return assertInstanceOf(DefaultMessageListenerContainer.class, consumer(consumer).getListenerContainer());
        }

        private void awaitConsumerReady(DefaultMessageListenerContainer listener) {
            // Uncached queue consumers are created for each poll and have no fixed registration.
            await(() -> listener.isRunning() && !listener.isRecovering()
                    && (listener.isRegisteredWithDestination()
                    || (listener.getCacheLevel() < DefaultMessageListenerContainer.CACHE_CONSUMER
                    && listener.getActiveConsumerCount() > 0
                    && (!listener.isPubSubDomain() || topicConsumersReady(listener)))), "JMS consumer readiness");
        }

        private boolean topicConsumersReady(DefaultMessageListenerContainer listener) {
            String destination = listener.getDestinationName();
            long expected = consumers.stream().filter(consumer -> consumer.runtime().isTopic(destination)).count();
            return broker.topicConsumerCount(destination) == expected;
        }

        private static void await(BooleanSupplier condition, String description) {
            RateLimiter limiter = RateLimiterBuilder.newBuilder().withRate(10, TimeUnit.SECONDS).withConstantThroughput().build();
            try {
                Unreliables.retryUntilTrue(30, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition::getAsBoolean));
            } catch (RuntimeException exception) {
                throw new AssertionError("Timed out waiting for " + description + ".", exception);
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                consumer.runtime().verifyInvocation(consumer.binding().interaction(invocation.getId()), invocation.getId());
            }
        }

        @Override
        public void awaitCompletion() throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                context.getRouteController().stopRoute(consumer.route().getId());
            }
            for (ConsumerRuntime consumer : consumers) {
                consumer.runtime().verifyAfterConsumerStopped();
            }
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() throws Exception {
            SnapshotJmsInitialContextFactory.deactivate(PROVIDER_URL, broker.connectionFactory());
            Exception failure = null;
            try {
                for (ConsumerRuntime consumer : consumers) {
                    try {
                        consumer.runtime().close();
                    } catch (Exception exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            } finally {
                broker.close();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
