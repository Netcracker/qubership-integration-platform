package org.qubership.integration.platform.engine.routes.fixture;

import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubComponent;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubConsumer;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.PubSubProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final Logger LOG = LoggerFactory.getLogger(PubSubTriggerSnapshotFixtureProvider.class);
    private static final Set<String> CONTROLS = Set.of("consumerAction", "startupUnavailable", "missingSubscription",
            "expectedAcknowledgments", "expectedNegativeAcknowledgments", "expectedAbsentHeaders", "expectedCompletions",
            "resourceProbe", "subscriptionAction", "maxDeliveriesPerMessage");

    @Override
    public String getId() {
        return "pubsub-trigger";
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.size() != 1) {
            throw new IllegalArgumentException("Pub/Sub trigger fixture requires one consumer binding.");
        }
        bindings.getFirst().interactionsByInvocationId().values().forEach(PubSubTriggerSnapshotFixtureProvider::validate);
        return new Fixture(deploymentId, bindings.getFirst());
    }

    private static void validate(SnapshotFixtureInteraction interaction) {
        if (interaction.getExpectedRequest() == null || interaction.getExpectedRequest().getCount() < 0) {
            throw new IllegalArgumentException("Pub/Sub trigger requires a nonnegative expectedRequest.count.");
        }
        if (interaction.getExpectedRequest().getMethod() != null || interaction.getExpectedRequest().getPath() != null
                || interaction.getExpectedRequest().getQuery() != null || interaction.getExpectedRequest().getKey() != null
                || interaction.getExpectedRequest().hasBody()) {
            throw new IllegalArgumentException("Pub/Sub trigger input is checked against publication; use count, destination, headers, and properties.");
        }
        Map<String, Object> controls = controls(interaction);
        if (interaction.getResponse() != null && (interaction.getResponse().hasExplicitStatus()
                || interaction.getResponse().getBody() != null || !interaction.getResponse().getHeaders().isEmpty()
                || interaction.getResponse().getDelayMillis() != null || !CONTROLS.containsAll(controls.keySet()))) {
            throw new IllegalArgumentException("Pub/Sub trigger response supports only consumer and observation controls.");
        }
        if (controls.containsKey("consumerAction") && !Set.of("stop-until-published", "disconnect-until-published",
                "restart", "probe-unavailable", "probe-idle", "stop-while-unavailable").contains(controls.get("consumerAction"))) {
            throw new IllegalArgumentException("Unsupported Pub/Sub consumerAction: " + controls.get("consumerAction"));
        }
        if (controls.containsKey("subscriptionAction") && !Set.of("create", "recreate-after-failures")
                .contains(controls.get("subscriptionAction"))) {
            throw new IllegalArgumentException("Unsupported Pub/Sub subscriptionAction: " + controls.get("subscriptionAction"));
        }
        for (String key : List.of("startupUnavailable", "missingSubscription")) {
            if (controls.containsKey(key) && !(controls.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Pub/Sub " + key + " must be a boolean.");
            }
        }
        if (controls.containsKey("maxDeliveriesPerMessage")
                && (!(controls.get("maxDeliveriesPerMessage") instanceof Number value)
                || value.intValue() < 1 || value.doubleValue() != value.intValue())) {
            throw new IllegalArgumentException("Pub/Sub maxDeliveriesPerMessage must be a positive integer.");
        }
    }

    private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
        return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
    }

    private static final class Fixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final PubSubTriggerSnapshotBroker broker = new PubSubTriggerSnapshotBroker();
        private final PubSubSubscriberRetentionProbe retention = new PubSubSubscriberRetentionProbe();
        private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
        private CamelContext context;
        private RouteDefinition route;
        private PubSubTriggerSnapshotRuntime runtime;
        private Consumer consumer;
        private PubSubTriggerSnapshotBroker.Statistics invocationStart;
        private boolean stopped;
        private final Map<String, java.util.logging.Level> previousLogLevels = new LinkedHashMap<>();

        private Fixture(String deploymentId, SnapshotFixtureBinding binding) {
            this.deploymentId = deploymentId;
            this.binding = binding;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) {
            boolean quietResourceProbe = binding.interactionsByInvocationId().values().stream()
                    .anyMatch(interaction -> controls(interaction).containsKey("resourceProbe"));
            if (quietResourceProbe) {
                for (String name : List.of(CustomGooglePubSubConsumer.class.getName(),
                        "com.google.cloud.pubsub.v1.StreamingSubscriberConnection", "io.grpc.internal.ManagedChannelOrphanWrapper")) {
                    var logger = org.jboss.logmanager.Logger.getLogger(name);
                    previousLogLevels.put(name, logger.getLevel());
                    logger.setLevel(java.util.logging.Level.OFF);
                }
            }
            InstrumentedComponent component = new InstrumentedComponent(retention);
            component.setAuthenticate(false);
            camelContext.addComponent("google-pubsub", component);
            camelContext.getRegistry().bind("snapshotPubSubAttempts", attempts);
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            context = camelContext;
            String nodeId = binding.definition().getNodeId();
            route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, nodeId),
                    "Pub/Sub trigger requires its generated consumer route").route();
            String uri = route.getInput().getUri();
            assertTrue(uri.startsWith("google-pubsub:"), "Pub/Sub fixture requires the generated google-pubsub URI.");
            String[] destination = uri.substring("google-pubsub:".length(), uri.indexOf('?')).split(":");
            Map<String, String> parameters = SnapshotEndpointParameters.parse(uri.substring(uri.indexOf('?') + 1),
                    key -> new IllegalArgumentException("Pub/Sub endpoint repeats parameter " + key));
            assertEquals(2, destination.length, "Pub/Sub destination must contain project and subscription.");
            assertTrue(parameters.get("serviceAccountKey").startsWith("base64:"), "Generated service account must remain configured.");
            assertEquals("1", parameters.get("maxMessagesPerPoll"), "Generated poll setting must remain configured.");
            assertTrue(Set.of("AUTO", "NONE").contains(parameters.get("ackMode")), "Generated ack mode must remain configured.");
            boolean missing = binding.interactionsByInvocationId().values().stream()
                    .anyMatch(interaction -> Boolean.TRUE.equals(controls(interaction).get("missingSubscription")));
            boolean unavailable = binding.interactionsByInvocationId().values().stream()
                    .anyMatch(interaction -> Boolean.TRUE.equals(controls(interaction).get("startupUnavailable")));
            broker.start(destination[0], "snapshot-pubsub-trigger-topic", destination[1], missing, unavailable);
            InstrumentedComponent component = assertInstanceOf(InstrumentedComponent.class,
                    context.getComponent("google-pubsub"));
            component.setEndpoint(broker.endpoint());
            runtime = new PubSubTriggerSnapshotRuntime(broker, context, "NONE".equals(parameters.get("ackMode")));
            context.getRegistry().bind(PubSubTriggerSnapshotRuntime.registryName(nodeId), runtime);
            SnapshotFixtureRouteScope.bind(context, List.of(route), deploymentId + ':' + binding.definition().getId(),
                    Map.of("pubSubProcessor", new PubSubProcessor(), "chainStartProcessor", new ChainStartProcessor(),
                            "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                            "chainFinishProcessor", (Processor) runtime::completed));
            String idempotencyId = "Idempotency--" + nodeId;
            AdviceWith.adviceWith(context, route, false, advice -> {
                if (SnapshotRouteNodes.findById(List.of(route), idempotencyId).isEmpty()) {
                    advice.weaveById(nodeId).after().process(runtime::received);
                } else {
                    advice.weaveById(idempotencyId).before().process(runtime::received);
                }
            });
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            if (consumer == null) {
                consumer = assertInstanceOf(CustomGooglePubSubConsumer.class, context.getRoute(route.getId()).getConsumer());
                GooglePubsubEndpoint endpoint = assertInstanceOf(GooglePubsubEndpoint.class, consumer.getEndpoint());
                assertTrue(!endpoint.isSynchronousPull(), "Generated Pub/Sub trigger must use streaming pull.");
            }
            Map<String, Object> control = controls(binding.interaction(invocation.getId()));
            runtime.beforeInvocation(binding.interaction(invocation.getId()));
            broker.maxDeliveriesPerMessage(((Number) control.getOrDefault("maxDeliveriesPerMessage", Integer.MAX_VALUE)).intValue());
            if (Boolean.TRUE.equals(control.get("startupUnavailable"))) {
                await(() -> broker.statistics().rejectedConnections() > 0, "startup connection rejection");
                runtime.assertNothingDelivered();
                broker.restore();
            }
            if ("create".equals(control.get("subscriptionAction"))) {
                broker.createSubscription();
            }
            if ("recreate-after-failures".equals(control.get("subscriptionAction"))) {
                long created = retention.createdCount();
                broker.deleteSubscription();
                await(() -> retention.createdCount() >= created + 5, "failed Subscriber recreation");
                broker.createSubscription();
            }
            String action = (String) control.get("consumerAction");
            if ("stop-until-published".equals(action)) {
                stopConsumer();
                runtime.afterPublish(() -> {
                    runtime.assertNothingDelivered();
                    startConsumer();
                });
            } else if ("restart".equals(action)) {
                stopConsumer();
                startConsumer();
            } else if ("disconnect-until-published".equals(action)) {
                long rejected = broker.statistics().rejectedConnections();
                broker.disconnect();
                runtime.afterPublish(() -> {
                    await(() -> broker.statistics().rejectedConnections() > rejected, "consumer reconnection during outage");
                    runtime.assertNothingDelivered();
                    broker.restore();
                });
            } else if ("probe-unavailable".equals(action) || "stop-while-unavailable".equals(action)) {
                long rejected = broker.statistics().rejectedConnections();
                broker.disconnect();
                await(() -> broker.statistics().rejectedConnections() > rejected, "connection rejection");
                if ("stop-while-unavailable".equals(action)) {
                    stopConsumer();
                    await(() -> broker.statistics().activeStreams() == 0, "stream shutdown during outage");
                    broker.restore();
                    startConsumer();
                }
            }
            invocationStart = broker.statistics();
            if (control.get("resourceProbe") instanceof Map<?, ?> probe) {
                verifyResources(probe, Boolean.TRUE.equals(control.get("missingSubscription")), "probe-unavailable".equals(action));
                broker.restore();
            }
        }

        @Override
        public String timeoutDiagnostics() {
            return "Pub/Sub route=" + route.getId()
                    + ", routeStatus=" + context.getRouteController().getRouteStatus(route.getId())
                    + ", createdSubscribers=" + retention.createdCount()
                    + ", subscriberStates=" + retention.liveSubscriberStates()
                    + ", broker=" + broker.statistics()
                    + ", runtime={" + runtime.timeoutDiagnostics() + "}";
        }

        private void verifyResources(Map<?, ?> controls, boolean missingSubscription, boolean unavailable) {
            int rounds = ((Number) (controls.containsKey("rounds") ? controls.get("rounds") : 3)).intValue();
            long interval = ((Number) (controls.containsKey("intervalMillis") ? controls.get("intervalMillis") : 5000)).longValue();
            int maxSubscribers = ((Number) (controls.containsKey("maxLiveSubscribers") ? controls.get("maxLiveSubscribers") : 2)).intValue();
            long maxHeap = ((Number) (controls.containsKey("maxHeapGrowthBytes") ? controls.get("maxHeapGrowthBytes") : 33554432)).longValue();
            int maxConnections = ((Number) (controls.containsKey("maxConnectionGrowth") ? controls.get("maxConnectionGrowth") : 2)).intValue();
            int maxThreads = ((Number) (controls.containsKey("maxThreadGrowth") ? controls.get("maxThreadGrowth") : 12)).intValue();
            if (missingSubscription) {
                await(() -> retention.createdCount() >= 5, "repeated terminal subscriber failures");
            } else if (!unavailable) {
                await(() -> broker.statistics().activeStreams() > 0, "an active idle subscriber stream");
            }
            PubSubSubscriberRetentionProbe.Snapshot baseline = retention.sampleAfterGc();
            long rejected = broker.statistics().rejectedConnections();
            int connections = broker.statistics().activeConnections();
            for (int index = 0; index < rounds; index++) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);
                await(() -> System.nanoTime() >= deadline
                        || broker.statistics().activeConnections() - connections > maxConnections,
                        "resource observation interval or connection limit");
                runtime.assertNothingDelivered();
                PubSubSubscriberRetentionProbe.Snapshot sample = retention.sampleAfterGc();
                LOG.info("Pub/Sub resource sample {}: created={}, live={}, heap={}, threads={}, connections={}, streams={}.",
                        index + 1, sample.createdCount(), sample.liveSubscribers(), sample.heapUsedBytes(), sample.threadCount(),
                        broker.statistics().activeConnections(), broker.statistics().activeStreams());
                assertTrue(sample.liveSubscribers() <= maxSubscribers, "Pub/Sub retains too many Subscribers after GC: " + sample);
                assertTrue(sample.heapUsedBytes() - baseline.heapUsedBytes() <= maxHeap, "Pub/Sub retained heap grows during retry: " + sample);
                assertTrue(sample.threadCount() - baseline.threadCount() <= maxThreads, "Pub/Sub threads grow during retry: " + sample);
                assertTrue(broker.statistics().activeConnections() - connections <= maxConnections,
                        "Pub/Sub connections grow during retry: " + broker.statistics());
                if (missingSubscription) {
                    assertTrue(sample.createdCount() > baseline.createdCount(), "Pub/Sub must keep retrying the missing subscription.");
                } else {
                    assertEquals(baseline.createdCount(), sample.createdCount(), "UNAVAILABLE must retry within the existing Subscriber.");
                }
            }
            if (unavailable) {
                assertTrue(broker.statistics().rejectedConnections() > rejected, "Pub/Sub must attempt to reconnect during resource observation.");
            } else if (!missingSubscription) {
                assertTrue(broker.statistics().activeStreams() > 0, "Pub/Sub must keep its stream while the publisher is idle.");
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            runtime.verifyInvocation(invocation.getId());
            Map<String, Object> control = controls(binding.interaction(invocation.getId()));
            int acknowledgments = ((Number) control.getOrDefault("expectedAcknowledgments", runtime.currentMessageIds().size())).intValue();
            int negative = ((Number) control.getOrDefault("expectedNegativeAcknowledgments", 0)).intValue();
            await(() -> broker.statistics().acknowledgedCount() - invocationStart.acknowledgedCount() >= acknowledgments
                    && broker.statistics().negativeAcknowledgedCount() - invocationStart.negativeAcknowledgedCount() >= negative,
                    "broker acknowledgment requests");
            assertEquals(acknowledgments, broker.statistics().acknowledgedCount() - invocationStart.acknowledgedCount(),
                    "Pub/Sub acknowledgment count");
            assertEquals(negative, broker.statistics().negativeAcknowledgedCount() - invocationStart.negativeAcknowledgedCount(),
                    "Pub/Sub negative acknowledgment count");
            if (acknowledgments == runtime.currentMessageIds().size()) {
                assertTrue(broker.statistics().acknowledgedMessageIds().containsAll(runtime.currentMessageIds()),
                        "Pub/Sub must acknowledge the published message IDs.");
            }
            if (!stopped) {
                assertSame(consumer, context.getRoute(route.getId()).getConsumer(), "Pub/Sub recovery must retain its consumer.");
            }
            if (binding.interaction(invocation.getId()).getExpectedRequest().getDestination() != null) {
                assertEquals(binding.interaction(invocation.getId()).getExpectedRequest().getDestination(), broker.subscription());
            }
        }

        private void stopConsumer() {
            try {
                context.getRouteController().stopRoute(route.getId(), 15, TimeUnit.SECONDS);
                stopped = true;
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot stop the Pub/Sub trigger route.", exception);
            }
        }

        private void startConsumer() {
            try {
                context.getRouteController().startRoute(route.getId());
                consumer = context.getRoute(route.getId()).getConsumer();
                stopped = false;
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot start the Pub/Sub trigger route.", exception);
            }
        }

        @Override
        public void releasePendingWork() {
            runtime.acknowledgePending();
            broker.restore();
        }

        @Override
        public void awaitCompletion() {
            stopConsumer();
            await(() -> broker.statistics().activeStreams() == 0, "all subscriber streams to stop");
        }

        @Override
        public void verify() {
            runtime.verifyStopped();
        }

        @Override
        public void beforeContextStop() {
            if (runtime != null) {
                runtime.acknowledgePending();
            }
            broker.restore();
        }

        @Override
        public void close() throws Exception {
            try {
                broker.close();
            } finally {
                previousLogLevels.forEach((name, level) -> org.jboss.logmanager.Logger.getLogger(name).setLevel(level));
            }
        }
    }

    private static void await(BooleanSupplier condition, String description) {
        var limiter = RateLimiterBuilder.newBuilder().withRate(10, TimeUnit.SECONDS).withConstantThroughput().build();
        try {
            Unreliables.retryUntilTrue(20, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition::getAsBoolean));
        } catch (RuntimeException exception) {
            throw new AssertionError("Timed out waiting for Pub/Sub " + description + '.', exception);
        }
    }

    private static final class InstrumentedComponent extends CustomGooglePubSubComponent {
        private final PubSubSubscriberRetentionProbe retention;

        private InstrumentedComponent(PubSubSubscriberRetentionProbe retention) {
            this.retention = retention;
        }

        @Override
        public Subscriber getSubscriber(String subscription, MessageReceiver receiver, GooglePubsubEndpoint endpoint) throws IOException {
            Subscriber subscriber = super.getSubscriber(subscription, receiver, endpoint);
            retention.track(subscriber);
            return subscriber;
        }
    }
}
