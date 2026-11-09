package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MetricsCollector;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.springrabbit.ListenerContainerFactory;
import org.apache.camel.component.springrabbit.SpringRabbitMQConsumer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.SpringRabbitMQCustomComponent;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.SpringRabbitMQCustomEndpoint;
import org.qubership.integration.platform.engine.camel.predeploy.AmqpTopologyCheck;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class RabbitMqTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    @Override
    public String getId() {
        return "rabbitmq-trigger";
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("RabbitMQ trigger fixture requires at least one binding.");
        }
        for (SnapshotFixtureBinding binding : bindings) {
            for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                if (interaction.getExpectedRequest() == null || interaction.getExpectedRequest().getCount() < 0) {
                    throw new IllegalArgumentException("RabbitMQ trigger fixture requires nonnegative expectedRequest.count.");
                }
            }
        }
        return new Fixture(deploymentId, bindings);
    }

    private static final class Fixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final List<Consumer> consumers = new ArrayList<>();
        private final Map<SpringRabbitMQCustomEndpoint, AbstractMessageListenerContainer> listeners = new IdentityHashMap<>();
        private final List<Throwable> nativeFailures = new CopyOnWriteArrayList<>();
        private final List<ListenerContainerConsumerFailedEvent> nativeEvents = new CopyOnWriteArrayList<>();
        private final Map<Classifier, Map<String, Object>> maasClassifiers = new LinkedHashMap<>();
        private CamelContext context;
        private RabbitMqTriggerSnapshotBroker broker;
        private SnapshotWorkerBrokerPool.Lease<RabbitMqTriggerSnapshotBroker> brokerLease;
        private RabbitMaaSClient maas;
        private boolean predeploymentAttempted;
        private SnapshotScenarioInvocation lastInvocation;

        private Fixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            boolean tls = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(Fixture::controls).anyMatch(properties -> Boolean.TRUE.equals(properties.get("tls")));
            boolean reuse = SnapshotWorkerBrokerPool.reuseRequested();
            if (reuse) {
                validateReuse();
            }
            brokerLease = SnapshotWorkerBrokerPool.acquire(tls ? "rabbitmq-trigger:tls" : "rabbitmq-trigger:plaintext", () -> {
                RabbitMqTriggerSnapshotBroker created = new RabbitMqTriggerSnapshotBroker(tls);
                try {
                    created.start();
                    if (reuse) {
                        created.enableReuse();
                    }
                    return created;
                } catch (Exception | Error failure) {
                    try {
                        created.close();
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            });
            broker = brokerLease.resource();
        }

        private void validateReuse() {
            Set<String> allowed = Set.of("expectedBusinessCount", "expectedFailureCompletions", "expectedRedeliveryCount",
                    "expectedBodyBase64", "expectedBodyLength", "expectedBodySha256", "topology", "deadLetterPolicy",
                    "consumerAction", "observeFanout", "bindings", "bindingArguments");
            for (SnapshotFixtureBinding binding : bindings) {
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    Map<String, Object> properties = controls(interaction);
                    if (!allowed.containsAll(properties.keySet())
                            || properties.containsKey("consumerAction") && !Set.of("stop-until-published", "restart-route",
                                    "observe-during-processing").contains(properties.get("consumerAction"))
                            || properties.containsKey("topology") && !Set.of("full", "queue-only").contains(properties.get("topology"))) {
                        throw new IllegalArgumentException("RabbitMQ broker reuse requires a plaintext functional scenario.");
                    }
                }
            }
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) throws Exception {
            for (SnapshotFixtureBinding binding : bindings) {
                Map<String, Object> properties = initialControls(binding);
                if (properties.containsKey("maasClassifier")) {
                    configureMaas(properties);
                }
            }
        }

        private void configureMaas(Map<String, Object> properties) throws Exception {
            if (maas == null) {
                maas = CDI.current().select(RabbitMaaSClient.class).get();
            }
            Classifier classifier = new Classifier(String.valueOf(properties.get("maasClassifier")),
                    Classifier.NAMESPACE, String.valueOf(properties.get("maasNamespace")));
            maasClassifiers.put(classifier, properties);
            if (Boolean.TRUE.equals(properties.get("maasMissingVhost"))) {
                when(maas.getVirtualHost(classifier)).thenReturn(null);
                return;
            }
            String vhost = "snapshot-trigger-maas";
            broker.ensureAccount("maas-trigger", "maas-password", vhost);
            VHost resolved = new VHost();
            resolved.setCnn(new URI("amqp", null, broker.consumerHost(), broker.consumerPort(), '/' + vhost, null, null).toString());
            resolved.setUsername("maas-trigger");
            resolved.setEncodedPassword("plain:maas-password");
            when(maas.getVirtualHost(classifier)).thenReturn(resolved);
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            context = camelContext;
            if (context.getComponent("rabbitmq-custom", false) == null) {
                context.addComponent("rabbitmq-custom", new SpringRabbitMQCustomComponent());
            }
            SpringRabbitMQCustomComponent component = context.getComponent("rabbitmq-custom", SpringRabbitMQCustomComponent.class);
            ListenerContainerFactory originalFactory = component.getListenerContainerFactory();
            component.setListenerContainerFactory(endpoint -> {
                AbstractMessageListenerContainer listener = originalFactory.createListenerContainer(endpoint);
                if (endpoint instanceof SpringRabbitMQCustomEndpoint custom
                        && consumers.stream().anyMatch(consumer -> consumer.endpoint() == custom)) {
                    listener.setApplicationEventPublisher(event -> {
                        if (event instanceof ListenerContainerConsumerFailedEvent failed) {
                            nativeEvents.add(failed);
                            if (failed.getThrowable() != null) {
                                nativeFailures.add(failed.getThrowable());
                            }
                        }
                    });
                    listeners.put(custom, listener);
                }
                return listener;
            });
            Map<String, RabbitMqTriggerSnapshotRuntime> shared = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, binding.definition().getNodeId()),
                        "RabbitMQ trigger requires one generated trigger step.").route();
                EndpointSettings settings = EndpointSettings.parse(route.getInput().getUri());
                Map<String, Object> properties = initialControls(binding);
                String uri = maas == null ? settings.withAddress(broker.consumerHost(), broker.consumerPort()) : route.getInput().getUri();
                route.getInput().setUri(uri);
                SpringRabbitMQCustomEndpoint endpoint = context.getEndpoint(uri, SpringRabbitMQCustomEndpoint.class);
                verifyGeneratedSettings(settings, endpoint, binding.definition().getNodeId());
                String username = settings.parameters().getOrDefault("username", "snapshot");
                String password = settings.parameters().getOrDefault("password", "snapshot");
                String vhost = settings.parameters().getOrDefault("vhost", "/");
                if (SnapshotWorkerBrokerPool.reuseRequested()
                        && (!RabbitMqTriggerSnapshotBroker.INSPECTOR.equals(username) || !"/".equals(vhost))) {
                    throw new IllegalArgumentException("RabbitMQ broker reuse requires the inspector account and default vhost.");
                }
                if (maas != null) {
                    assertEquals(broker.consumerHost() + ':' + broker.consumerPort(), settings.parameters().get("addresses"),
                            "MaaS must supply the real RabbitMQ broker address.");
                    assertEquals("maas-trigger", username, "MaaS must supply the RabbitMQ credentials.");
                    assertEquals("snapshot-trigger-maas", vhost, "MaaS must supply the RabbitMQ vhost.");
                }
                broker.ensureAccount(username, password, vhost);
                broker.provision(vhost, settings.exchange(), settings.exchangeType(), settings.queues(), settings.routingKey(),
                        settings.parameters(), properties);
                String sharedKey = Boolean.TRUE.equals(properties.get("sharedQueue")) ? vhost + ':' + settings.queues() : binding.definition().getId();
                RabbitMqTriggerSnapshotRuntime runtime = shared.get(sharedKey);
                if (runtime == null) {
                    runtime = new RabbitMqTriggerSnapshotRuntime(broker.publisherFactory(vhost), settings.exchange(),
                            settings.routingKey(), settings.queues());
                    runtime.context(context);
                    shared.put(sharedKey, runtime);
                }
                Consumer consumer = new Consumer(binding, route, endpoint, settings, vhost, username, password, runtime,
                        new NativeConnectionObserver());
                consumers.add(consumer);
                observeConnection(consumer);
                context.getRegistry().bind(RabbitMqTriggerSnapshotRuntime.registryName(binding.definition().getNodeId()), runtime);
                RabbitMqTriggerSnapshotRuntime selected = runtime;
                String node = binding.definition().getNodeId();
                SnapshotFixtureRouteScope.bind(context, List.of(route), deploymentId + ':' + node,
                        Map.of("chainStartProcessor", new ChainStartProcessor(), "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                                "chainFinishProcessor", (Processor) exchange -> selected.completed(exchange, node)));
                AdviceWith.adviceWith(context, route, false, advice -> {
                    advice.weaveById(node).before().process(exchange -> selected.received(exchange, node));
                    advice.weaveById(node).after().process(exchange -> selected.business(exchange, node));
                });
                predeploymentAttempted = true;
                new AmqpTopologyCheck(true).check(endpoint, true);
            }
        }

        private static void verifyGeneratedSettings(EndpointSettings settings, SpringRabbitMQCustomEndpoint endpoint, String node) {
            assertFalse(endpoint.isAutoDeclare(), "RabbitMQ trigger must retain autoDeclare=false.");
            assertEquals(1, endpoint.getMaximumRetryAttempts(), "RabbitMQ trigger must retain one listener attempt.");
            assertEquals(settings.parameters().getOrDefault("acknowledgeMode", "AUTO"), endpoint.getAcknowledgeMode().name(),
                    "RabbitMQ trigger must retain the exported acknowledgment mode.");
            assertNotNull(endpoint.getMetricsCollector(), "RabbitMQ trigger must retain the generated metrics collector for " + node);
            assertInstanceOf(MetricsCollector.class, endpoint.getMetricsCollector());
            assertInstanceOf(CachingConnectionFactory.class, endpoint.getConnectionFactory());
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            lastInvocation = invocation;
            nativeFailures.clear();
            nativeEvents.clear();
            Set<RabbitMqTriggerSnapshotRuntime> initialized = new LinkedHashSet<>();
            for (Consumer consumer : consumers) {
                consumer.connections().failures.clear();
                Map<String, Object> properties = controls(consumer.binding().interaction(invocation.getId()));
                if (initialized.add(consumer.runtime())) {
                    consumer.runtime().beforeInvocation(properties);
                }
            }
            for (Consumer consumer : consumers) {
                Map<String, Object> properties = controls(consumer.binding().interaction(invocation.getId()));
                awaitReady(consumer);
                if (properties.containsKey("expectedAcknowledgmentMode")) {
                    assertEquals(properties.get("expectedAcknowledgmentMode"), listeners.get(consumer.endpoint()).getAcknowledgeMode().name(),
                            "Generated RabbitMQ listener acknowledgment mode");
                }
                configureAction(consumer, properties);
            }
        }

        private void configureAction(Consumer consumer, Map<String, Object> properties) throws Exception {
            RabbitMqTriggerSnapshotRuntime runtime = consumer.runtime();
            String action = String.valueOf(properties.getOrDefault("consumerAction", ""));
            switch (action) {
                case "" -> { }
                case "stop-until-published" -> {
                    stop(consumer);
                    runtime.afterPublish(messages -> {
                        runtime.assertNothingDelivered();
                        assertEquals(messages.size(), broker.readyCount(consumer.vhost(), consumer.settings().queues()),
                                "Stopped RabbitMQ consumer must leave the published backlog ready.");
                        startRoute(consumer);
                    });
                }
                case "restart-route" -> {
                    stop(consumer);
                    startRoute(consumer);
                }
                case "stop-during-processing" -> {
                    runtime.holdFirstDelivery();
                    runtime.afterPublish(messages -> gracefulStop(consumer));
                }
                case "observe-during-processing" -> {
                    runtime.holdFirstDelivery();
                    runtime.afterPublish(messages -> {
                        runtime.awaitHeldDelivery();
                        try {
                            assertHeldAcknowledgment(consumer);
                        } finally {
                            runtime.releaseHeldDelivery();
                        }
                    });
                }
                case "close-during-processing" -> {
                    runtime.holdFirstDelivery();
                    runtime.afterPublish(messages -> closeWhileHeld(consumer, false));
                }
                case "close-after-business" -> {
                    runtime.holdCompletion();
                    runtime.afterPublish(messages -> closeWhileHeld(consumer, true));
                }
                case "restart-broker-with-backlog" -> {
                    stop(consumer);
                    runtime.afterPublish(messages -> {
                        runtime.assertNothingDelivered();
                        runtime.resetPublisher();
                        broker.restart();
                        startRoute(consumer);
                    });
                }
                case "delete-recreate-queue" -> {
                    broker.deleteQueue(consumer.vhost(), consumer.settings().queues().getFirst());
                    await(() -> !nativeEvents.isEmpty() || !nativeFailures.isEmpty(),
                            "RabbitMQ consumer cancellation after queue deletion");
                    broker.provision(consumer.vhost(), consumer.settings().exchange(), consumer.settings().exchangeType(),
                            consumer.settings().queues(), consumer.settings().routingKey(), consumer.settings().parameters(), initialControls(consumer.binding()));
                    awaitReady(consumer);
                }
                case "redeploy" -> reload(consumer);
                case "deny-read-until-published", "deny-credentials-until-published", "deny-vhost-until-published", "tls-until-published" ->
                        accessFault(consumer, action);
                default -> throw new IllegalArgumentException("Unsupported RabbitMQ trigger consumerAction '" + action + "'.");
            }
            if (Boolean.TRUE.equals(properties.get("holdConcurrentConsumers"))) {
                long count = consumers.stream().filter(other -> other.runtime() == runtime).count();
                runtime.holdConcurrentConsumers((int) count);
                runtime.afterPublish(messages -> {
                    try {
                        runtime.awaitConcurrentConsumers();
                    } finally {
                        runtime.releaseConcurrentConsumers();
                    }
                });
            }
        }

        private void accessFault(Consumer consumer, String action) throws Exception {
            assertTrue("tls-until-published".equals(action) || !"snapshot".equals(consumer.username()),
                    "Access fault cases require a separate RabbitMQ application account.");
            switch (action) {
                case "deny-read-until-published" -> broker.permissions(consumer.username(), consumer.vhost(), ".*", ".*", "^$");
                case "deny-credentials-until-published" -> broker.password(consumer.username(), "invalid-snapshot-password");
                case "deny-vhost-until-published" -> broker.deleteVhost(consumer.vhost());
                case "tls-until-published" -> broker.tlsUnavailable(true);
                default -> throw new IllegalArgumentException("Unsupported RabbitMQ access fault '" + action + "'.");
            }
            factory(consumer).resetConnection();
            if ("tls-until-published".equals(action)) {
                await(() -> consumer.connections().failures.stream().anyMatch(Fixture::hasSslCause),
                        "native RabbitMQ connection creation to fail its TLS handshake before publication");
                awaitChecked(() -> broker.consumerCount(consumer.vhost(), consumer.settings().queues()) == 0,
                        "RabbitMQ consumer removal before publication during TLS failure");
            }
            if ("deny-vhost-until-published".equals(action)) {
                await(() -> !nativeFailures.isEmpty(), "native RabbitMQ listener failure after vhost removal");
                consumer.runtime().assertNothingDelivered();
                broker.ensureAccount(consumer.username(), consumer.password(), consumer.vhost());
                broker.provision(consumer.vhost(), consumer.settings().exchange(), consumer.settings().exchangeType(),
                        consumer.settings().queues(), consumer.settings().routingKey(), consumer.settings().parameters(), initialControls(consumer.binding()));
                awaitReady(consumer);
                return;
            }
            consumer.runtime().afterPublish(messages -> {
                if ("tls-until-published".equals(action)) {
                    awaitChecked(() -> broker.readyCount(consumer.vhost(), consumer.settings().queues()) == messages.size(),
                            "RabbitMQ published backlog to remain queued during TLS failure");
                } else {
                    await(() -> !nativeFailures.isEmpty(), "native RabbitMQ listener failure during " + action);
                }
                consumer.runtime().assertNothingDelivered();
                switch (action) {
                    case "deny-read-until-published" -> broker.permissions(consumer.username(), consumer.vhost(), ".*", ".*", ".*");
                    case "deny-credentials-until-published" -> broker.password(consumer.username(), consumer.password());
                    case "deny-vhost-until-published" -> {
                        broker.ensureAccount(consumer.username(), consumer.password(), consumer.vhost());
                        broker.provision(consumer.vhost(), consumer.settings().exchange(), consumer.settings().exchangeType(),
                                consumer.settings().queues(), consumer.settings().routingKey(), consumer.settings().parameters(), initialControls(consumer.binding()));
                    }
                    case "tls-until-published" -> broker.tlsUnavailable(false);
                    default -> throw new IllegalArgumentException("Unsupported RabbitMQ access fault '" + action + "'.");
                }
                awaitReady(consumer);
            });
        }

        private void gracefulStop(Consumer consumer) throws Exception {
            consumer.runtime().awaitHeldDelivery();
            assertHeldAcknowledgment(consumer);
            SpringRabbitMQConsumer original = assertInstanceOf(SpringRabbitMQConsumer.class,
                    context.getRoute(consumer.route().getId()).getConsumer());
            try (var executor = Executors.newSingleThreadExecutor()) {
                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
                    try {
                        stop(consumer);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Cannot stop the in-flight RabbitMQ route.", exception);
                    }
                }, executor);
                try {
                    await(original::isSuspendingOrSuspended, "RabbitMQ consumer suspension during graceful shutdown");
                    await(() -> context.getInflightRepository().size(consumer.route().getId()) > 0,
                            "in-flight RabbitMQ delivery during graceful shutdown");
                    assertFalse(stopped.isDone(), "RabbitMQ graceful shutdown must wait for its held delivery.");
                } finally {
                    consumer.runtime().releaseHeldDelivery();
                }
                stopped.get(20, TimeUnit.SECONDS);
            }
            startRoute(consumer);
        }

        private void closeWhileHeld(Consumer consumer, boolean completion) throws Exception {
            if (completion) {
                consumer.runtime().awaitHeldCompletion();
            } else {
                consumer.runtime().awaitHeldDelivery();
            }
            assertHeldAcknowledgment(consumer);
            try {
                broker.closeConnections(consumer.username());
            } finally {
                if (completion) {
                    consumer.runtime().releaseCompletion();
                } else {
                    consumer.runtime().releaseHeldDelivery();
                }
            }
            awaitReady(consumer);
        }

        private void assertHeldAcknowledgment(Consumer consumer) throws Exception {
            int expected = "AUTO".equals(consumer.settings().parameters().getOrDefault("acknowledgeMode", "AUTO")) ? 1 : 0;
            awaitChecked(() -> broker.unacknowledged(consumer.vhost(), consumer.settings().queues()) == expected,
                    "RabbitMQ acknowledgment state while downstream processing is held");
        }

        private void reload(Consumer consumer) throws Exception {
            Object original = context.getRoute(consumer.route().getId()).getConsumer();
            com.rabbitmq.client.Connection originalConnection = consumer.connections().current;
            assertNotNull(originalConnection, "Reloading the generated route requires its native RabbitMQ connection.");
            assertTrue(originalConnection.isOpen(), "The RabbitMQ connection must be open before route removal.");
            stop(consumer);
            ModelCamelContext model = (ModelCamelContext) context;
            model.removeRouteDefinition(consumer.route());
            context.removeEndpoint(consumer.endpoint());
            await(() -> !originalConnection.isOpen(), "old native RabbitMQ connection closure after endpoint removal");
            SpringRabbitMQCustomEndpoint endpoint = context.getEndpoint(consumer.route().getInput().getUri(), SpringRabbitMQCustomEndpoint.class);
            assertNotSame(consumer.endpoint(), endpoint, "Reloading the generated route must recreate its native RabbitMQ endpoint.");
            verifyGeneratedSettings(consumer.settings(), endpoint, consumer.binding().definition().getNodeId());
            Consumer replacement = new Consumer(consumer.binding(), consumer.route(), endpoint, consumer.settings(), consumer.vhost(),
                    consumer.username(), consumer.password(), consumer.runtime(), new NativeConnectionObserver());
            consumers.set(consumers.indexOf(consumer), replacement);
            observeConnection(replacement);
            model.addRouteDefinition(consumer.route());
            awaitReady(replacement);
            assertNotSame(original, context.getRoute(consumer.route().getId()).getConsumer(),
                    "Reloading the generated route must recreate its native RabbitMQ consumer.");
            assertNotNull(replacement.connections().current, "The reloaded RabbitMQ consumer must establish a native connection.");
            assertNotSame(originalConnection, replacement.connections().current,
                    "The reloaded RabbitMQ consumer must use a fresh native connection.");
        }

        private void stop(Consumer consumer) throws Exception {
            context.getRouteController().stopRoute(consumer.route().getId());
            awaitChecked(() -> broker.consumerCount(consumer.vhost(), consumer.settings().queues()) == 0,
                    "RabbitMQ consumer removal after route stop");
        }

        private void startRoute(Consumer consumer) throws Exception {
            context.getRouteController().startRoute(consumer.route().getId());
            awaitReady(consumer);
        }

        private void awaitReady(Consumer consumer) {
            int expected = (int) consumers.stream().filter(other -> other.runtime() == consumer.runtime()).count()
                    * consumer.settings().queues().size();
            awaitChecked(() -> broker.consumerCount(consumer.vhost(), consumer.settings().queues()) == expected,
                    "RabbitMQ consumer registration for " + consumer.settings().queues());
        }

        private static CachingConnectionFactory factory(Consumer consumer) {
            return (CachingConnectionFactory) consumer.endpoint().getConnectionFactory();
        }

        private void observeConnection(Consumer consumer) {
            factory(consumer).addConnectionListener(consumer.connections());
            factory(consumer).addChannelListener((channel, transactional) -> channel.addShutdownListener(signal -> {
                if (!signal.isInitiatedByApplication()) {
                    nativeFailures.add(signal);
                }
            }));
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            Set<RabbitMqTriggerSnapshotRuntime> checked = new LinkedHashSet<>();
            List<Consumer> checkedConsumers = new ArrayList<>();
            Map<String, List<QueueExpectation>> expectationsByVhost = new LinkedHashMap<>();
            for (Consumer consumer : consumers) {
                if (!checked.add(consumer.runtime())) {
                    continue;
                }
                checkedConsumers.add(consumer);
                Map<String, Object> properties = controls(consumer.binding().interaction(invocation.getId()));
                expectationsByVhost.computeIfAbsent(consumer.vhost(), ignored -> new ArrayList<>()).add(
                        new QueueExpectation(consumer.settings().queues(), number(properties, "expectedReadyCount", 0),
                                number(properties, "expectedUnacknowledgedCount", 0), properties.containsKey("expectedConsumerCount")
                                ? number(properties, "expectedConsumerCount", 1) : null));
            }
            for (Map.Entry<String, List<QueueExpectation>> entry : expectationsByVhost.entrySet()) {
                awaitChecked(() -> {
                    Map<String, Map<String, Object>> states = broker.queueStates(entry.getKey()).stream()
                            .collect(Collectors.toMap(state -> (String) state.get("name"), state -> state));
                    return entry.getValue().stream().allMatch(expectation -> expectation.matches(states));
                }, "RabbitMQ queue state for vhost '" + entry.getKey() + "' after invocation " + invocation.getId() + ": " + entry.getValue());
            }
            for (Consumer consumer : checkedConsumers) {
                Map<String, Object> properties = controls(consumer.binding().interaction(invocation.getId()));
                if (properties.containsKey("expectedChannelCloseCode")) {
                    int expectedCode = number(properties, "expectedChannelCloseCode", 0);
                    assertTrue(nativeFailures.stream().anyMatch(failure -> hasCloseCode(failure, expectedCode)),
                            () -> "RabbitMQ listener did not report AMQP close code " + expectedCode + ": " + nativeFailures);
                }
                verifyDeadLetters(consumer, properties);
                if (Boolean.TRUE.equals(initialControls(consumer.binding()).get("observeFanout"))) {
                    List<GetResponse> records = broker.drain(consumer.vhost(),
                            RabbitMqTriggerSnapshotBroker.observerQueue(consumer.settings().queues().getFirst()), consumer.runtime().published().size());
                    assertEquals(consumer.runtime().published().size(), records.size(), "Fanout observer must receive an independent copy.");
                    for (int index = 0; index < records.size(); index++) {
                        assertArrayEquals(consumer.runtime().published().get(index).body(), records.get(index).getBody(), "Fanout observer body");
                    }
                }
                if ("queue-only".equals(initialControls(consumer.binding()).get("topology"))) {
                    assertFalse(broker.exchangeExists(consumer.vhost(), consumer.settings().exchange()), "Trigger must not declare the exchange.");
                    String dlx = consumer.settings().parameters().get("deadLetterExchange");
                    if (dlx != null) {
                        assertFalse(broker.exchangeExists(consumer.vhost(), dlx), "Trigger must not declare the DLX.");
                    }
                    String dlq = consumer.settings().parameters().get("deadLetterQueue");
                    if (dlq != null) {
                        assertFalse(broker.queueExists(consumer.vhost(), dlq), "Trigger must not declare the DLQ.");
                    }
                }
            }
            verifyRuntime(invocation);
        }

        private void verifyRuntime(SnapshotScenarioInvocation invocation) throws Exception {
            for (Consumer consumer : consumers) {
                consumer.runtime().verify(consumer.binding().interaction(invocation.getId()), invocation.getId(),
                        consumer.binding().definition().getNodeId());
            }
        }

        private void verifyDeadLetters(Consumer consumer, Map<String, Object> properties) throws Exception {
            int expected = number(properties, "expectedDeadLetterCount", 0);
            String queue = consumer.settings().parameters().get("deadLetterQueue");
            if (expected > 0) {
                awaitChecked(() -> broker.readyCount(consumer.vhost(), List.of(queue)) == expected,
                        "RabbitMQ dead-letter delivery");
            }
            List<GetResponse> records = broker.drain(consumer.vhost(), queue, expected);
            assertEquals(expected, records.size(), "RabbitMQ dead-letter record count");
            Set<String> published = consumer.runtime().published().stream().map(RabbitMqTriggerSnapshotRuntime.PublishedMessage::id)
                    .collect(Collectors.toSet());
            for (GetResponse record : records) {
                Map<String, Object> headers = record.getProps().getHeaders();
                assertNotNull(headers, "A dead-letter record must retain the publication headers.");
                assertTrue(published.contains(String.valueOf(headers.get("snapshotPublicationId"))), "Dead-letter publication ID");
                assertTrue(headers.containsKey("x-death"), "A rejected message must carry broker death metadata.");
                assertTrue(headers.get("x-death") instanceof List<?> deaths && deaths.stream().anyMatch(death ->
                        death instanceof Map<?, ?> entry && "rejected".equals(String.valueOf(entry.get("reason")))),
                        "The dead-letter reason must identify broker rejection.");
                RabbitMqTriggerSnapshotRuntime.PublishedMessage original = consumer.runtime().published().stream()
                        .filter(message -> message.id().equals(String.valueOf(headers.get("snapshotPublicationId")))).findFirst().orElseThrow();
                assertArrayEquals(original.body(), record.getBody(), "A rejected message must retain the original payload.");
            }
        }

        @Override
        public void verifyStartupFailure() throws Exception {
            for (SnapshotFixtureBinding binding : bindings) {
                Map<String, Object> properties = initialControls(binding);
                if ("missing-queue".equals(properties.get("topology"))) {
                    assertTrue(predeploymentAttempted, "Missing queue must reach the production passive topology check.");
                    for (Consumer consumer : consumers) {
                        for (String queue : consumer.settings().queues()) {
                            assertFalse(broker.queueExists(consumer.vhost(), queue), "Passive topology checks must not create missing queues.");
                        }
                    }
                }
            }
            verifyMaas();
        }

        @Override
        public String timeoutDiagnostics() {
            return "RabbitMQ native listener failures: " + nativeFailures + "; events: " + nativeEvents
                    + "; connection failures: " + consumers.stream().flatMap(consumer -> consumer.connections().failures.stream()).toList();
        }

        @Override
        public void releasePendingWork() {
            consumers.forEach(consumer -> {
                consumer.runtime().releaseHeldDelivery();
                consumer.runtime().releaseConcurrentConsumers();
                consumer.runtime().releaseCompletion();
            });
        }

        @Override
        public void beforeContextStop() {
            releasePendingWork();
        }

        @Override
        public void awaitCompletion() throws Exception {
            for (Consumer consumer : consumers) {
                if (context.getRoute(consumer.route().getId()) != null) {
                    context.getRouteController().stopRoute(consumer.route().getId());
                }
            }
            Set<RabbitMqTriggerSnapshotRuntime> checked = new LinkedHashSet<>();
            for (Consumer consumer : consumers) {
                if (checked.add(consumer.runtime())) {
                    awaitChecked(() -> broker.consumerCount(consumer.vhost(), consumer.settings().queues()) == 0,
                            "all native RabbitMQ consumers to stop before final verification");
                }
            }
        }

        @Override
        public void verify() {
            if (lastInvocation != null) {
                assertDoesNotThrow(() -> verifyRuntime(lastInvocation), "RabbitMQ final invocation after native consumer shutdown");
            }
            verifyMaas();
        }

        private void verifyMaas() {
            if (maas != null) {
                maasClassifiers.keySet().forEach(classifier -> org.mockito.Mockito.verify(maas, atLeastOnce()).getVirtualHost(classifier));
            }
        }

        @Override
        public void close() throws Exception {
            releasePendingWork();
            Exception failure = null;
            Set<RabbitMqTriggerSnapshotRuntime> closed = new LinkedHashSet<>();
            for (Consumer consumer : consumers) {
                if (closed.add(consumer.runtime())) {
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
            }
            try {
                if (brokerLease != null) {
                    brokerLease.close();
                }
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static Map<String, Object> initialControls(SnapshotFixtureBinding binding) {
            return controls(binding.interactionsByInvocationId().values().iterator().next());
        }

        private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
            SnapshotFixtureResponse response = interaction.getResponse();
            return response == null ? Map.of() : response.getProperties();
        }

        private static int number(Map<String, Object> properties, String key, int fallback) {
            return properties.get(key) instanceof Number value ? value.intValue() : fallback;
        }

        private static boolean hasCloseCode(Throwable failure, int code) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof com.rabbitmq.client.ShutdownSignalException shutdown) {
                    if (shutdown.getReason() instanceof com.rabbitmq.client.AMQP.Channel.Close close && close.getReplyCode() == code) {
                        return true;
                    }
                    if (shutdown.getReason() instanceof com.rabbitmq.client.AMQP.Connection.Close close && close.getReplyCode() == code) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean hasSslCause(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof SSLException) {
                    return true;
                }
            }
            return false;
        }

        private static void await(BooleanSupplier condition, String description) {
            RateLimiter limiter = RateLimiterBuilder.newBuilder().withRate(5, TimeUnit.SECONDS).withConstantThroughput().build();
            try {
                Unreliables.retryUntilTrue(20, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition::getAsBoolean));
            } catch (RuntimeException exception) {
                throw new AssertionError("Timed out waiting for " + description + ".", exception);
            }
        }

        private static void awaitChecked(CheckedBooleanSupplier condition, String description) {
            await(() -> {
                try {
                    return condition.getAsBoolean();
                } catch (Exception exception) {
                    throw new IllegalStateException("RabbitMQ observation failed while waiting for " + description + ".", exception);
                }
            }, description);
        }
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static final class NativeConnectionObserver implements ConnectionListener {
        private final List<Exception> failures = new CopyOnWriteArrayList<>();
        private volatile com.rabbitmq.client.Connection current;

        @Override
        public void onCreate(Connection connection) {
            current = connection.getDelegate();
        }

        @Override
        public void onFailed(Exception exception) {
            failures.add(exception);
        }
    }

    private record Consumer(SnapshotFixtureBinding binding, RouteDefinition route, SpringRabbitMQCustomEndpoint endpoint,
                            EndpointSettings settings, String vhost, String username, String password, RabbitMqTriggerSnapshotRuntime runtime,
                            NativeConnectionObserver connections) {
    }

    private record QueueExpectation(List<String> queues, int ready, int unacknowledged, Integer consumers) {
        boolean matches(Map<String, Map<String, Object>> states) {
            if (!states.keySet().containsAll(queues)) {
                return false;
            }
            int actualReady = 0;
            int actualUnacknowledged = 0;
            int actualConsumers = 0;
            for (String queue : queues) {
                Map<String, Object> state = states.get(queue);
                actualReady += ((Number) state.get("messages_ready")).intValue();
                actualUnacknowledged += ((Number) state.get("messages_unacknowledged")).intValue();
                actualConsumers += ((Number) state.get("consumers")).intValue();
            }
            return actualReady == ready && actualUnacknowledged == unacknowledged
                    && (consumers == null || actualConsumers == consumers);
        }
    }

    private record EndpointSettings(String exchange, String exchangeType, String routingKey, List<String> queues, Map<String, String> parameters) {
        static EndpointSettings parse(String uri) {
            int query = uri.indexOf('?');
            if (!uri.startsWith("rabbitmq-custom:") || query < 0) {
                throw new IllegalArgumentException("RabbitMQ trigger fixture requires a generated rabbitmq-custom consumer URI.");
            }
            Map<String, String> parameters = SnapshotEndpointParameters.parse(uri.substring(query + 1),
                    key -> new IllegalArgumentException("Duplicate RabbitMQ trigger endpoint parameter '" + key + "'."));
            String queues = parameters.get("queues");
            if (queues == null || queues.isBlank()) {
                throw new IllegalArgumentException("RabbitMQ trigger fixture requires configured input queues.");
            }
            return new EndpointSettings(uri.substring("rabbitmq-custom:".length(), query), parameters.getOrDefault("exchangeType", "direct"),
                    parameters.getOrDefault("routingKey", ""), List.copyOf(Arrays.asList(queues.split(","))), parameters);
        }

        String withAddress(String host, int port) {
            Map<String, String> values = new LinkedHashMap<>(parameters);
            values.put("addresses", host + ':' + port);
            return "rabbitmq-custom:" + exchange + '?' + values.entrySet().stream()
                    .map(entry -> entry.getKey() + '=' + entry.getValue()).collect(Collectors.joining("&"));
        }
    }
}
