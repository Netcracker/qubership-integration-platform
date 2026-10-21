package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.StepDefinition;
import org.apache.kafka.common.TopicPartition;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.camel.KafkaHeaderStringDeserializer;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.consumer.KafkaBGConsumer;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.DefaultKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.KafkaTriggerProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFailureExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class KafkaTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final Set<String> CONSUMER_ACTIONS = Set.of("stop-until-published", "disconnect-until-published",
            "restart-broker", "stop-peer-until-published", "start-peer", "stop-during-processing", "exceed-poll-interval");
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper().copy()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    @Override
    public String getId() {
        return "kafka-trigger";
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("Kafka trigger fixture requires at least one binding.");
        }
        bindings.forEach(binding -> binding.interactionsByInvocationId().values()
                .forEach(KafkaTriggerSnapshotFixtureProvider::validateInteraction));
        return new KafkaTriggerSnapshotFixture(deploymentId, bindings);
    }

    private static void validateInteraction(SnapshotFixtureInteraction interaction) {
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        if (expected == null || expected.getCount() < 0) {
            throw new IllegalArgumentException("Kafka trigger requires a nonnegative expectedRequest.count.");
        }
        if (expected.getMethod() != null || expected.getPath() != null || expected.getQuery() != null) {
            throw new IllegalArgumentException("Kafka trigger supports expectedRequest count, destination, key, body, headers, and properties.");
        }
        if (interaction.getExpectedExchanges().stream().anyMatch(exchange -> exchange.getExpectedFailure() != null)) {
            throw new IllegalArgumentException("Kafka trigger input expectations cannot define expectedFailure.");
        }
        SnapshotFixtureResponse response = interaction.getResponse();
        if (response == null) {
            return;
        }
        if (response.hasExplicitStatus() || response.getBody() != null || !response.getHeaders().isEmpty()
                || response.getDelayMillis() != null
                || !Set.of("consumerAction", "awaitCommit", "startupRecords", "expectedStartupRecords", "committedOffsets",
                        "ignoredRecords", "expectedAbsentHeaders", "expectedConsumerFailure", "expectedCompletionBody",
                        "expectedCompletionHeaders", "expectedCompletionProperties", "expectedCompletions", "allowRedelivery",
                        "startupUnavailable", "resetConsumerFailure", "initialConsumerAccess", "consumerAccess", "expectedMembers",
                        "minSuccessfulAuthentications", "minFailedAuthentications", "minSuccessfulTlsHandshakes")
                .containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException("Kafka trigger response must use supported observation and consumer controls.");
        }
        Map<String, Object> controls = response.getProperties();
        if (controls.containsKey("consumerAction") && !CONSUMER_ACTIONS
                .contains(controls.get("consumerAction"))) {
            throw new IllegalArgumentException("Unsupported Kafka trigger consumerAction: " + controls.get("consumerAction"));
        }
        for (String key : List.of("awaitCommit", "allowRedelivery", "startupUnavailable", "resetConsumerFailure")) {
            if (controls.containsKey(key) && !(controls.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a boolean.");
            }
        }
        for (String key : List.of("initialConsumerAccess", "consumerAccess")) {
            if (controls.containsKey(key) && !Set.of("allow", "topic-denied", "group-denied").contains(controls.get(key))) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be allow, topic-denied, or group-denied.");
            }
        }
        for (String key : List.of("expectedMembers", "minSuccessfulAuthentications", "minFailedAuthentications", "minSuccessfulTlsHandshakes")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof Number value)
                    || value.intValue() < 0 || value.doubleValue() != value.intValue())) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a nonnegative integer.");
            }
        }
        for (String key : List.of("startupRecords", "expectedStartupRecords", "ignoredRecords")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof List<?> records)
                    || records.stream().anyMatch(record -> !(record instanceof Map<?, ?>)))) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a list of producer records.");
            }
        }
        for (String key : List.of("expectedCompletionHeaders", "expectedCompletionProperties")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof Map<?, ?> values)
                    || values.keySet().stream().anyMatch(name -> !(name instanceof String)))) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a map with string keys.");
            }
        }
        if (controls.containsKey("expectedAbsentHeaders") && (!(controls.get("expectedAbsentHeaders") instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String)))) {
            throw new IllegalArgumentException("Kafka trigger expectedAbsentHeaders must be a list of header names.");
        }
        if (controls.containsKey("expectedCompletions") && !(controls.get("expectedCompletions") instanceof List<?>)) {
            throw new IllegalArgumentException("Kafka trigger expectedCompletions must be a list of exchange expectations.");
        }
        if (controls.containsKey("committedOffsets")) {
            parseCommittedOffsets(controls.get("committedOffsets"));
        }
        if (controls.containsKey("expectedConsumerFailure")) {
            expectedConsumerFailure(controls);
        }
    }

    private static SnapshotFailureExpectation expectedConsumerFailure(Map<String, Object> controls) {
        if (!controls.containsKey("expectedConsumerFailure")) {
            return null;
        }
        return MAPPER.convertValue(Map.of("expectedFailure", controls.get("expectedConsumerFailure")),
                SnapshotFixtureExchangeExpectation.class).getExpectedFailure();
    }

    private static Map<TopicPartition, Long> parseCommittedOffsets(Object value) {
        if (!(value instanceof List<?> offsets)) {
            throw new IllegalArgumentException("Kafka trigger committedOffsets must be a list of topic, partition, and offset entries.");
        }
        Map<TopicPartition, Long> result = new LinkedHashMap<>();
        for (Object item : offsets) {
            if (!(item instanceof Map<?, ?> offset) || !offset.keySet().equals(Set.of("topic", "partition", "offset"))
                    || !(offset.get("topic") instanceof String topic) || topic.isBlank()
                    || !(offset.get("partition") instanceof Number partition) || partition.intValue() < 0
                    || partition.doubleValue() != partition.intValue()
                    || !(offset.get("offset") instanceof Number position) || position.longValue() < 0
                    || position.doubleValue() != position.longValue()) {
                throw new IllegalArgumentException("Kafka trigger committedOffsets requires a topic and nonnegative integer partition and offset.");
            }
            TopicPartition key = new TopicPartition(topic, partition.intValue());
            if (result.put(key, position.longValue()) != null) {
                throw new IllegalArgumentException("Kafka trigger committedOffsets repeats partition '" + key + "'.");
            }
        }
        return result;
    }

    private static final class KafkaTriggerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final KafkaTriggerSnapshotBroker broker = new KafkaTriggerSnapshotBroker();
        private final List<ConsumerRuntime> consumers = new ArrayList<>();
        private final List<KafkaTriggerSnapshotPollFailureObserver> failureObservers = new ArrayList<>();
        private CamelContext context;
        private boolean ready;

        private record ConsumerRuntime(SnapshotFixtureBinding binding, RouteDefinition route, List<RouteDefinition> routes,
                                       KafkaTriggerSnapshotRuntime runtime, KafkaTriggerSnapshotProcessingGate processing,
                                       List<String> topics, Map<String, String> parameters,
                                       String groupId, Map<TopicPartition, Long> initialOffsets,
                                       Map<TopicPartition, Long> committedOffsets) {
        }

        private KafkaTriggerSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) {
            BlueGreenStatePublisher publisher = CDI.current().select(BlueGreenStatePublisher.class).get();
            when(publisher.getBlueGreenState()).thenReturn(new BlueGreenState(
                    new NamespaceVersion("snapshot", State.ACTIVE, new Version(1)), OffsetDateTime.parse("2026-01-01T00:00:00Z")));
            camelContext.getRegistry().bind("kafkaHeaderStringDeserializer", new KafkaHeaderStringDeserializer());
            if (camelContext.getComponent("kafka-custom", false) == null) {
                camelContext.addComponent("kafka-custom", new KafkaCustomComponent(camelContext));
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            context = camelContext;
            RouteDefinition first = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes,
                    bindings.getFirst().definition().getNodeId()), "Kafka trigger fixture requires its generated route").route();
            broker.configureSecurity(endpointParameters(first));
            broker.start();
            for (SnapshotFixtureBinding binding : bindings) {
                configureConsumer(routes, binding);
            }
            if (bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .anyMatch(interaction -> Boolean.TRUE.equals(controls(interaction).get("startupUnavailable")))) {
                broker.disconnectBeforeStartup();
            }
        }

        private static Map<String, String> endpointParameters(RouteDefinition route) {
            String uri = route.getInput().getUri();
            if (!uri.startsWith("kafka-custom:") || uri.indexOf('?') < 0) {
                throw new IllegalArgumentException("Kafka trigger fixture requires a generated kafka-custom consumer endpoint.");
            }
            return new LinkedHashMap<>(SnapshotEndpointParameters.parse(uri.substring(uri.indexOf('?') + 1),
                    name -> new IllegalArgumentException("Kafka trigger endpoint repeats parameter '" + name + "'.")));
        }

        private void configureConsumer(List<RouteDefinition> routes, SnapshotFixtureBinding binding) throws Exception {
            String nodeId = binding.definition().getNodeId();
            RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, nodeId),
                    "Kafka trigger expected one node '" + nodeId + "'").route();
            String originalUri = route.getInput().getUri();
            int queryStart = originalUri.indexOf('?');
            if (!originalUri.startsWith("kafka-custom:") || queryStart < 0) {
                throw new IllegalArgumentException("Kafka trigger fixture requires a generated kafka-custom consumer endpoint.");
            }
            Map<String, String> parameters = new LinkedHashMap<>(SnapshotEndpointParameters.parse(originalUri.substring(queryStart + 1),
                    name -> new IllegalArgumentException("Kafka trigger endpoint repeats parameter '" + name + "'.")));
            String reference = parameters.get("kafkaClientFactory");
            KafkaBGClientFactory factory = context.getRegistry().lookupByNameAndType(reference.substring(1), KafkaBGClientFactory.class);
            assertInstanceOf(DefaultKafkaBGClientFactory.class, factory, "Kafka trigger must retain its generated client factory.");
            List<String> topics = List.of(originalUri.substring("kafka-custom:".length(), queryStart).split(","));
            broker.createTopics(topics);
            for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                for (String key : List.of("startupRecords", "expectedStartupRecords", "ignoredRecords")) {
                    for (Object record : records(controls(interaction), key)) {
                        Object topic = ((Map<?, ?>) record).get("topic");
                        if (topic instanceof String name) {
                            broker.createTopics(List.of(name));
                        }
                    }
                }
            }
            String groupId = parameters.get("groupId");
            KafkaTriggerSnapshotRuntime runtime = new KafkaTriggerSnapshotRuntime(broker.producerAddress(), topics, groupId);
            KafkaTriggerSnapshotProcessingGate processing = new KafkaTriggerSnapshotProcessingGate();
            List<RouteDefinition> groupRoutes = routes.stream()
                    .filter(candidate -> candidate.getInput().getUri().startsWith("kafka-custom:")
                            && groupId.equals(endpointParameters(candidate).get("groupId"))).toList();
            Map<TopicPartition, Long> initialOffsets = new LinkedHashMap<>();
            Map<TopicPartition, Long> committedOffsets = new LinkedHashMap<>();
            consumers.add(new ConsumerRuntime(binding, route, groupRoutes, runtime, processing, topics, parameters,
                    groupId, initialOffsets, committedOffsets));
            context.getRegistry().bind(KafkaTriggerSnapshotRuntime.registryName(nodeId), runtime);
            for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                Map<String, Object> configured = controls(interaction);
                runtime.publishIgnored(records(configured, "startupRecords"));
                runtime.publishStartup(records(configured, "expectedStartupRecords"));
                committedOffsets.putAll(parseCommittedOffsets(configured.getOrDefault("committedOffsets", List.of())));
            }
            initialOffsets.putAll("earliest".equals(parameters.get("autoOffsetReset"))
                    ? broker.beginningOffsets(topics) : broker.endOffsets(topics));
            if (!initialOffsets.keySet().containsAll(committedOffsets.keySet())) {
                throw new IllegalArgumentException("Kafka trigger committedOffsets must reference subscribed topic partitions.");
            }
            broker.commitOffsets(groupId, committedOffsets);
            initialOffsets.putAll(committedOffsets);
            String initialAccess = binding.interactionsByInvocationId().values().stream()
                    .map(interaction -> controls(interaction).get("initialConsumerAccess")).filter(java.util.Objects::nonNull)
                    .map(String.class::cast).findFirst().orElse("allow");
            broker.applyConsumerAccess(topics, groupId, !"topic-denied".equals(initialAccess), !"group-denied".equals(initialAccess));
            for (RouteDefinition memberRoute : groupRoutes) {
                configureMemberRoute(memberRoute, binding, runtime, processing);
            }
        }

        private void configureMemberRoute(RouteDefinition route, SnapshotFixtureBinding binding,
                                          KafkaTriggerSnapshotRuntime runtime, KafkaTriggerSnapshotProcessingGate processing) throws Exception {
            String originalUri = route.getInput().getUri();
            Map<String, String> parameters = endpointParameters(route);
            String reference = parameters.get("kafkaClientFactory");
            assertInstanceOf(DefaultKafkaBGClientFactory.class,
                    context.getRegistry().lookupByNameAndType(reference.substring(1), KafkaBGClientFactory.class));
            parameters.put("brokers", broker.consumerAddress());
            broker.configureEndpointSecurity(parameters);
            StringBuilder endpoint = new StringBuilder(originalUri.substring(0, originalUri.indexOf('?')));
            parameters.forEach((key, value) -> endpoint.append(endpoint.indexOf("?") < 0 ? '?' : '&').append(key).append('=').append(value));
            route.getInput().setUri(endpoint.toString());
            KafkaTriggerProcessor trigger = new KafkaTriggerProcessor(CDI.current().select(JsonMessageValidator.class).get());
            SnapshotFixtureRouteScope.bind(context, List.of(route), deploymentId + ':' + binding.definition().getId(),
                    Map.of("kafkaTriggerProcessor", trigger, "chainStartProcessor", new ChainStartProcessor(),
                            "interruptExchangeProcessor", new InterruptExchangeProcessor(),
                            "chainFinishProcessor", (Processor) runtime::completed));
            String nodeId = route.getOutputs().stream().filter(StepDefinition.class::isInstance)
                    .map(StepDefinition.class::cast).findFirst().orElseThrow().getId();
            context.getRegistry().bind(KafkaTriggerSnapshotRuntime.registryName(nodeId), runtime);
            String idempotencyId = "Idempotency--" + nodeId;
            AdviceWith.adviceWith(context, route, false, advice -> {
                if (SnapshotRouteNodes.findById(List.of(route), idempotencyId).isEmpty()) {
                    advice.weaveById(nodeId).after().process(runtime::received).process(processing);
                } else {
                    advice.weaveById(idempotencyId).before().process(runtime::received).process(processing);
                }
            });
            KafkaTriggerSnapshotPollFailureObserver observer = new KafkaTriggerSnapshotPollFailureObserver(
                    route::getId, runtime::consumerFailure);
            context.getManagementStrategy().addEventNotifier(observer);
            observer.start();
            failureObservers.add(observer);
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            if (!ready) {
                for (ConsumerRuntime consumer : consumers) {
                    consumer.routes().forEach(route -> assertInstanceOf(KafkaBGConsumer.class, context.getRoute(route.getId()).getConsumer()));
                    Map<String, Object> configured = controls(consumer.binding().interaction(invocation.getId()));
                    if (Boolean.TRUE.equals(configured.get("startupUnavailable"))) {
                        await(() -> broker.authenticationStatistics().rejectedConnections() > 0,
                                "Kafka consumer to attempt a connection during the startup outage");
                        assertEquals(consumer.committedOffsets(), broker.snapshotCommittedOffsets(consumer.groupId()),
                                "Startup outage must preserve the original group offsets.");
                        consumer.runtime().assertNothingDelivered();
                        broker.restore();
                    }
                    if (configured.containsKey("expectedConsumerFailure") && records(configured, "ignoredRecords").isEmpty()) {
                        broker.awaitOffsetFailure(consumer.groupId(), consumer.committedOffsets(), consumer.runtime()::hasConsumerFailure);
                    } else {
                        broker.awaitReady(consumer.groupId(), consumer.initialOffsets());
                        if (configured.get("expectedMembers") instanceof Number count) {
                            broker.awaitMembers(consumer.groupId(), count.intValue(), consumer.topics());
                        }
                    }
                }
                ready = true;
            }
            for (ConsumerRuntime consumer : consumers) {
                Map<String, Object> controls = controls(consumer.binding().interaction(invocation.getId()));
                consumer.runtime().beforeInvocation(controls);
                if (controls.get("consumerAccess") instanceof String access) {
                    consumer.runtime().clearConsumerFailure();
                    broker.applyConsumerAccess(consumer.topics(), consumer.groupId(),
                            !"topic-denied".equals(access), !"group-denied".equals(access));
                }
                Object action = controls.get("consumerAction");
                if (action != null) {
                    broker.awaitCommitted(consumer.groupId(), consumer.runtime().publishedOffsets());
                }
                if ("stop-until-published".equals(action)) {
                    context.getRouteController().stopRoute(consumer.route().getId());
                    assertTrue(context.getRouteController().getRouteStatus(consumer.route().getId()).isStopped(),
                            "Kafka consumer route must stop before publishing its backlog.");
                    consumer.runtime().afterPublish(metadata -> {
                        consumer.runtime().assertNothingDelivered();
                        context.getRouteController().startRoute(consumer.route().getId());
                    });
                } else if ("disconnect-until-published".equals(action)) {
                    KafkaBGConsumer original = (KafkaBGConsumer) context.getRoute(consumer.route().getId()).getConsumer();
                    broker.disconnect();
                    consumer.runtime().afterPublish(metadata -> {
                        consumer.runtime().assertNothingDelivered();
                        broker.restore();
                        assertSame(original, context.getRoute(consumer.route().getId()).getConsumer(),
                                "Connection recovery must retain the existing Kafka consumer.");
                    });
                } else if ("restart-broker".equals(action)) {
                    KafkaBGConsumer original = (KafkaBGConsumer) context.getRoute(consumer.route().getId()).getConsumer();
                    Map<TopicPartition, Long> offsets = broker.snapshotCommittedOffsets(consumer.groupId());
                    broker.restart();
                    assertEquals(offsets, broker.snapshotCommittedOffsets(consumer.groupId()), "Broker restart must preserve committed offsets.");
                    assertSame(original, context.getRoute(consumer.route().getId()).getConsumer(), "Broker recovery must retain the Kafka consumer.");
                } else if ("stop-peer-until-published".equals(action)) {
                    RouteDefinition peer = peerRoute(consumer);
                    context.getRouteController().stopRoute(peer.getId());
                    int remaining = Integer.parseInt(consumer.parameters().get("consumersCount"));
                    broker.awaitMembers(consumer.groupId(), remaining, consumer.topics());
                } else if ("start-peer".equals(action)) {
                    RouteDefinition peer = peerRoute(consumer);
                    context.getRouteController().startRoute(peer.getId());
                    int expected = consumer.routes().stream().mapToInt(route ->
                            Integer.parseInt(endpointParameters(route).get("consumersCount"))).sum();
                    broker.awaitMembers(consumer.groupId(), expected, consumer.topics());
                } else if ("stop-during-processing".equals(action) || "exceed-poll-interval".equals(action)) {
                    KafkaTriggerSnapshotProcessingGate.Gate gate = consumer.processing().blockNext();
                    consumer.runtime().afterPublish(metadata -> {
                        gate.awaitStarted();
                        try {
                            if ("stop-during-processing".equals(action)) {
                                stopDuringProcessing(consumer, gate);
                            } else {
                                broker.awaitMembers(consumer.groupId(), 0, consumer.topics());
                            }
                        } finally {
                            gate.open();
                        }
                    });
                }
                consumer.runtime().publishIgnored(records(controls, "ignoredRecords"));
                if (Boolean.TRUE.equals(controls.get("resetConsumerFailure"))) {
                    consumer.runtime().clearConsumerFailure();
                }
            }
        }

        private static RouteDefinition peerRoute(ConsumerRuntime consumer) {
            return SnapshotRouteNodes.requireSingle(consumer.routes().stream().filter(route -> route != consumer.route()).toList(),
                    "Kafka group scenario requires one peer route");
        }

        private void stopDuringProcessing(ConsumerRuntime consumer, KafkaTriggerSnapshotProcessingGate.Gate gate) throws Exception {
            Map<TopicPartition, Long> before = broker.snapshotCommittedOffsets(consumer.groupId());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> {
                    try {
                        context.getRouteController().stopRoute(consumer.route().getId());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }, executor);
                try {
                    KafkaBGConsumer kafkaConsumer = (KafkaBGConsumer) context.getRoute(consumer.route().getId()).getConsumer();
                    await(kafkaConsumer::isStoppingOrStopped,
                            "Kafka consumer to begin stopping while a record is in progress");
                    assertFalse(stopped.isDone(), "Kafka shutdown must wait for the record being processed.");
                    assertEquals(before, broker.snapshotCommittedOffsets(consumer.groupId()),
                            "Kafka must not commit the record before processing finishes.");
                } finally {
                    gate.open();
                }
                stopped.get(20, TimeUnit.SECONDS);
            }
            broker.awaitCommitted(consumer.groupId(), consumer.runtime().publishedOffsets());
            context.getRouteController().startRoute(consumer.route().getId());
        }

        private static void await(BooleanSupplier condition, String description) {
            var limiter = RateLimiterBuilder.newBuilder().withRate(10, TimeUnit.SECONDS).withConstantThroughput().build();
            try {
                Unreliables.retryUntilTrue(20, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition::getAsBoolean));
            } catch (RuntimeException exception) {
                throw new AssertionError("Timed out waiting for " + description + ".", exception);
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                SnapshotFixtureInteraction interaction = consumer.binding().interaction(invocation.getId());
                if (Boolean.TRUE.equals(controls(interaction).get("awaitCommit"))) {
                    broker.awaitCommitted(consumer.groupId(), consumer.runtime().publishedOffsets());
                }
                if (controls(interaction).get("expectedMembers") instanceof Number count) {
                    broker.awaitMembers(consumer.groupId(), count.intValue(), consumer.topics());
                }
                consumer.runtime().verifyInvocation(interaction, invocation.getId());
                if ("allow".equals(controls(interaction).get("consumerAccess")) && consumer.runtime().hasConsumerFailure()) {
                    SnapshotFailureExpectation previousFailure = consumer.binding().interactionsByInvocationId().values().stream()
                            .filter(previous -> controls(previous).containsKey("initialConsumerAccess"))
                            .map(previous -> expectedConsumerFailure(controls(previous))).findFirst().orElseThrow();
                    SnapshotFailureAssertions.assertMatches(previousFailure, consumer.runtime().getConsumerFailure(),
                            "Kafka trigger access recovery before successful consumption");
                    consumer.runtime().clearConsumerFailure();
                }
                SnapshotFailureAssertions.assertMatches(expectedConsumerFailure(controls(interaction)),
                        consumer.runtime().getConsumerFailure(), "Kafka trigger invocation '" + invocation.getId() + "' consumer");
                if (controls(interaction).containsKey("expectedConsumerFailure")) {
                    broker.assertCommittedOffsetsUnchanged(consumer.groupId(), consumer.committedOffsets());
                }
                KafkaConnectionProxy.Statistics statistics = broker.authenticationStatistics();
                for (String key : List.of("minSuccessfulAuthentications", "minFailedAuthentications", "minSuccessfulTlsHandshakes")) {
                    if (controls(interaction).get(key) instanceof Number count) {
                        long observed = switch (key) {
                            case "minSuccessfulAuthentications" -> statistics.successfulAuthentications();
                            case "minFailedAuthentications" -> statistics.failedAuthentications();
                            default -> statistics.successfulTlsHandshakes();
                        };
                        assertTrue(observed >= count.longValue(), "Kafka trigger " + key + " must be at least " + count);
                    }
                }
            }
            broker.verify();
        }

        @Override
        public void releasePendingWork() {
            consumers.forEach(consumer -> consumer.processing().close());
            broker.restore();
        }

        @Override
        public void awaitCompletion() throws Exception {
            for (ConsumerRuntime consumer : consumers) {
                for (RouteDefinition route : consumer.routes()) {
                    context.getRouteController().stopRoute(route.getId());
                }
                consumer.runtime().verifyAfterConsumerStopped();
                if (consumer.runtime().getConsumerFailure() != null) {
                    broker.assertCommittedOffsetsUnchanged(consumer.groupId(), consumer.committedOffsets());
                }
            }
        }

        @Override
        public void verify() {
            broker.verify();
        }

        @Override
        public void close() throws Exception {
            try {
                for (KafkaTriggerSnapshotPollFailureObserver observer : failureObservers) {
                    context.getManagementStrategy().removeEventNotifier(observer);
                    observer.stop();
                }
                for (ConsumerRuntime consumer : consumers) {
                    consumer.processing().close();
                    consumer.runtime().close();
                }
            } finally {
                broker.close();
            }
        }

        private static List<?> records(Map<String, Object> controls, String key) {
            return controls.get(key) instanceof List<?> records ? records : List.of();
        }

        private static Map<String, Object> controls(SnapshotFixtureInteraction interaction) {
            return interaction.getResponse() == null ? Map.of() : interaction.getResponse().getProperties();
        }
    }
}
