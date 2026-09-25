package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.ContextProvider;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextProvider;
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
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.http.HttpHeaders;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.jspecify.annotations.NonNull;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class KafkaContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "kafka-container";
    private static final String KAFKA_COMPONENT_NAME = "kafka-custom";
    private static final String KAFKA_IMAGE = "apache/kafka:3.8.0";
    private static final String KAFKA_CLIENT_FACTORY_PARAMETER = "kafkaClientFactory";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final long ADMIN_TIMEOUT_SECONDS = 15;

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
        private final Map<String, KafkaFixtureRuntime> runtimes = new LinkedHashMap<>();
        private final KafkaConnectionProxy proxy = new KafkaConnectionProxy();
        private final Set<String> liveTopics = new LinkedHashSet<>();
        private final Map<String, String> timingOverrides = new LinkedHashMap<>();
        private final Map<String, String> producerProperties = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> initializedContexts = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> restoredContexts = new ConcurrentHashMap<>();
        private List<ContextProvider<?>> originalContextProviders;
        private Map<String, Object> originalContext;
        private CamelExchangeContextPropagation contextPropagation;
        private KafkaConnectionProxy.Statistics invocationStatistics;
        private KafkaContainer container;
        private KafkaTlsMaterial tls;
        private Admin admin;
        private Properties readerProperties;
        private String security;
        private String tlsCertificate = "valid";
        private Boolean synchronous;
        private int topicPartitions = 3;

        private KafkaContainerSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            Integer configuredPartitions = null;
            for (SnapshotFixtureBinding binding : bindings) {
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
                    if (expectation == null || expectation.getDestination() == null || expectation.getMethod() != null
                            || expectation.getPath() != null || expectation.getQuery() != null) {
                        throw new IllegalArgumentException("Kafka fixtures require a destination and message expectations.");
                    }
                    ResponsePlan plan = responsePlan(interaction.getResponse());
                    if (plan.synchronous() != null) {
                        if (synchronous != null && !synchronous.equals(plan.synchronous())) {
                            throw new IllegalArgumentException("Kafka synchronous mode must remain constant within a scenario.");
                        }
                        synchronous = plan.synchronous();
                    }
                    if (plan.security() != null) {
                        if (security != null && !security.equals(plan.security())) {
                            throw new IllegalArgumentException("Kafka security must remain constant within a scenario.");
                        }
                        security = plan.security();
                    }
                    plan.timingOverrides().forEach((name, value) -> {
                        String previous = timingOverrides.putIfAbsent(name, value);
                        if (previous != null && !previous.equals(value)) {
                            throw new IllegalArgumentException("Kafka timing overrides must remain constant within a scenario.");
                        }
                    });
                    plan.producerProperties().forEach((name, value) -> {
                        String previous = producerProperties.putIfAbsent(name, value);
                        if (previous != null && !previous.equals(value)) {
                            throw new IllegalArgumentException("Kafka producer properties must remain constant within a scenario.");
                        }
                    });
                    if (plan.topicPartitions() != null) {
                        if (configuredPartitions != null && !configuredPartitions.equals(plan.topicPartitions())) {
                            throw new IllegalArgumentException("Kafka partition count must remain constant within a scenario.");
                        }
                        configuredPartitions = plan.topicPartitions();
                    }
                }
                runtimes.put(binding.definition().getId(), new KafkaFixtureRuntime(binding));
            }
            if (configuredPartitions != null) {
                topicPartitions = configuredPartitions;
            }
            if (!usesSasl() && bindings.stream()
                    .flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .anyMatch(interaction -> responsePlan(interaction.getResponse()).aclAction() != null)) {
                throw new IllegalArgumentException("Kafka ACL actions require a SASL security profile.");
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            if ("sasl-ssl".equals(security)) {
                tls = KafkaTlsMaterial.create();
                proxy.configureTls(tls.serverContext("valid"), tls.clientContext());
            }
            proxy.start();
            container = tls == null ? new KafkaSnapshotContainer(KAFKA_IMAGE) {
                @Override
                public String getBootstrapServers() {
                    return "127.0.0.1:" + proxy.port();
                }
            } : new KafkaTlsContainer(KAFKA_IMAGE, proxy.port(), tls);
            container.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").withStartupTimeout(STARTUP_TIMEOUT);
            if ("sasl-plain".equals(security)) {
                KafkaSaslSupport.configure(container);
            }
            container.start();
            proxy.connectTo(container.getHost(), container.getMappedPort(9092));
            String adminAddress = container instanceof KafkaTlsContainer tlsContainer
                    ? tlsContainer.adminBootstrapServers() : container.getBootstrapServers();
            readerProperties = usesSasl() ? KafkaSaslSupport.adminProperties(adminAddress) : new Properties();
            readerProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, adminAddress);
            Properties adminProperties = new Properties();
            adminProperties.putAll(readerProperties);
            adminProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
            adminProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
            admin = Admin.create(adminProperties);
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            originalContext = ContextManager.createContextSnapshot();
            originalContextProviders = List.copyOf(ContextManager.getContextProviders());
            if (originalContextProviders.stream()
                    .noneMatch(provider -> XRequestIdContextProvider.X_REQUEST_ID_CONTEXT_NAME.equals(provider.contextName()))) {
                // Maven disables context-provider discovery for the component suite.
                ContextManager.register(List.of(new XRequestIdContextProvider()));
            }
            contextPropagation = registerRuntimeBeans(camelContext);
            if (camelContext.getComponent(KAFKA_COMPONENT_NAME, false) == null) {
                camelContext.addComponent(KAFKA_COMPONENT_NAME, new KafkaCustomComponent(camelContext));
            }
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                KafkaProducerNode node = findProducerNode(routes, binding.definition());
                KafkaEndpointUri uri = KafkaEndpointUri.parse(node.definition().getUri(), binding.definition().getId());
                uri.validateSecurity(security);
                Map<String, String> connectionOptions = new LinkedHashMap<>(timingOverrides);
                connectionOptions.putAll(producerProperties);
                if (synchronous != null) {
                    connectionOptions.put("synchronous", synchronous.toString());
                }
                if (tls != null) {
                    connectionOptions.putAll(tls.endpointOptions());
                }
                KafkaBGClientFactory clientFactory = requireKafkaClientFactory(camelContext, uri);
                KafkaFixtureRuntime runtime = runtimes.get(binding.definition().getId());
                if (tls != null && clientFactory != null) {
                    runtime.clientFactory = new KafkaSnapshotClientFactory(clientFactory);
                    String factoryName = "snapshot-fixture:kafka:" + deploymentId + ':' + binding.definition().getId();
                    camelContext.getRegistry().bind(factoryName, runtime.clientFactory);
                    connectionOptions.put(KAFKA_CLIENT_FACTORY_PARAMETER, '#' + factoryName);
                }
                node.definition().setUri(uri.withBroker(container.getBootstrapServers(), connectionOptions));
                runtime.topics.add(uri.destination());
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    runtime.topics.add(interaction.getExpectedRequest().getDestination());
                    expectedRecords(interaction).forEach(record -> runtime.topics.add((String) record.get("topic")));
                }
                for (String topic : runtime.topics) {
                    if (!liveTopics.contains(topic)) {
                        createTopic(topic);
                    }
                }
                bindingsByRoute.computeIfAbsent(node.route(), ignored -> new ArrayList<>()).add(binding);
            }
            Processor restoreProcessor = camelContext.getRegistry().lookupByNameAndType("contextRestoreProcessor", Processor.class);
            Processor observedRestore = exchange -> {
                restoreProcessor.process(exchange);
                // Kafka resumes the route on a callback thread; capture its restored context on that same thread.
                restoredContexts.put(exchange.getExchangeId(), immutableMap(contextPropagation.getHeadersForCurrentContext()));
            };
            SnapshotFixtureRouteScope.bind(camelContext, List.copyOf(bindingsByRoute.keySet()), "kafka:" + deploymentId,
                    Map.of("contextRestoreProcessor", observedRestore));
            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    advice.weaveAddFirst().process(exchange -> initializedContexts.put(exchange.getExchangeId(),
                            initializeRequestContext(contextPropagation, exchange)));
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId()).before().process(exchange -> {
                            Object body = exchange.getMessage().getBody();
                            Object batchItems = exchange.getProperty("snapshot.kafkaBatchItems");
                            runtimes.get(binding.definition().getId()).preparedRequests.add(new PreparedRequest(
                                    immutableMap(exchange.getProperties()), exchange,
                                    batchItems instanceof List<?> items ? items.size()
                                            : body instanceof Collection<?> collection ? collection.size() : 1));
                        });
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            invocationStatistics = proxy.statistics();
            for (KafkaFixtureRuntime runtime : runtimes.values()) {
                runtime.beforeInvocation(invocation.getId());
                ResponsePlan plan = runtime.plan;
                boolean recoverTls = false;
                if (plan.tlsCertificate() != null) {
                    assertNotNull(tls, "Kafka certificate changes require the sasl-ssl security profile.");
                    recoverTls = Set.of("untrusted", "wrong-hostname", "expired").contains(tlsCertificate)
                            && Set.of("valid", "rotated").contains(plan.tlsCertificate());
                    proxy.rotateTlsCertificate(tls.serverContext(plan.tlsCertificate()));
                    tlsCertificate = plan.tlsCertificate();
                }
                if ("recover".equals(plan.connectionAction())) {
                    proxy.restore();
                }
                SnapshotFixtureInteraction interaction = runtime.binding.interaction(invocation.getId());
                if (interaction == null) {
                    continue;
                }
                String topic = interaction.getExpectedRequest().getDestination();
                if ("delete".equals(plan.topicAction())) {
                    for (KafkaFixtureRuntime owner : runtimes.values()) {
                        owner.verifyTopic(readerProperties, topic);
                    }
                    admin.deleteTopics(List.of(topic)).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    liveTopics.remove(topic);
                } else if ("recreate".equals(plan.topicAction())) {
                    createTopic(topic);
                }
                if (plan.topicMaxMessageBytes() != null) {
                    setTopicMaxMessageBytes(topic, plan.topicMaxMessageBytes());
                }
                if (plan.increaseTopicPartitionsTo() != null) {
                    increaseTopicPartitions(topic, plan.increaseTopicPartitionsTo());
                }
                if (plan.aclAction() != null) {
                    KafkaSaslSupport.applyTopicAcl(admin, topic, plan.aclPrincipal(), "allow".equals(plan.aclAction()));
                }
                if (recoverTls && runtime.clientFactory != null) {
                    runtime.clientFactory.awaitMetadata(topic, Duration.ofSeconds(ADMIN_TIMEOUT_SECONDS));
                }
                if ("disconnect".equals(plan.connectionAction()) && !plan.disconnectBeforeProduce()) {
                    proxy.disconnect();
                }
                if (plan.disconnectBeforeProduce()) {
                    proxy.disconnectBeforeNextProduce("disconnect".equals(plan.connectionAction()));
                }
                if (plan.dropProduceResponses() > 0) {
                    proxy.dropNextProduceResponses(plan.dropProduceResponses());
                }
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws InterruptedException {
            for (KafkaFixtureRuntime runtime : runtimes.values()) {
                SnapshotFixtureInteraction interaction = runtime.binding.interaction(invocation.getId());
                if (usesSasl() && interaction != null && interaction.getExpectedRequest().getCount() > 0) {
                    // A batch can fail serialization while an earlier record is still queued for sending.
                    proxy.awaitAuthenticatedProduceRequests(invocationStatistics.authenticatedProduceRequests() + 1);
                }
                if (runtime.plan.minFailedTlsHandshakes() > 0) {
                    // The client can report its TLS failure before the proxy receives the fatal alert.
                    proxy.awaitFailedTlsHandshakes(invocationStatistics.failedTlsHandshakes()
                            + runtime.plan.minFailedTlsHandshakes());
                }
                if (runtime.plan.expectedAcks() != null || runtime.plan.expectedCompressionCodec() != null) {
                    proxy.awaitProduceRequests(invocationStatistics.produceAttempts() + 1);
                }
            }
            KafkaConnectionProxy.Statistics statistics = proxy.statistics();
            for (KafkaFixtureRuntime runtime : runtimes.values()) {
                runtime.verifyInvocation(invocation, initializedContexts, restoredContexts,
                        !"false".equals(producerProperties.get("recordMetadata")));
                SnapshotFixtureInteraction interaction = runtime.binding.interaction(invocation.getId());
                if (usesSasl() && interaction != null && interaction.getExpectedRequest().getCount() > 0) {
                    assertTrue(statistics.authenticatedProduceRequests() > invocationStatistics.authenticatedProduceRequests(),
                            "Kafka did not forward the publication through an authenticated sender connection.");
                    if (tls != null) {
                        assertTrue(statistics.tlsProduceRequests() > invocationStatistics.tlsProduceRequests(),
                                "Kafka did not forward the publication through an authenticated TLS connection.");
                    }
                }
                if (runtime.plan.disconnectBeforeProduce()) {
                    assertEquals(1, statistics.disconnectedProduceRequests() - invocationStatistics.disconnectedProduceRequests(),
                            "Kafka did not encounter the planned connection interruption.");
                    if (!"disconnect".equals(runtime.plan.connectionAction())) {
                        assertTrue(statistics.produceAttempts() - invocationStatistics.produceAttempts() >= 2,
                                "Kafka did not retry the interrupted Produce request.");
                    }
                }
                if (runtime.plan.dropProduceResponses() > 0) {
                    assertEquals(runtime.plan.dropProduceResponses(),
                            statistics.droppedProduceResponses() - invocationStatistics.droppedProduceResponses(),
                            "Kafka did not lose the planned successful Produce responses.");
                }
                if (runtime.plan.expectedProduceAttempts() != null) {
                    assertEquals(runtime.plan.expectedProduceAttempts().longValue(),
                            statistics.produceAttempts() - invocationStatistics.produceAttempts(),
                            "Kafka observed an unexpected number of Produce requests.");
                }
                if (runtime.plan.expectedAcks() != null) {
                    short expectedAcks = runtime.plan.expectedAcks().shortValue();
                    long requests = statistics.produceAttempts() - invocationStatistics.produceAttempts();
                    long matchingRequests = statistics.produceRequestsByAcks().getOrDefault(expectedAcks, 0L)
                            - invocationStatistics.produceRequestsByAcks().getOrDefault(expectedAcks, 0L);
                    assertTrue(requests > 0, "Kafka did not send any Produce requests for acknowledgment verification.");
                    assertEquals(requests, matchingRequests,
                            "Kafka Produce requests used an unexpected acknowledgment setting.");
                }
                if (runtime.plan.expectedCompressionCodec() != null) {
                    String codec = runtime.plan.expectedCompressionCodec();
                    long batches = statistics.recordBatchesByCompression().values().stream().mapToLong(Long::longValue).sum()
                            - invocationStatistics.recordBatchesByCompression().values().stream().mapToLong(Long::longValue).sum();
                    long matchingBatches = statistics.recordBatchesByCompression().getOrDefault(codec, 0L)
                            - invocationStatistics.recordBatchesByCompression().getOrDefault(codec, 0L);
                    assertTrue(batches > 0, "Kafka did not send any record batches for compression verification.");
                    assertEquals(batches, matchingBatches, "Kafka record batches used an unexpected compression codec.");
                }
                if ("disconnect".equals(runtime.plan.connectionAction())) {
                    assertTrue(statistics.rejectedConnections() > invocationStatistics.rejectedConnections()
                                    || statistics.connectionResets() > invocationStatistics.connectionResets(),
                            "Kafka did not encounter the disconnected broker.");
                }
                assertTrue(statistics.successfulAuthentications() - invocationStatistics.successfulAuthentications()
                                >= runtime.plan.minSuccessfulAuthentications(),
                        "Kafka did not complete the expected successful SASL authentications.");
                assertTrue(statistics.failedAuthentications() - invocationStatistics.failedAuthentications()
                                >= runtime.plan.minFailedAuthentications(),
                        "Kafka did not observe the expected failed SASL authentications.");
                assertTrue(statistics.successfulTlsHandshakes() - invocationStatistics.successfulTlsHandshakes()
                                >= runtime.plan.minSuccessfulTlsHandshakes(),
                        "Kafka did not complete the expected TLS handshakes.");
                assertTrue(statistics.failedTlsHandshakes() - invocationStatistics.failedTlsHandshakes()
                                >= runtime.plan.minFailedTlsHandshakes(),
                        "Kafka did not observe the expected TLS handshake failures.");
            }
            proxy.verify();
        }

        @Override
        public void verify() {
            // Restore only after all scenario calls so verification can inspect a final failed publication.
            proxy.restore();
            proxy.verify();
            runtimes.values().forEach(runtime -> runtime.verify(readerProperties, liveTopics));
        }

        @Override
        public void close() throws Exception {
            try {
                if (admin != null) {
                    admin.close(Duration.ofSeconds(5));
                }
            } finally {
                try {
                    if (container != null) {
                        container.stop();
                    }
                } finally {
                    try {
                        try {
                            proxy.close();
                        } finally {
                            if (tls != null) {
                                tls.close();
                            }
                        }
                    } finally {
                        if (originalContextProviders != null) {
                            ContextManager.clearAll();
                            ContextManager.reinitialize();
                            ContextManager.register(originalContextProviders);
                            ContextManager.activateContextSnapshot(originalContext);
                        }
                    }
                }
            }
        }

        private boolean usesSasl() {
            return "sasl-plain".equals(security) || "sasl-ssl".equals(security);
        }

        private void createTopic(String topic) throws Exception {
            admin.createTopics(List.of(new NewTopic(topic, topicPartitions, (short) 1)))
                    .all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (usesSasl()) {
                KafkaSaslSupport.applyTopicAcl(admin, topic, "writer", true);
                KafkaSaslSupport.applyTopicAcl(admin, topic, "reader", false);
            }
            liveTopics.add(topic);
        }

        private void setTopicMaxMessageBytes(String topic, int maxMessageBytes) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ADMIN_TIMEOUT_SECONDS);
            String action = "setting max.message.bytes for topic " + topic;
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            String configuredValue = Integer.toString(maxMessageBytes);
            AlterConfigOp operation = new AlterConfigOp(
                    new ConfigEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, configuredValue), AlterConfigOp.OpType.SET);
            admin.incrementalAlterConfigs(Map.of(resource, List.of(operation))).all()
                    .get(remainingAdminNanos(deadline, action), TimeUnit.NANOSECONDS);
            while (true) {
                ConfigEntry observed = admin.describeConfigs(List.of(resource)).all()
                        .get(remainingAdminNanos(deadline, action), TimeUnit.NANOSECONDS)
                        .get(resource).get(TopicConfig.MAX_MESSAGE_BYTES_CONFIG);
                if (configuredValue.equals(observed.value())) {
                    return;
                }
            }
        }

        private void increaseTopicPartitions(String topic, int partitions) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ADMIN_TIMEOUT_SECONDS);
            String action = "increasing partitions for topic " + topic + " to " + partitions;
            admin.createPartitions(Map.of(topic, NewPartitions.increaseTo(partitions))).all()
                    .get(remainingAdminNanos(deadline, action), TimeUnit.NANOSECONDS);
            while (true) {
                int observed = admin.describeTopics(List.of(topic)).allTopicNames()
                        .get(remainingAdminNanos(deadline, action), TimeUnit.NANOSECONDS)
                        .get(topic).partitions().size();
                if (observed == partitions) {
                    return;
                }
            }
        }

        private long remainingAdminNanos(long deadline, String action) throws TimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Kafka timed out while " + action + '.');
            }
            return remaining;
        }
    }

    private static final class KafkaFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private final Set<String> topics = new LinkedHashSet<>();
        private final List<Map<String, Object>> expected = new ArrayList<>();
        private final List<ConsumerRecord<byte[], byte[]>> retiredRecords = new ArrayList<>();
        private final List<Exchange> metadataExchanges = new ArrayList<>();
        private ResponsePlan plan = responsePlan(null);
        private KafkaSnapshotClientFactory clientFactory;
        private int preparedBaseline;
        private int metadataCount;

        private KafkaFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void beforeInvocation(String invocationId) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocationId);
            plan = responsePlan(interaction == null ? null : interaction.getResponse());
            preparedBaseline = preparedRequests.size();
        }

        private void verifyInvocation(
                SnapshotScenarioInvocation invocation,
                Map<String, Map<String, Object>> initializedContexts,
                Map<String, Map<String, Object>> restoredContexts,
                boolean recordMetadataEnabled
        ) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
            List<PreparedRequest> prepared = preparedRequests.subList(preparedBaseline, preparedRequests.size());
            int expectedSendCount = plan.expectedSendCount() == null ? invocation.getRepeat() : plan.expectedSendCount();
            assertEquals(interaction == null ? 0 : expectedSendCount, prepared.size(),
                    "Kafka prepared an unexpected number of logical sends.");
            if (interaction == null) {
                return;
            }
            expected.addAll(expectedRecords(interaction));
            Object incomingRequestId = new CaseInsensitiveMap<>(invocation.getHeaders()).get("X-Request-Id");
            for (PreparedRequest request : prepared) {
                String exchangeId = request.exchange().getExchangeId();
                Map<String, Object> initializedContext = initializedContexts.get(exchangeId);
                assertNotNull(initializedContext, "Kafka did not initialize request context for " + invocation.getId());
                Object initializedRequestId = new CaseInsensitiveMap<>(initializedContext).get("X-Request-Id");
                if (incomingRequestId == null) {
                    assertTrue(initializedRequestId instanceof String id && !id.isBlank(),
                            "Kafka did not generate a request ID for " + invocation.getId());
                    assertTrue(initializedContexts.entrySet().stream()
                                    .filter(entry -> !entry.getKey().equals(exchangeId))
                                    .noneMatch(entry -> initializedRequestId.equals(
                                            new CaseInsensitiveMap<>(entry.getValue()).get("X-Request-Id"))),
                            "Kafka reused another exchange's request ID for " + invocation.getId());
                } else {
                    assertEquals(incomingRequestId, initializedRequestId,
                            "Kafka did not initialize the incoming request ID for " + invocation.getId());
                }
                Map<String, Object> restoredContext = restoredContexts.get(request.exchange().getExchangeId());
                assertNotNull(restoredContext, "Kafka did not execute the context restore processor for " + invocation.getId());
                assertEquals(initializedRequestId, new CaseInsensitiveMap<>(restoredContext).get("X-Request-Id"),
                        "Kafka did not restore the incoming request context for " + invocation.getId());
                SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getProperties(), request.properties(),
                        "Kafka prepared an unexpected exchange property");
                if (!recordMetadataEnabled) {
                    KafkaSnapshotRecords.assertNoMetadata(request.exchange());
                } else if (plan.verifyMetadata()) {
                    metadataExchanges.add(request.exchange());
                    if (!invocation.isExpectedFailure()) {
                        metadataCount += request.recordCount();
                    }
                }
            }
        }

        private void verifyTopic(Properties readerProperties, String topic) {
            if (!topics.contains(topic)) {
                return;
            }
            List<Map<String, Object>> topicExpected = expected.stream().filter(record -> topic.equals(record.get("topic"))).toList();
            List<ConsumerRecord<byte[], byte[]>> records = KafkaSnapshotRecords.read(readerProperties, Set.of(topic), topicExpected.size());
            KafkaSnapshotRecords.assertRecords(topicExpected, records, "Kafka topic " + topic);
            retiredRecords.addAll(records);
            expected.removeIf(record -> topic.equals(record.get("topic")));
        }

        private void verify(Properties readerProperties, Set<String> liveTopics) {
            Set<String> readableTopics = new LinkedHashSet<>(topics);
            readableTopics.retainAll(liveTopics);
            List<ConsumerRecord<byte[], byte[]>> records = KafkaSnapshotRecords.read(readerProperties, readableTopics, expected.size());
            KafkaSnapshotRecords.assertRecords(expected, records, "Kafka fixture " + binding.definition().getId());
            List<ConsumerRecord<byte[], byte[]>> allRecords = new ArrayList<>(retiredRecords);
            allRecords.addAll(records);
            KafkaSnapshotRecords.assertMetadata(metadataExchanges, metadataCount, allRecords);
        }
    }

    private static List<Map<String, Object>> expectedRecords(SnapshotFixtureInteraction interaction) {
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        Object configured = responseProperties(interaction.getResponse()).get("expectedRecords");
        List<?> records = configured == null ? Collections.nCopies(expectation.getCount(), Map.of())
                : requireType(configured, List.class, "expectedRecords");
        assertEquals(expectation.getCount(), records.size(), "Kafka expectedRecords must match expectedRequest.count.");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : records) {
            Map<?, ?> fields = requireType(value, Map.class, "expectedRecords entry");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("topic", expectation.getDestination());
            if (expectation.getKey() != null) {
                record.put("key", expectation.getKey());
            }
            if (expectation.hasBody()) {
                record.put("body", expectation.getBody());
            }
            record.put("headers", expectation.getHeaders());
            result.add(KafkaSnapshotRecords.mergeExpectation(record, fields));
        }
        return result;
    }

    private static Map<String, Object> responseProperties(SnapshotFixtureResponse response) {
        if (response == null) {
            return Map.of();
        }
        if (response.hasExplicitStatus() || response.getDelayMillis() != null || response.getBody() != null
                || !response.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("Kafka fixture response supports only properties.");
        }
        Map<String, Object> properties = response.getProperties();
        if (!Set.of("topicAction", "connectionAction", "disconnectBeforeProduce", "dropProduceResponses",
                "timingOverrides", "topicPartitions", "verifyMetadata", "expectedRecords", "security", "aclAction", "aclPrincipal",
                "minSuccessfulAuthentications", "minFailedAuthentications", "expectedSendCount", "tlsCertificate",
                "minSuccessfulTlsHandshakes", "minFailedTlsHandshakes", "topicMaxMessageBytes", "increaseTopicPartitionsTo",
                "expectedProduceAttempts", "synchronous", "producerProperties", "expectedAcks", "expectedCompressionCodec")
                .containsAll(properties.keySet())) {
            throw new IllegalArgumentException("Kafka fixture response contains unsupported properties.");
        }
        return properties;
    }

    private static ResponsePlan responsePlan(SnapshotFixtureResponse response) {
        Map<String, Object> properties = responseProperties(response);
        String topicAction = option(properties, "topicAction", String.class);
        String connectionAction = option(properties, "connectionAction", String.class);
        String security = option(properties, "security", String.class);
        String aclAction = option(properties, "aclAction", String.class);
        String aclPrincipal = option(properties, "aclPrincipal", String.class);
        String tlsCertificate = option(properties, "tlsCertificate", String.class);
        if (security != null && !Set.of("plaintext", "sasl-plain", "sasl-ssl").contains(security)) {
            throw new IllegalArgumentException("Kafka security supports plaintext, sasl-plain, and sasl-ssl.");
        }
        if (tlsCertificate != null && !Set.of("valid", "rotated", "untrusted", "expired", "wrong-hostname").contains(tlsCertificate)) {
            throw new IllegalArgumentException("Kafka tlsCertificate must name a supported test certificate.");
        }
        if (aclAction != null && !Set.of("allow", "deny").contains(aclAction)) {
            throw new IllegalArgumentException("Kafka aclAction supports allow and deny.");
        }
        if (aclPrincipal != null && aclPrincipal.isBlank()) {
            throw new IllegalArgumentException("Kafka aclPrincipal must be a nonblank username.");
        }
        if (topicAction != null && !Set.of("delete", "recreate").contains(topicAction)) {
            throw new IllegalArgumentException("Kafka topicAction supports delete and recreate.");
        }
        if (connectionAction != null && !Set.of("disconnect", "recover").contains(connectionAction)) {
            throw new IllegalArgumentException("Kafka connectionAction supports disconnect and recover.");
        }
        Integer dropCount = option(properties, "dropProduceResponses", Integer.class);
        Integer partitions = option(properties, "topicPartitions", Integer.class);
        Integer maxMessageBytes = option(properties, "topicMaxMessageBytes", Integer.class);
        Integer increasePartitionsTo = option(properties, "increaseTopicPartitionsTo", Integer.class);
        Integer expectedProduceAttempts = option(properties, "expectedProduceAttempts", Integer.class);
        Integer expectedSendCount = option(properties, "expectedSendCount", Integer.class);
        Integer expectedAcks = option(properties, "expectedAcks", Integer.class);
        if (expectedAcks != null && !Set.of(-1, 0, 1).contains(expectedAcks)) {
            throw new IllegalArgumentException("Kafka expectedAcks must be -1, 0, or 1.");
        }
        String expectedCompressionCodec = option(properties, "expectedCompressionCodec", String.class);
        if (expectedCompressionCodec != null && !Set.of("none", "gzip", "snappy", "lz4", "zstd").contains(expectedCompressionCodec)) {
            throw new IllegalArgumentException("Kafka expectedCompressionCodec must be none, gzip, snappy, lz4, or zstd.");
        }
        if (expectedSendCount != null && expectedSendCount < 0) {
            throw new IllegalArgumentException("Kafka expectedSendCount must be nonnegative.");
        }
        if (expectedProduceAttempts != null && expectedProduceAttempts < 0) {
            throw new IllegalArgumentException("Kafka expectedProduceAttempts must be nonnegative.");
        }
        if (dropCount != null && dropCount < 1 || partitions != null && partitions < 1) {
            throw new IllegalArgumentException("Kafka response counts must be positive.");
        }
        if (maxMessageBytes != null && maxMessageBytes < 1) {
            throw new IllegalArgumentException("Kafka topicMaxMessageBytes must be positive.");
        }
        if (increasePartitionsTo != null && increasePartitionsTo < 1) {
            throw new IllegalArgumentException("Kafka increaseTopicPartitionsTo must be positive.");
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        Object configuredOverrides = properties.get("timingOverrides");
        if (configuredOverrides != null) {
            Map<?, ?> options = requireType(configuredOverrides, Map.class, "timingOverrides");
            options.forEach((name, value) -> {
                if (!Set.of("maxBlockMs", "requestTimeoutMs", "deliveryTimeoutMs", "retryBackoffMs").contains(name)
                        || !(value instanceof Number number) || number.longValue() <= 0) {
                    throw new IllegalArgumentException("Kafka timingOverrides requires supported timeout names and positive milliseconds.");
                }
                overrides.put((String) name, value.toString());
            });
        }
        return new ResponsePlan(topicAction, connectionAction,
                Boolean.TRUE.equals(option(properties, "disconnectBeforeProduce", Boolean.class)),
                dropCount == null ? 0 : dropCount, overrides, partitions, maxMessageBytes, increasePartitionsTo,
                Boolean.TRUE.equals(option(properties, "verifyMetadata", Boolean.class)), security, aclAction,
                aclPrincipal == null ? "writer" : aclPrincipal,
                minimumCount(properties, "minSuccessfulAuthentications"), minimumCount(properties, "minFailedAuthentications"),
                expectedSendCount, expectedProduceAttempts, tlsCertificate,
                minimumCount(properties, "minSuccessfulTlsHandshakes"), minimumCount(properties, "minFailedTlsHandshakes"),
                option(properties, "synchronous", Boolean.class), producerProperties(properties), expectedAcks, expectedCompressionCodec);
    }

    private static Map<String, String> producerProperties(Map<String, Object> properties) {
        Object configured = properties.get("producerProperties");
        if (configured == null) {
            return Map.of();
        }
        Map<?, ?> options = requireType(configured, Map.class, "producerProperties");
        Map<String, String> result = new LinkedHashMap<>();
        options.forEach((name, value) -> {
            if (Set.of("enableIdempotence", "recordMetadata").contains(name)) {
                result.put((String) name, requireType(value, Boolean.class, (String) name).toString());
            } else if ("requestRequiredAcks".equals(name)) {
                String acks = requireType(value, String.class, "requestRequiredAcks");
                if (!Set.of("all", "-1", "0", "1").contains(acks)) {
                    throw new IllegalArgumentException("Kafka requestRequiredAcks must be all, -1, 0, or 1.");
                }
                result.put((String) name, acks);
            } else if ("compressionCodec".equals(name)) {
                String codec = requireType(value, String.class, "compressionCodec");
                if (!Set.of("none", "gzip", "snappy", "lz4", "zstd").contains(codec)) {
                    throw new IllegalArgumentException("Kafka compressionCodec must be none, gzip, snappy, lz4, or zstd.");
                }
                result.put((String) name, codec);
            } else {
                throw new IllegalArgumentException("Kafka producerProperties contains an unsupported property: " + name);
            }
        });
        return Map.copyOf(result);
    }

    private static int minimumCount(Map<String, Object> properties, String name) {
        Integer value = option(properties, name, Integer.class);
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Kafka " + name + " must be nonnegative.");
        }
        return value == null ? 0 : value;
    }

    private static <T> T option(Map<String, Object> options, String name, Class<T> type) {
        Object value = options.get(name);
        return value == null ? null : requireType(value, type, name);
    }

    private static <T> T requireType(Object value, Class<T> type, String name) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Kafka " + name + " must be a " + type.getSimpleName() + '.');
        }
        return type.cast(value);
    }

    private record ResponsePlan(
            String topicAction,
            String connectionAction,
            boolean disconnectBeforeProduce,
            int dropProduceResponses,
            Map<String, String> timingOverrides,
            Integer topicPartitions,
            Integer topicMaxMessageBytes,
            Integer increaseTopicPartitionsTo,
            boolean verifyMetadata,
            String security,
            String aclAction,
            String aclPrincipal,
            int minSuccessfulAuthentications,
            int minFailedAuthentications,
            Integer expectedSendCount,
            Integer expectedProduceAttempts,
            String tlsCertificate,
            int minSuccessfulTlsHandshakes,
            int minFailedTlsHandshakes,
            Boolean synchronous,
            Map<String, String> producerProperties,
            Integer expectedAcks,
            String expectedCompressionCodec
    ) {
    }

    private static KafkaBGClientFactory requireKafkaClientFactory(CamelContext camelContext, KafkaEndpointUri endpointUri) {
        Registry registry = camelContext.getRegistry();
        String reference = endpointUri.parameters().get(KAFKA_CLIENT_FACTORY_PARAMETER);
        if (reference == null || !reference.startsWith("#")) {
            return null;
        }

        String beanName = reference.substring(1);
        KafkaBGClientFactory clientFactory = registry.lookupByNameAndType(beanName, KafkaBGClientFactory.class);
        if (clientFactory == null) {
            throw new IllegalStateException("Micro-engine snapshot is missing Kafka client factory '" + beanName + "'.");
        }
        return clientFactory;
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

    private static Map<String, Object> initializeRequestContext(
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
        return immutableMap(contextPropagation.getHeadersForCurrentContext());
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

    private record KafkaProducerNode(
            RouteDefinition route,
            ToDynamicDefinition definition
    ) {
    }

    private record PreparedRequest(Map<String, Object> properties, Exchange exchange, int recordCount) {
    }

    private record KafkaEndpointUri(
            String endpoint,
            String destination,
            Map<String, String> parameters
    ) {
        private void validateSecurity(String profile) {
            if ("sasl-plain".equals(profile) || "sasl-ssl".equals(profile)) {
                assertEquals("sasl-ssl".equals(profile) ? "SASL_SSL" : "SASL_PLAINTEXT",
                        parameters.get("securityProtocol"), "Kafka sender security protocol");
                if ("sasl-ssl".equals(profile)) {
                    assertEquals("https", parameters.get("sslEndpointAlgorithm"), "Kafka sender hostname verification");
                }
                assertEquals("PLAIN", parameters.get("saslMechanism"), "Kafka sender SASL mechanism");
                String jaas = parameters.get("saslJaasConfig");
                assertTrue(jaas != null && jaas.contains("org.apache.kafka.common.security.plain.PlainLoginModule"),
                        "Kafka sender must provide its own PLAIN JAAS configuration.");
            } else {
                assertEquals("PLAINTEXT", parameters.getOrDefault("securityProtocol", "PLAINTEXT"),
                        "Kafka sender security protocol does not match the fixture profile.");
            }
        }

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

        private String withBroker(String bootstrapServers, Map<String, String> timingOverrides) {
            Map<String, String> containerParameters = new LinkedHashMap<>(parameters);
            putParameter(containerParameters, "brokers", stripProtocol(bootstrapServers));
            timingOverrides.forEach((name, value) -> putParameter(containerParameters, name, value));
            StringBuilder uri = new StringBuilder(endpoint);
            containerParameters.forEach((name, value) -> uri
                    .append(uri.indexOf("?") < 0 ? '?' : '&')
                    .append(name).append('=').append(value));
            return uri.toString();
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
