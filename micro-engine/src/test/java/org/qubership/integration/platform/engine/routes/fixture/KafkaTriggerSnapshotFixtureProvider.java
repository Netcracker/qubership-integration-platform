package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.model.Version;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.OffsetSetupStrategy;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.VersionedGroupId;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.StepDefinition;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.camel.KafkaHeaderStringDeserializer;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtendedImpl;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final Set<String> CONSUMER_ACTIONS = Set.of("stop-until-published", "disconnect-until-published",
            "restart-broker", "stop-peer-until-published", "start-peer", "stop-during-processing", "exceed-poll-interval",
            "truncate-before-restart", "reset-committed-before-restart", "transition-with-backlog", "lose-broker-during-processing");
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
                        "minSuccessfulAuthentications", "minFailedAuthentications", "minSuccessfulTlsHandshakes",
                        "minFailedTlsHandshakes", "tlsCertificate", "partitionCount", "backlogRecords", "truncateBeforeOffsets",
                        "expectedFetchOffsets", "resetCommittedOffsets", "maasClassifier", "maasNamespace", "maasTopic",
                        "maasMissingTopic", "blueGreenState", "initialBlueGreenState", "filteredRecords",
                        "metadataRefreshMillis", "expectedCommitAuthorizationFailure", "scramPassword", "expectedMaxPollRecords",
                        "siblingOffsets", "expectedReplayRecords")
                .containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException("Kafka trigger response must use supported observation and consumer controls.");
        }
        Map<String, Object> controls = response.getProperties();
        if (controls.containsKey("consumerAction") && !CONSUMER_ACTIONS
                .contains(controls.get("consumerAction"))) {
            throw new IllegalArgumentException("Unsupported Kafka trigger consumerAction: " + controls.get("consumerAction"));
        }
        for (String key : List.of("awaitCommit", "allowRedelivery", "startupUnavailable", "resetConsumerFailure", "maasMissingTopic",
                "expectedCommitAuthorizationFailure")) {
            if (controls.containsKey(key) && !(controls.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a boolean.");
            }
        }
        if (controls.containsKey("metadataRefreshMillis") && (!(controls.get("metadataRefreshMillis") instanceof Number value)
                || value.intValue() <= 0 || value.doubleValue() != value.intValue())) {
            throw new IllegalArgumentException("Kafka trigger metadataRefreshMillis must be a positive integer.");
        }
        if (controls.containsKey("expectedMaxPollRecords") && (!(controls.get("expectedMaxPollRecords") instanceof Number value)
                || value.intValue() <= 0 || value.doubleValue() != value.intValue())) {
            throw new IllegalArgumentException("Kafka expectedMaxPollRecords must be a positive integer.");
        }
        if (controls.containsKey("scramPassword") && (!(controls.get("scramPassword") instanceof String password) || password.isBlank())) {
            throw new IllegalArgumentException("Kafka scramPassword must be nonblank.");
        }
        if (controls.containsKey("siblingOffsets")) {
            parseCommittedOffsets(controls.get("siblingOffsets"));
        }
        if (controls.containsKey("expectedReplayRecords") && (!(controls.get("expectedReplayRecords") instanceof List<?> records)
                || records.stream().anyMatch(replayRecord -> !(replayRecord instanceof Map<?, ?>))
                || !"transition-with-backlog".equals(controls.get("consumerAction")))) {
            throw new IllegalArgumentException("Kafka expectedReplayRecords requires a transition-with-backlog record list.");
        }
        for (String key : List.of("maasClassifier", "maasNamespace", "maasTopic")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof String value) || value.isBlank())) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a nonblank string.");
            }
        }
        if (controls.containsKey("maasClassifier") && (!controls.containsKey("maasNamespace") || !controls.containsKey("maasTopic"))) {
            throw new IllegalArgumentException("Kafka trigger MaaS controls require a namespace and topic.");
        }
        for (String key : List.of("initialConsumerAccess", "consumerAccess")) {
            if (controls.containsKey(key) && !Set.of("allow", "topic-denied", "group-denied").contains(controls.get(key))) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be allow, topic-denied, or group-denied.");
            }
        }
        for (String key : List.of("expectedMembers", "minSuccessfulAuthentications", "minFailedAuthentications",
                "minSuccessfulTlsHandshakes", "minFailedTlsHandshakes", "partitionCount")) {
            if (controls.containsKey(key) && (!(controls.get(key) instanceof Number value)
                    || value.intValue() < 0 || value.doubleValue() != value.intValue())) {
                throw new IllegalArgumentException("Kafka trigger " + key + " must be a nonnegative integer.");
            }
        }
        if (controls.containsKey("tlsCertificate")
                && !Set.of("valid", "rotated", "untrusted", "expired", "wrong-hostname").contains(controls.get("tlsCertificate"))) {
            throw new IllegalArgumentException("Kafka trigger tlsCertificate must name a supported test certificate.");
        }
        for (String key : List.of("startupRecords", "expectedStartupRecords", "ignoredRecords", "backlogRecords", "filteredRecords")) {
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
        if (controls.containsKey("truncateBeforeOffsets")) {
            parseCommittedOffsets(controls.get("truncateBeforeOffsets"));
            if (!"truncate-before-restart".equals(controls.get("consumerAction"))) {
                throw new IllegalArgumentException("Kafka trigger truncateBeforeOffsets requires truncate-before-restart.");
            }
        }
        if (controls.containsKey("expectedFetchOffsets")) {
            parseCommittedOffsets(controls.get("expectedFetchOffsets"));
        }
        if (controls.containsKey("resetCommittedOffsets")) {
            parseCommittedOffsets(controls.get("resetCommittedOffsets"));
            if (!"reset-committed-before-restart".equals(controls.get("consumerAction"))) {
                throw new IllegalArgumentException("Kafka trigger resetCommittedOffsets requires reset-committed-before-restart.");
            }
        }
        if (controls.containsKey("expectedConsumerFailure")) {
            expectedConsumerFailure(controls);
        }
        for (String name : List.of("blueGreenState", "initialBlueGreenState")) {
            if (controls.containsKey(name)) {
                parseBlueGreenState(controls.get(name));
            }
        }
    }

    private static BlueGreenState parseBlueGreenState(Object value) {
        if (!(value instanceof Map<?, ?> state)
                || !state.keySet().equals(Set.of("state", "version", "siblingState", "siblingVersion", "updateSeconds"))
                || !(state.get("state") instanceof String currentState)
                || !(state.get("siblingState") instanceof String siblingState)) {
            throw new IllegalArgumentException("Kafka blueGreenState requires state, version, siblingState, siblingVersion, and updateSeconds.");
        }
        for (String name : List.of("version", "siblingVersion", "updateSeconds")) {
            if (!(state.get(name) instanceof Number number) || number.intValue() < ("updateSeconds".equals(name) ? 0 : 1)
                    || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("Kafka blueGreenState " + name + " must be an integer in its supported range.");
            }
        }
        return new BlueGreenState(
                new NamespaceVersion("snapshot", State.valueOf(currentState), new Version(((Number) state.get("version")).intValue())),
                new NamespaceVersion("snapshot-sibling", State.valueOf(siblingState), new Version(((Number) state.get("siblingVersion")).intValue())),
                OffsetDateTime.parse("2026-01-01T00:00:00Z").plusSeconds(((Number) state.get("updateSeconds")).intValue()));
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
        private KafkaTriggerSnapshotBroker broker;
        private SnapshotWorkerBrokerPool.Lease<KafkaTriggerSnapshotBroker> brokerLease;
        private final List<ConsumerRuntime> consumers = new ArrayList<>();
        private final List<KafkaTriggerSnapshotPollFailureObserver> failureObservers = new ArrayList<>();
        private final List<KafkaTriggerSnapshotPollObserver> pollObservers = new ArrayList<>();
        private final Map<String, Map<TopicPartition, Long>> invocationOffsets = new LinkedHashMap<>();
        private final Map<String, Long> invocationCommitFailures = new LinkedHashMap<>();
        private final Map<String, SnapshotFailureExpectation> previousFailures = new LinkedHashMap<>();
        private final KafkaTriggerSnapshotMaas maas;
        private final List<java.util.function.Consumer<BlueGreenState>> stateListeners = new CopyOnWriteArrayList<>();
        private volatile BlueGreenState blueGreenState = new BlueGreenState(
                new NamespaceVersion("snapshot", State.ACTIVE, new Version(1)), OffsetDateTime.parse("2026-01-01T00:00:00Z"));
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
            maas = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(KafkaTriggerSnapshotFixture::controls).filter(controls -> controls.containsKey("maasClassifier"))
                    .findFirst().map(KafkaTriggerSnapshotMaas::new).orElse(null);
            bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .map(KafkaTriggerSnapshotFixture::controls).map(controls -> controls.get("initialBlueGreenState"))
                    .filter(java.util.Objects::nonNull).findFirst().ifPresent(state -> blueGreenState = parseBlueGreenState(state));
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            if (maas != null) {
                startBroker(Map.of("securityProtocol", "PLAINTEXT"));
            }
        }

        private void startBroker(Map<String, String> parameters) throws Exception {
            boolean reuse = SnapshotWorkerBrokerPool.reuseRequested();
            if (reuse) {
                if (maas != null || !"PLAINTEXT".equals(parameters.getOrDefault("securityProtocol", "PLAINTEXT"))) {
                    throw new IllegalArgumentException("Kafka trigger broker reuse requires a direct PLAINTEXT functional scenario.");
                }
                for (SnapshotFixtureBinding binding : bindings) {
                    for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                        Map<String, Object> configured = controls(interaction);
                        if (!Set.of("consumerAction", "awaitCommit", "startupRecords", "expectedStartupRecords", "committedOffsets",
                                "ignoredRecords", "expectedAbsentHeaders", "expectedCompletionBody", "expectedCompletionHeaders",
                                "expectedCompletionProperties", "expectedCompletions", "allowRedelivery", "expectedConsumerFailure",
                                "resetConsumerFailure", "expectedMaxPollRecords", "expectedMembers", "metadataRefreshMillis", "partitionCount")
                                .containsAll(configured.keySet())
                                || configured.containsKey("consumerAction")
                                && !"stop-until-published".equals(configured.get("consumerAction"))) {
                            throw new IllegalArgumentException("Kafka trigger broker reuse excludes fault, security, lifecycle, and resource controls.");
                        }
                    }
                }
            }
            brokerLease = SnapshotWorkerBrokerPool.acquire("kafka-trigger:plaintext", () -> {
                KafkaTriggerSnapshotBroker created = new KafkaTriggerSnapshotBroker();
                try {
                    created.configureSecurity(parameters);
                    if (reuse) {
                        created.enableReuse();
                    }
                    created.start();
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
            broker.beginScenario();
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) {
            BlueGreenStatePublisher publisher = CDI.current().select(BlueGreenStatePublisher.class).get();
            when(publisher.getBlueGreenState()).thenAnswer(ignored -> blueGreenState);
            doAnswer(invocation -> {
                stateListeners.add(invocation.getArgument(0));
                return null;
            }).when(publisher).subscribe(any());
            doAnswer(invocation -> {
                stateListeners.remove(invocation.getArgument(0));
                return null;
            }).when(publisher).unsubscribe(any());
            camelContext.getRegistry().bind("kafkaHeaderStringDeserializer", new KafkaHeaderStringDeserializer());
            if (camelContext.getComponent("kafka-custom", false) == null) {
                camelContext.addComponent("kafka-custom", new KafkaCustomComponent(camelContext));
            }
            if (maas != null) {
                maas.beforeRouteLoad(broker.consumerAddress());
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
            if (maas == null) {
                if (SnapshotWorkerBrokerPool.reuseRequested()) {
                    for (SnapshotFixtureBinding binding : bindings) {
                        RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes,
                                binding.definition().getNodeId()), "Kafka trigger fixture requires its generated route").route();
                        if (!"PLAINTEXT".equals(endpointParameters(route).getOrDefault("securityProtocol", "PLAINTEXT"))) {
                            throw new IllegalArgumentException("Kafka trigger broker reuse requires PLAINTEXT on every generated endpoint.");
                        }
                    }
                }
                startBroker(endpointParameters(first));
            }
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
            if (maas != null) {
                maas.verifyResolvedEndpoint(parameters, String.join(",", topics));
            }
            broker.createTopics(topics);
            for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                for (String key : List.of("startupRecords", "expectedStartupRecords", "ignoredRecords", "backlogRecords")) {
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
            broker.commitOffsets(groupId(groupId), committedOffsets);
            initialOffsets.putAll(committedOffsets);
            String initialAccess = binding.interactionsByInvocationId().values().stream()
                    .map(interaction -> controls(interaction).get("initialConsumerAccess")).filter(java.util.Objects::nonNull)
                    .map(String.class::cast).findFirst().orElse("allow");
            broker.applyConsumerAccess(topics, groupId(groupId), !"topic-denied".equals(initialAccess), !"group-denied".equals(initialAccess));
            broker.awaitAuthentication();
            for (RouteDefinition memberRoute : groupRoutes) {
                configureMemberRoute(memberRoute, binding, runtime, processing);
            }
        }

        private void configureMemberRoute(RouteDefinition route, SnapshotFixtureBinding binding,
                                          KafkaTriggerSnapshotRuntime runtime, KafkaTriggerSnapshotProcessingGate processing) throws Exception {
            String originalUri = route.getInput().getUri();
            Map<String, String> parameters = endpointParameters(route);
            String reference = parameters.get("kafkaClientFactory");
            KafkaBGClientFactory generatedFactory = context.getRegistry().lookupByNameAndType(reference.substring(1), KafkaBGClientFactory.class);
            assertInstanceOf(DefaultKafkaBGClientFactory.class, generatedFactory);
            binding.interactionsByInvocationId().values().stream().map(KafkaTriggerSnapshotFixture::controls)
                    .map(controls -> controls.get("expectedMaxPollRecords")).filter(java.util.Objects::nonNull).findFirst()
                    .ifPresent(value -> {
                        assertEquals(((Number) value).intValue(), Integer.parseInt(parameters.get("maxPollRecords")),
                                "Poll observation must use the exported maxPollRecords value.");
                        KafkaTriggerSnapshotPollObserver observer = new KafkaTriggerSnapshotPollObserver(generatedFactory);
                        pollObservers.add(observer);
                        String name = reference.substring(1) + "-observed-" + route.getId();
                        context.getRegistry().bind(name, observer);
                        parameters.put("kafkaClientFactory", '#' + name);
                    });
            parameters.put("brokers", broker.consumerAddress());
            binding.interactionsByInvocationId().values().stream().map(KafkaTriggerSnapshotFixture::controls)
                    .map(controls -> controls.get("metadataRefreshMillis")).filter(java.util.Objects::nonNull)
                    .findFirst().ifPresent(value -> parameters.put("metadataMaxAgeMs", value.toString()));
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
                        assertEquals(consumer.committedOffsets(), broker.snapshotCommittedOffsets(groupId(consumer)),
                                "Startup outage must preserve the original group offsets.");
                        consumer.runtime().assertNothingDelivered();
                        broker.restore();
                    }
                    if (configured.containsKey("expectedConsumerFailure") && records(configured, "ignoredRecords").isEmpty()) {
                        broker.awaitOffsetFailure(groupId(consumer), consumer.committedOffsets(), consumer.runtime()::hasConsumerFailure);
                    } else {
                        broker.awaitReady(groupId(consumer), consumer.initialOffsets());
                        if (configured.get("expectedMembers") instanceof Number count) {
                            broker.awaitMembers(groupId(consumer), count.intValue(), consumer.topics());
                        }
                    }
                }
                ready = true;
            }
            for (ConsumerRuntime consumer : consumers) {
                Map<String, Object> controls = controls(consumer.binding().interaction(invocation.getId()));
                invocationOffsets.put(groupId(consumer), broker.snapshotCommittedOffsets(groupId(consumer)));
                invocationCommitFailures.put(groupId(consumer), broker.commitAuthorizationFailures(groupId(consumer)));
                consumer.runtime().beforeInvocation(controls);
                if (controls.containsKey("blueGreenState") && !"transition-with-backlog".equals(controls.get("consumerAction"))) {
                    String previousGroup = groupId(consumer);
                    blueGreenState = parseBlueGreenState(controls.get("blueGreenState"));
                    List.copyOf(stateListeners).forEach(listener -> listener.accept(blueGreenState));
                    broker.awaitMembers(previousGroup, 0, consumer.topics());
                    int members = consumer.routes().stream().mapToInt(route ->
                            Integer.parseInt(endpointParameters(route).get("consumersCount"))).sum();
                    broker.awaitMembers(groupId(consumer), members, consumer.topics());
                    invocationOffsets.put(groupId(consumer), broker.snapshotCommittedOffsets(groupId(consumer)));
                }
                if (controls.get("scramPassword") instanceof String password) {
                    consumer.runtime().clearConsumerFailure();
                    broker.setScramPassword(password);
                    broker.reconnect();
                }
                if (controls.get("consumerAccess") instanceof String access) {
                    consumer.runtime().clearConsumerFailure();
                    broker.applyConsumerAccess(consumer.topics(), groupId(consumer),
                            !"topic-denied".equals(access), !"group-denied".equals(access));
                    if ("topic-denied".equals(access)) {
                        broker.reconnect();
                    }
                }
                if (controls.get("tlsCertificate") instanceof String profile) {
                    consumer.runtime().clearConsumerFailure();
                    broker.rotateTlsCertificate(profile);
                }
                if (controls.get("partitionCount") instanceof Number count) {
                    broker.expandPartitions(consumer.topics(), count.intValue());
                    int members = consumer.routes().stream().mapToInt(route ->
                            Integer.parseInt(endpointParameters(route).get("consumersCount"))).sum();
                    broker.awaitMembers(groupId(consumer), members, consumer.topics());
                }
                boolean stagedBeforeRestart = false;
                Object action = controls.get("consumerAction");
                if (action != null) {
                    broker.awaitCommitted(groupId(consumer), consumer.runtime().publishedOffsets());
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
                    Map<TopicPartition, Long> offsets = broker.snapshotCommittedOffsets(groupId(consumer));
                    broker.restart();
                    assertEquals(offsets, broker.snapshotCommittedOffsets(groupId(consumer)), "Broker restart must preserve committed offsets.");
                    assertSame(original, context.getRoute(consumer.route().getId()).getConsumer(), "Broker recovery must retain the Kafka consumer.");
                } else if ("transition-with-backlog".equals(action)) {
                    context.getRouteController().stopRoute(consumer.route().getId());
                    broker.awaitMembers(groupId(consumer), 0, consumer.topics());
                    consumer.runtime().expectHistoricalReplay((List<?>) controls.getOrDefault("expectedReplayRecords", List.of()));
                    consumer.runtime().publishIgnored(records(controls, "ignoredRecords"));
                    consumer.runtime().publishStartup(records(controls, "backlogRecords"));
                    blueGreenState = parseBlueGreenState(controls.get("blueGreenState"));
                    List.copyOf(stateListeners).forEach(listener -> listener.accept(blueGreenState));
                    context.getRouteController().startRoute(consumer.route().getId());
                    broker.awaitMembers(groupId(consumer), 1, consumer.topics());
                    stagedBeforeRestart = true;
                } else if ("truncate-before-restart".equals(action)) {
                    context.getRouteController().stopRoute(consumer.route().getId());
                    consumer.runtime().publishIgnored(records(controls, "ignoredRecords"));
                    consumer.runtime().publishStartup(records(controls, "backlogRecords"));
                    broker.truncateBeforeOffsets(parseCommittedOffsets(controls.get("truncateBeforeOffsets")));
                    context.getRouteController().startRoute(consumer.route().getId());
                    if (controls.containsKey("expectedFetchOffsets")) {
                        broker.awaitFetchOffsets(parseCommittedOffsets(controls.get("expectedFetchOffsets")));
                    }
                    stagedBeforeRestart = true;
                } else if ("reset-committed-before-restart".equals(action)) {
                    context.getRouteController().stopRoute(consumer.route().getId());
                    broker.awaitMembers(groupId(consumer), 0, consumer.topics());
                    broker.commitOffsets(groupId(consumer), parseCommittedOffsets(controls.get("resetCommittedOffsets")));
                    consumer.runtime().clearConsumerFailure();
                    context.getRouteController().startRoute(consumer.route().getId());
                    int members = consumer.routes().stream().mapToInt(route ->
                            Integer.parseInt(endpointParameters(route).get("consumersCount"))).sum();
                    broker.awaitMembers(groupId(consumer), members, consumer.topics());
                } else if ("stop-peer-until-published".equals(action)) {
                    RouteDefinition peer = peerRoute(consumer);
                    context.getRouteController().stopRoute(peer.getId());
                    int remaining = Integer.parseInt(consumer.parameters().get("consumersCount"));
                    broker.awaitMembers(groupId(consumer), remaining, consumer.topics());
                } else if ("start-peer".equals(action)) {
                    RouteDefinition peer = peerRoute(consumer);
                    context.getRouteController().startRoute(peer.getId());
                    int expected = consumer.routes().stream().mapToInt(route ->
                            Integer.parseInt(endpointParameters(route).get("consumersCount"))).sum();
                    broker.awaitMembers(groupId(consumer), expected, consumer.topics());
                } else if ("stop-during-processing".equals(action) || "exceed-poll-interval".equals(action)
                        || "lose-broker-during-processing".equals(action)) {
                    KafkaTriggerSnapshotProcessingGate.Gate gate = consumer.processing()
                            .blockNext("lose-broker-during-processing".equals(action) ? 120 : 25);
                    consumer.runtime().afterPublish(metadata -> {
                        gate.awaitStarted();
                        try {
                            if ("stop-during-processing".equals(action)) {
                                stopDuringProcessing(consumer, gate);
                            } else if ("lose-broker-during-processing".equals(action)) {
                                Map<TopicPartition, Long> before = broker.snapshotCommittedOffsets(groupId(consumer));
                                broker.crashAndRestart();
                                assertEquals(before, broker.snapshotCommittedOffsets(groupId(consumer)),
                                        "Broker loss before processing completes must preserve committed offsets.");
                            } else {
                                broker.awaitMembers(groupId(consumer), 0, consumer.topics());
                            }
                        } finally {
                            gate.open();
                        }
                    });
                }
                if (!stagedBeforeRestart) {
                    consumer.runtime().publishIgnored(records(controls, "ignoredRecords"));
                    consumer.runtime().publishStartup(records(controls, "backlogRecords"));
                }
                if (controls.containsKey("filteredRecords")) {
                    broker.awaitCommitted(groupId(consumer), consumer.runtime().publishFiltered(records(controls, "filteredRecords")));
                    consumer.runtime().assertNothingDelivered();
                }
                if (Boolean.TRUE.equals(controls.get("resetConsumerFailure"))) {
                    consumer.runtime().clearConsumerFailure();
                }
            }
        }

        private static RouteDefinition peerRoute(ConsumerRuntime consumer) {
            return SnapshotRouteNodes.requireSingle(consumer.routes().stream().filter(route -> route != consumer.route()).toList(),
                    "Kafka group scenario requires one peer route");
        }

        private String groupId(ConsumerRuntime consumer) {
            return groupId(consumer.groupId());
        }

        private String groupId(String prefix) {
            return groupId(prefix, blueGreenState);
        }

        private static String groupId(String prefix, BlueGreenState state) {
            if (state.getSibling().isEmpty()) {
                return prefix;
            }
            NamespaceVersion current = state.getCurrent();
            return new VersionedGroupId(prefix, current.getVersion(), current.getState(),
                    state.getSibling().orElseThrow().getState(), state.getUpdateTime()).toString();
        }

        private void stopDuringProcessing(ConsumerRuntime consumer, KafkaTriggerSnapshotProcessingGate.Gate gate) throws Exception {
            Map<TopicPartition, Long> before = broker.snapshotCommittedOffsets(groupId(consumer));
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
                    assertEquals(before, broker.snapshotCommittedOffsets(groupId(consumer)),
                            "Kafka must not commit the record before processing finishes.");
                } finally {
                    gate.open();
                }
                stopped.get(20, TimeUnit.SECONDS);
            }
            broker.awaitCommitted(groupId(consumer), consumer.runtime().publishedOffsets());
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
                    broker.awaitCommitted(groupId(consumer), consumer.runtime().publishedOffsets());
                }
                if (controls(interaction).get("expectedMaxPollRecords") instanceof Number maximum) {
                    pollObservers.forEach(observer -> observer.verify(maximum.intValue()));
                }
                if (controls(interaction).get("expectedMembers") instanceof Number count) {
                    broker.awaitMembers(groupId(consumer), count.intValue(), consumer.topics());
                }
                if (Boolean.TRUE.equals(controls(interaction).get("expectedCommitAuthorizationFailure"))) {
                    broker.awaitCommitAuthorizationFailure(groupId(consumer), invocationCommitFailures.get(groupId(consumer)),
                            consumer.runtime().publishedOffsets());
                    assertEquals(invocationOffsets.get(groupId(consumer)), broker.snapshotCommittedOffsets(groupId(consumer)),
                            "A denied Kafka OffsetCommit must preserve the previously committed offsets.");
                }
                consumer.runtime().verifyInvocation(interaction, invocation.getId());
                if (("allow".equals(controls(interaction).get("consumerAccess"))
                        || Set.of("valid", "rotated").contains(controls(interaction).getOrDefault("tlsCertificate", ""))
                        || controls(interaction).containsKey("scramPassword")
                            && !controls(interaction).containsKey("expectedConsumerFailure"))
                        && consumer.runtime().hasConsumerFailure()) {
                    SnapshotFailureExpectation previousFailure = previousFailures.get(groupId(consumer));
                    SnapshotFailureAssertions.assertMatches(previousFailure, consumer.runtime().getConsumerFailure(),
                            "Kafka trigger access recovery before successful consumption");
                    consumer.runtime().clearConsumerFailure();
                }
                SnapshotFailureAssertions.assertMatches(expectedConsumerFailure(controls(interaction)),
                        consumer.runtime().getConsumerFailure(), "Kafka trigger invocation '" + invocation.getId() + "' consumer");
                if (controls(interaction).containsKey("expectedConsumerFailure")) {
                    previousFailures.put(groupId(consumer), expectedConsumerFailure(controls(interaction)));
                    assertEquals(invocationOffsets.get(groupId(consumer)), broker.snapshotCommittedOffsets(groupId(consumer)),
                            "Kafka consumer failure must preserve offsets from the beginning of this invocation.");
                }
                KafkaConnectionProxy.Statistics statistics = broker.authenticationStatistics();
                for (String key : List.of("minSuccessfulAuthentications", "minFailedAuthentications",
                        "minSuccessfulTlsHandshakes", "minFailedTlsHandshakes")) {
                    if (controls(interaction).get(key) instanceof Number count) {
                        long observed = switch (key) {
                            case "minSuccessfulAuthentications" -> statistics.successfulAuthentications();
                            case "minFailedAuthentications" -> statistics.failedAuthentications();
                            case "minFailedTlsHandshakes" -> statistics.failedTlsHandshakes();
                            default -> statistics.successfulTlsHandshakes();
                        };
                        assertTrue(observed >= count.longValue(), "Kafka trigger " + key + " must be at least " + count);
                    }
                }
                if (blueGreenState.getSibling().isPresent()) {
                    consumeSiblingThroughOffsets(consumer, controls(interaction).containsKey("siblingOffsets")
                            ? parseCommittedOffsets(controls(interaction).get("siblingOffsets")) : broker.endOffsets(consumer.topics()));
                }
            }
            broker.verify();
        }

        private void consumeSiblingThroughOffsets(ConsumerRuntime primary, Map<TopicPartition, Long> expected) throws Exception {
            BlueGreenState siblingState = new BlueGreenState(blueGreenState.getSibling().orElseThrow(),
                    blueGreenState.getCurrent(), blueGreenState.getUpdateTime());
            BlueGreenStatePublisher publisher = mock(BlueGreenStatePublisher.class);
            when(publisher.getBlueGreenState()).thenReturn(siblingState);
            KafkaCustomEndpoint endpoint = context.getEndpoint(primary.route().getInput().getUri(), KafkaCustomEndpoint.class);
            Properties nativeProperties = endpoint.getConfiguration().createConsumerProperties();
            endpoint.updateClassProperties(nativeProperties);
            nativeProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.consumerAddress());
            nativeProperties.put(ConsumerConfig.GROUP_ID_CONFIG, primary.groupId());
            nativeProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            Map<String, Object> properties = new LinkedHashMap<>();
            nativeProperties.forEach((key, value) -> properties.put(key.toString(), value));
            OffsetSetupStrategy strategy = "earliest".equals(primary.parameters().get("autoOffsetReset"))
                    ? OffsetSetupStrategy.EARLIEST : OffsetSetupStrategy.LATEST;
            BGKafkaConsumerConfig configuration = BGKafkaConsumerConfig.builder(properties, Set.copyOf(primary.topics()), publisher)
                    .consistencyMode(ConsumerConsistencyMode.valueOf(endpoint.getConfiguration().getConsumerConsistencyMode()))
                    .activeOffsetSetupStrategy(strategy).candidateOffsetSetupStrategy(strategy).build();
            String siblingGroup = groupId(primary.groupId(), siblingState);
            try (var sibling = new BGKafkaConsumerExtendedImpl<Object, Object>(configuration)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                do {
                    sibling.poll(Duration.ofMillis(100)).ifPresent(batch -> sibling.commitSync(batch.getCommitMarker()));
                    if (broker.hasCommitted(siblingGroup, expected)) {
                        assertEquals(expected, broker.snapshotCommittedOffsets(siblingGroup),
                                "Sibling offsets must match the declared progress, including deliberate lag.");
                        return;
                    }
                } while (System.nanoTime() < deadline);
                throw new AssertionError("Sibling Kafka group '" + siblingGroup + "' did not commit through " + expected + '.');
            }
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
                    assertEquals(invocationOffsets.get(groupId(consumer)), broker.snapshotCommittedOffsets(groupId(consumer)),
                            "Stopping the failed Kafka consumer must preserve invocation offsets.");
                }
            }
        }

        @Override
        public void verify() {
            broker.verify();
            if (maas != null) {
                maas.verifyLookups();
            }
        }

        @Override
        public void verifyStartupFailure() {
            if (maas != null) {
                maas.verifyLookups();
            }
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
                if (brokerLease != null) {
                    try {
                        broker.closeScenario();
                    } finally {
                        brokerLease.close();
                    }
                }
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
