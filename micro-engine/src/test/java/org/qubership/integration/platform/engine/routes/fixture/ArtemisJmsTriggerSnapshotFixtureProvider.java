package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsConsumer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.jms.policy.JmsDefaultPrefetchPolicy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
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
                        "expectedListenerTransacted", "maxDeliveryAttempts", "expectedDeadLetterCount",
                        "expectedNamingFailure", "anonymousNaming", "sharedQueue", "holdConcurrentConsumers",
                        "expectedConsumerCount", "expectedDuplicateCompletions", "expectedFailureCompletions").containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException("Artemis JMS trigger response must use supported observation and consumer controls.");
        }
        Map<String, Object> controls = response.getProperties();
        if (controls.containsKey("consumerAction") && !Set.of("stop-until-published", "expire-before-start", "restart-broker",
                "restart-broker-with-backlog", "restart-broker-before-acknowledgment", "wrong-jndi-binding-until-published",
                "stop-peer", "start-peer", "stop-primary", "start-primary", "stop-group-until-published", "stop-during-processing")
                .contains(controls.get("consumerAction"))) {
            throw new IllegalArgumentException("JMS trigger consumerAction is not supported.");
        }
        if (controls.containsKey("expectedNamingFailure") && !Set.of("authentication", "missing-connection-factory")
                .contains(controls.get("expectedNamingFailure"))) {
            throw new IllegalArgumentException("JMS trigger expectedNamingFailure must be authentication or missing-connection-factory.");
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
        for (String key : List.of("expectedTransacted", "expectedListenerTransacted", "anonymousNaming", "sharedQueue",
                "holdConcurrentConsumers")) {
            if (controls.containsKey(key) && !(controls.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("JMS trigger " + key + " must be a boolean.");
            }
        }
        for (String key : List.of("expectedConsumerCount", "expectedDuplicateCompletions", "expectedFailureCompletions")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof Integer count) || count < 0)) {
                throw new IllegalArgumentException("JMS trigger " + key + " must be a nonnegative integer.");
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
        private SnapshotJmsBrokerControl broker;
        private SnapshotWorkerBrokerPool.Lease<SnapshotJmsBrokerControl> brokerLease;
        private final List<ConsumerRuntime> consumers = new ArrayList<>();
        private CamelContext context;

        private record ConsumerRuntime(SnapshotFixtureBinding binding, RouteDefinition route, JmsTriggerSnapshotRuntime runtime,
                                       List<RouteDefinition> routes) {
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
            SnapshotJmsBrokerControl.validateReuse(bindings);
            boolean persistDeliveryCount = bindings.stream()
                    .flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(SnapshotFixtureInteraction::getResponse)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(response -> "restart-broker-before-acknowledgment".equals(response.getProperties().get("consumerAction")));
            brokerLease = SnapshotWorkerBrokerPool.acquire(SnapshotJmsBrokerControl.POOL_KEY, SnapshotJmsBrokerControl::new);
            broker = brokerLease.resource();
            broker.start(persistDeliveryCount);
            if (persistDeliveryCount) {
                assertTrue(broker.persistsDeliveryCountBeforeDelivery(),
                        "Artemis must persist delivery counts before delivery in broker-restart redelivery scenarios.");
            }
        }

        @Override
        public void beforeRouteLoad(CamelContext context) {
            boolean sharedQueue = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(SnapshotFixtureInteraction::getResponse).filter(java.util.Objects::nonNull)
                    .anyMatch(response -> Boolean.TRUE.equals(response.getProperties().get("sharedQueue")));
            if (sharedQueue) {
                JmsConnectionFactory connectionFactory = assertInstanceOf(JmsConnectionFactory.class, broker.connectionFactory());
                JmsDefaultPrefetchPolicy prefetch = assertInstanceOf(JmsDefaultPrefetchPolicy.class,
                        connectionFactory.getPrefetchPolicy());
                // A held listener must leave the second publication available to its peer.
                prefetch.setQueuePrefetch(0);
            }
            SnapshotJmsInitialContextFactory.activate(PROVIDER_URL, broker.connectionFactory());
            boolean anonymous = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(SnapshotFixtureInteraction::getResponse).filter(java.util.Objects::nonNull)
                    .anyMatch(response -> Boolean.TRUE.equals(response.getProperties().get("anonymousNaming")));
            if (anonymous) {
                SnapshotJmsInitialContextFactory.requireAnonymous(PROVIDER_URL);
            } else {
                SnapshotJmsInitialContextFactory.requireCredentials(PROVIDER_URL, "snapshot", "snapshot");
            }
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
            String destinationPart = endpoint.substring(endpoint.indexOf(':') + 1);
            boolean topic = destinationPart.startsWith("topic:");
            if (!endpoint.startsWith("jms-") || (!topic && !destinationPart.startsWith("queue:"))) {
                throw new IllegalArgumentException("Artemis JMS trigger requires a generated JMS queue or topic consumer.");
            }
            String destination = destinationPart.substring("queue:".length()).split("\\?", 2)[0];
            boolean sharedQueue = binding.interactionsByInvocationId().values().stream()
                    .map(SnapshotFixtureInteraction::getResponse).filter(java.util.Objects::nonNull)
                    .anyMatch(response -> Boolean.TRUE.equals(response.getProperties().get("sharedQueue")));
            List<RouteDefinition> physicalRoutes = new ArrayList<>();
            physicalRoutes.add(route);
            if (sharedQueue) {
                assertTrue(!topic, "Competing consumers require a queue.");
                routes.stream().filter(other -> other != route && other.getInput().getUri().startsWith("jms-"))
                        .filter(other -> other.getInput().getUri().substring(other.getInput().getUri().indexOf(':') + 1)
                                .split("\\?", 2)[0].equals("queue:" + destination)).forEach(physicalRoutes::add);
                assertEquals(2, physicalRoutes.size(), "Competing queue fixture requires exactly two generated consumers.");
            }
            JmsTriggerSnapshotRuntime runtime = new JmsTriggerSnapshotRuntime(broker.createVerificationConnection(), destination, topic);
            if (sharedQueue) {
                runtime.sharedQueue();
            }
            consumers.add(new ConsumerRuntime(binding, route, runtime, List.copyOf(physicalRoutes)));
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
            for (RouteDefinition physical : physicalRoutes) {
                String uri = physical.getInput().getUri();
                String componentName = uri.substring(0, uri.indexOf(':'));
                String physicalNode = componentName.substring("jms-".length());
                JmsComponent component = context.getRegistry().lookupByNameAndType(componentName, JmsComponent.class);
                assertNotNull(component, "Artemis JMS trigger requires its generated component.");
                assertSame(broker.connectionFactory(), component.getConfiguration().getConnectionFactory(),
                        "Generated JMS trigger must retain its JNDI connection factory.");
                JndiDestinationResolver resolver = assertInstanceOf(JndiDestinationResolver.class,
                        component.getConfiguration().getDestinationResolver());
                assertEquals(PROVIDER_URL, resolver.getJndiTemplate().getEnvironment().get(Context.PROVIDER_URL),
                        "Generated JMS trigger must retain its JNDI provider.");
                context.getRegistry().bind(JmsTriggerSnapshotRuntime.registryName(physicalNode), runtime);
                SnapshotFixtureRouteScope.bind(context, List.of(physical), deploymentId + ':' + physicalNode,
                        Map.of("jmsProcessor", new JmsProcessor(), "chainStartProcessor", new ChainStartProcessor(),
                                "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                                "chainFinishProcessor", (Processor) runtime::completed));
                AdviceWith.adviceWith(context, physical, false, advice -> advice.weaveById(physicalNode).before()
                        .process(exchange -> runtime.received(exchange, physical.getId())));
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                Map<String, Object> controls = controls(consumer, invocation);
                consumer.runtime().beforeInvocation(controls);
                for (RouteDefinition physical : consumer.routes()) {
                    if (context.getRouteController().getRouteStatus(physical.getId()).isStopped()) {
                        continue;
                    }
                    DefaultMessageListenerContainer listener = listener(physical);
                    if (controls.containsKey("expectedListenerTransacted")) {
                        assertEquals(controls.get("expectedListenerTransacted"), listener.isSessionTransacted(),
                                "Generated JMS trigger listener transaction mode");
                    }
                    awaitConsumerReady(listener);
                }
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
            if ("stop-peer".equals(action) || "start-peer".equals(action)
                    || "stop-primary".equals(action) || "start-primary".equals(action)) {
                RouteDefinition selected = String.valueOf(action).endsWith("peer") ? consumer.routes().getLast() : route;
                if (String.valueOf(action).startsWith("stop")) {
                    context.getRouteController().stopRoute(selected.getId());
                    assertTrue(context.getRouteController().getRouteStatus(selected.getId()).isStopped(),
                            "The selected competing consumer must stop before publication.");
                } else {
                    context.getRouteController().startRoute(selected.getId());
                    awaitConsumerReady(listener(selected));
                }
            } else if ("stop-group-until-published".equals(action)) {
                for (RouteDefinition physical : consumer.routes()) {
                    context.getRouteController().stopRoute(physical.getId());
                }
                runtime.afterPublish(messages -> {
                    runtime.assertNothingDelivered();
                    context.getRouteController().startRoute(route.getId());
                    awaitConsumerReady(listener(route));
                });
            } else if ("stop-during-processing".equals(action)) {
                runtime.holdFirstDelivery();
                runtime.afterPublish(messages -> stopDuringProcessing(consumer));
            } else if ("stop-until-published".equals(action) || "expire-before-start".equals(action)) {
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
            } else if ("restart-broker-with-backlog".equals(action)) {
                context.getRouteController().stopRoute(route.getId());
                runtime.afterPublish(messages -> {
                    runtime.assertNothingDelivered();
                    restartBrokerWithPausedConsumer(consumer);
                });
            } else if ("restart-broker-before-acknowledgment".equals(action)) {
                runtime.holdFirstDelivery();
                runtime.afterPublish(messages -> restartBrokerDuringDelivery(consumer));
            } else if ("wrong-jndi-binding-until-published".equals(action)) {
                configureWrongBinding(consumer);
            }
            if (Boolean.TRUE.equals(controls.get("holdConcurrentConsumers"))) {
                runtime.holdConcurrentConsumers(consumer.routes().size());
                runtime.afterPublish(messages -> {
                    try {
                        runtime.awaitConcurrentConsumers();
                    } finally {
                        runtime.releaseConcurrentConsumers();
                    }
                });
            }
        }

        private void stopDuringProcessing(ConsumerRuntime consumer) throws Exception {
            consumer.runtime().awaitHeldDelivery();
            JmsConsumer original = consumer(consumer);
            try (var executor = Executors.newSingleThreadExecutor()) {
                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
                    try {
                        context.getRouteController().stopRoute(consumer.route().getId());
                    } catch (Exception exception) {
                        throw new IllegalStateException("Cannot stop the in-flight JMS route.", exception);
                    }
                }, executor);
                try {
                    await(original::isSuspendingOrSuspended, "JMS consumer suspension during graceful route shutdown");
                    assertEquals(1, context.getInflightRepository().size(consumer.route().getId()),
                            "The JMS delivery must remain in flight while shutdown waits.");
                    assertTrue(!stopped.isDone(), "JMS graceful shutdown must wait for the in-flight delivery.");
                } finally {
                    consumer.runtime().releaseHeldDelivery();
                }
                stopped.get(20, TimeUnit.SECONDS);
            }
            context.getRouteController().startRoute(consumer.route().getId());
            awaitConsumerReady(listener(consumer));
        }

        private JndiDestinationResolver resolver(ConsumerRuntime consumer) {
            String componentName = consumer.route().getInput().getUri().split(":", 2)[0];
            JmsComponent component = context.getRegistry().lookupByNameAndType(componentName, JmsComponent.class);
            return assertInstanceOf(JndiDestinationResolver.class, component.getConfiguration().getDestinationResolver());
        }

        private void configureWrongBinding(ConsumerRuntime consumer) throws Exception {
            JmsTriggerSnapshotRuntime runtime = consumer.runtime();
            String destination = listener(consumer).getDestinationName();
            boolean topic = listener(consumer).isPubSubDomain();
            JndiDestinationResolver resolver = resolver(consumer);
            context.getRouteController().stopRoute(consumer.route().getId());
            int previousLookups = SnapshotJmsInitialContextFactory.lookupCount(PROVIDER_URL, destination);
            SnapshotJmsInitialContextFactory.applyBinding(PROVIDER_URL, destination,
                    Map.of("jndiBinding", Map.of("type", topic ? "Queue" : "Topic", "destination", destination)));
            resolver.removeFromCache(destination);
            context.getRouteController().startRoute(consumer.route().getId());
            runtime.afterPublish(messages -> {
                await(() -> SnapshotJmsInitialContextFactory.lookupCount(PROVIDER_URL, destination) > previousLookups,
                        "the generated consumer's invalid JNDI destination lookup");
                await(listener(consumer)::isRecovering, "JMS listener recovery after a wrong JNDI destination type");
                runtime.assertNothingDelivered();
                SnapshotJmsInitialContextFactory.applyBinding(PROVIDER_URL, destination,
                        Map.of("jndiBinding", Map.of("type", topic ? "Topic" : "Queue", "destination", destination)));
                resolver.removeFromCache(destination);
                awaitConsumerReady(listener(consumer));
            });
        }

        private void restartBrokerWithPausedConsumer(ConsumerRuntime paused) throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                consumer.runtime().close();
            }
            broker.beforeInvocation(Map.of("brokerFault", "CONNECTION_REFUSED"));
            assertTrue(broker.restoreAfterInvocation(), "Artemis must restore its persistent backlog after shutdown.");
            for (ConsumerRuntime consumer : consumers) {
                consumer.runtime().connect(broker.createVerificationConnection());
            }
            context.getRouteController().startRoute(paused.route().getId());
            for (ConsumerRuntime consumer : consumers) {
                awaitConsumerReady(listener(consumer));
            }
        }

        private void restartBrokerDuringDelivery(ConsumerRuntime interrupted) throws Exception {
            interrupted.runtime().awaitHeldDelivery();
            List<JmsConsumer> originalConsumers = consumers.stream().map(this::consumer).toList();
            List<DefaultMessageListenerContainer> listeners = new ArrayList<>();
            for (ConsumerRuntime consumer : consumers) {
                listeners.add(listener(consumer));
                consumer.runtime().close();
            }
            try {
                broker.crash();
            } finally {
                interrupted.runtime().releaseHeldDelivery();
            }
            interrupted.runtime().awaitFirstCompletion();
            listeners.forEach(listener -> await(listener::isRecovering, "JMS recovery before acknowledging the interrupted message"));
            assertTrue(broker.restoreAfterInvocation(), "Artemis must restart with its unacknowledged message.");
            for (int index = 0; index < consumers.size(); index++) {
                ConsumerRuntime consumer = consumers.get(index);
                awaitConsumerReady(listeners.get(index));
                assertSame(originalConsumers.get(index), consumer(consumer), "In-flight recovery must keep the existing JMS consumer.");
                consumer.runtime().connect(broker.createVerificationConnection());
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
            return listener(consumer.route());
        }

        private DefaultMessageListenerContainer listener(RouteDefinition route) throws Exception {
            JmsConsumer consumer = assertInstanceOf(JmsConsumer.class, context.getRoute(route.getId()).getConsumer());
            return assertInstanceOf(DefaultMessageListenerContainer.class, consumer.getListenerContainer());
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
                if (Boolean.TRUE.equals(controls(consumer, invocation).get("anonymousNaming"))) {
                    assertTrue(SnapshotJmsInitialContextFactory.anonymousContexts(PROVIDER_URL) > 0,
                            "Generated JMS component must create an anonymous naming context without credentials.");
                    assertEquals(0, SnapshotJmsInitialContextFactory.authenticationFailures(PROVIDER_URL),
                            "Anonymous JMS naming must not attempt credentials.");
                }
            }
        }

        @Override
        public void verifyStartupFailure() throws Exception {
            assertTrue(consumers.isEmpty(), "A rejected JMS component must not create a consumer.");
            for (SnapshotFixtureBinding binding : bindings) {
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    Map<String, Object> controls = interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
                    if ("authentication".equals(controls.get("expectedNamingFailure"))) {
                        assertTrue(SnapshotJmsInitialContextFactory.authenticationFailures(PROVIDER_URL) > 0,
                                "Generated JMS credentials must reach the naming provider before startup fails.");
                    } else if ("missing-connection-factory".equals(controls.get("expectedNamingFailure"))) {
                        assertTrue(SnapshotJmsInitialContextFactory.lookupCount(PROVIDER_URL, "jms/missing/connectionfactory") > 0,
                                "Generated JMS component must attempt the configured connection-factory lookup.");
                    }
                }
            }
            assertEquals(0, broker.applicationMessageCount(), "A JMS startup failure must leave no application messages.");
        }

        @Override
        public void releasePendingWork() {
            consumers.forEach(consumer -> {
                consumer.runtime().releaseHeldDelivery();
                consumer.runtime().releaseConcurrentConsumers();
            });
        }

        @Override
        public void beforeContextStop() {
            releasePendingWork();
        }

        @Override
        public void awaitCompletion() throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                for (RouteDefinition physical : consumer.routes()) {
                    context.getRouteController().stopRoute(physical.getId());
                }
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
            if (broker != null) {
                SnapshotJmsInitialContextFactory.deactivate(PROVIDER_URL, broker.connectionFactory());
            }
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
                if (brokerLease != null) {
                    brokerLease.close();
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
