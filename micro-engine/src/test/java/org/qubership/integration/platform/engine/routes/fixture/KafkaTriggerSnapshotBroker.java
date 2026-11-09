package org.qubership.integration.platform.engine.routes.fixture;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.ScramCredentialInfo;
import org.apache.kafka.clients.admin.ScramMechanism;
import org.apache.kafka.clients.admin.UserScramCredentialUpsertion;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AccessControlEntryFilter;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaTriggerSnapshotBroker implements SnapshotWorkerBrokerPool.Resource {
    private static final int INITIAL_PARTITIONS = 3;
    private final KafkaConnectionProxy proxy = new KafkaConnectionProxy();
    private final Set<String> topics = new LinkedHashSet<>();
    private final Map<String, Integer> partitionCounts = new LinkedHashMap<>();
    private TriggerContainer container;
    private Admin admin;
    private String securityProtocol = "PLAINTEXT";
    private String saslMechanism = "PLAIN";
    private KafkaTlsMaterial tls;
    private boolean reused;

    KafkaTriggerSnapshotBroker() {
    }

    KafkaTriggerSnapshotBroker(Admin admin) {
        this.admin = admin;
    }

    void configureSecurity(Map<String, String> parameters) throws Exception {
        securityProtocol = parameters.getOrDefault("securityProtocol", "PLAINTEXT");
        saslMechanism = parameters.getOrDefault("saslMechanism", "PLAIN");
        if (!Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(securityProtocol)) {
            throw new IllegalArgumentException("Kafka trigger fixture requires a supported security protocol.");
        }
        if (securityProtocol.startsWith("SASL")
                && !Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512").contains(saslMechanism)) {
            throw new IllegalArgumentException("Kafka trigger fixture requires PLAIN or SCRAM authentication.");
        }
        if ("SASL_SSL".equals(securityProtocol) || "SSL".equals(securityProtocol)) {
            tls = KafkaTlsMaterial.create();
            proxy.configureTls(tls.serverContext("valid"), tls.clientContext());
        }
    }

    void configureEndpointSecurity(Map<String, String> parameters) {
        assertEquals(securityProtocol, parameters.getOrDefault("securityProtocol", "PLAINTEXT"),
                "Kafka trigger fixtures sharing a broker must use the same security protocol.");
        if (tls != null) {
            parameters.putAll(tls.endpointOptions());
        }
    }

    void start() throws Exception {
        proxy.start();
        container = new TriggerContainer(proxy.port(), securityProtocol, saslMechanism, tls);
        container.start();
        proxy.connectTo(container.getHost(), container.getMappedPort(9092));
        beginScenario();
        if (saslMechanism.startsWith("SCRAM")) {
            updateScramPassword("reader-password");
        }
    }

    void beginScenario() {
        if (admin != null) {
            return;
        }
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, producerAddress());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        admin = Admin.create(properties);
    }

    void enableReuse() {
        reused = true;
    }

    void closeScenario() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
            admin = null;
        }
    }

    @Override
    public void resetAfterScenario() throws Exception {
        KafkaSnapshotBrokerReset.reset(producerAddress());
        proxy.resetAfterScenario();
        topics.clear();
        partitionCounts.clear();
    }

    void applyConsumerAccess(List<String> names, String groupId, boolean allowTopicRead, boolean allowGroupRead) throws Exception {
        if (securityProtocol.startsWith("SASL")) {
            setTopicRead(names, allowTopicRead);
            setGroupRead(groupId, allowGroupRead);
        }
    }

    void awaitAuthentication() {
        if (saslMechanism.startsWith("SCRAM")) {
            awaitReaderAuthentication(true);
            assertTrue(proxy.successfulAuthentications(saslMechanism) > 0,
                    "Kafka authentication must negotiate " + saslMechanism + ".");
        }
    }

    void setScramPassword(String password) throws Exception {
        updateScramPassword(password);
        awaitReaderAuthentication("reader-password".equals(password));
    }

    private void updateScramPassword(String password) throws Exception {
        ScramMechanism mechanism = "SCRAM-SHA-256".equals(saslMechanism)
                ? ScramMechanism.SCRAM_SHA_256 : ScramMechanism.SCRAM_SHA_512;
        admin.alterUserScramCredentials(List.of(new UserScramCredentialUpsertion("reader",
                new ScramCredentialInfo(mechanism, 4096), password.getBytes(StandardCharsets.UTF_8))))
                .all().get(10, TimeUnit.SECONDS);
    }

    private void awaitReaderAuthentication(boolean permitted) {
        await(() -> {
            Properties properties = tls == null ? new Properties() : tls.senderProperties();
            properties.setProperty("bootstrap.servers", consumerAddress());
            properties.setProperty("security.protocol", securityProtocol);
            properties.setProperty("sasl.mechanism", saslMechanism);
            properties.setProperty("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required "
                    + "username=\"reader\" password=\"reader-password\";");
            properties.setProperty("request.timeout.ms", "3000");
            properties.setProperty("default.api.timeout.ms", "4000");
            try (Admin client = Admin.create(properties)) {
                client.describeTopics(List.copyOf(topics)).allTopicNames().get(5, TimeUnit.SECONDS);
                return permitted;
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof SaslAuthenticationException) {
                    return !permitted;
                }
                throw exception;
            }
        }, "Kafka SCRAM credential propagation");
    }

    void setTopicRead(List<String> names, boolean allowed) throws Exception {
        for (String topic : names) {
            applyReadAcl(ResourceType.TOPIC, topic, allowed);
        }
    }

    void setGroupRead(String groupId, boolean allowed) throws Exception {
        applyReadAcl(ResourceType.GROUP, groupId, allowed);
    }

    private void applyReadAcl(ResourceType resourceType, String name, boolean allowed) throws Exception {
        ResourcePattern resource = new ResourcePattern(resourceType, name, PatternType.LITERAL);
        String principal = "User:reader";
        AclBinding describe = new AclBinding(resource,
                new AccessControlEntry(principal, "*", AclOperation.DESCRIBE, AclPermissionType.ALLOW));
        AclBinding read = new AclBinding(resource,
                new AccessControlEntry(principal, "*", AclOperation.READ, AclPermissionType.ALLOW));
        admin.createAcls(allowed ? List.of(describe, read) : List.of(describe)).all().get(10, TimeUnit.SECONDS);
        if (!allowed) {
            admin.deleteAcls(List.of(read.toFilter())).all().get(10, TimeUnit.SECONDS);
        }
        AclBindingFilter filter = new AclBindingFilter(resource.toFilter(),
                new AccessControlEntryFilter(principal, "*", AclOperation.ANY, AclPermissionType.ANY));
        await(() -> {
            Set<AclBinding> actual = new LinkedHashSet<>(admin.describeAcls(filter).values().get(5, TimeUnit.SECONDS));
            return actual.contains(describe) && actual.contains(read) == allowed;
        }, "Kafka " + resourceType + " '" + name + "' Read permission " + allowed);
    }

    Map<String, Set<TopicPartition>> groupAssignments(String groupId) throws Exception {
        ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(groupId)).all()
                .get(5, TimeUnit.SECONDS).get(groupId);
        Map<String, Set<TopicPartition>> result = new LinkedHashMap<>();
        group.members().forEach(member -> result.put(member.consumerId(), Set.copyOf(member.assignment().topicPartitions())));
        return Map.copyOf(result);
    }

    void awaitMembers(String groupId, int count) {
        awaitMembers(groupId, count, List.copyOf(topics));
    }

    void awaitMembers(String groupId, int count, List<String> subscribedTopics) {
        await(() -> {
            Map<String, Set<TopicPartition>> assigned = groupAssignments(groupId);
            Set<TopicPartition> partitions = assigned.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
            Set<TopicPartition> expected = count == 0 ? Set.of() : subscribedTopics.stream()
                    .flatMap(topic -> java.util.stream.IntStream.range(0, partitionCounts.get(topic))
                    .mapToObj(partition -> new TopicPartition(topic, partition))).collect(Collectors.toSet());
            return assigned.size() == count && partitions.equals(expected);
        }, "Kafka group '" + groupId + "' to assign every partition to " + count + " members");
    }

    String consumerAddress() {
        return container.getBootstrapServers();
    }

    String producerAddress() {
        return container.adminAddress();
    }

    void createTopics(List<String> names) throws Exception {
        List<NewTopic> requested = names.stream().filter(name -> !topics.contains(name))
                .map(name -> new NewTopic(name, INITIAL_PARTITIONS, (short) 1).configs(Map.of(
                        "message.timestamp.before.max.ms", Long.toString(Long.MAX_VALUE),
                        "message.timestamp.after.max.ms", Long.toString(Long.MAX_VALUE),
                        "retention.ms", "-1"))).toList();
        if (!requested.isEmpty()) {
            admin.createTopics(requested).all().get(15, TimeUnit.SECONDS);
            requested.forEach(topic -> {
                topics.add(topic.name());
                partitionCounts.put(topic.name(), INITIAL_PARTITIONS);
            });
            await(() -> {
                try {
                    admin.describeTopics(requested.stream().map(NewTopic::name).toList()).allTopicNames()
                            .get(5, TimeUnit.SECONDS).values()
                            .forEach(topic -> proxy.registerFetchTopic(topic.topicId(), topic.name()));
                    return true;
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof UnknownTopicOrPartitionException) {
                        return false;
                    }
                    throw exception;
                }
            }, "Kafka created topic metadata");
            if (reused) {
                KafkaSnapshotBrokerReset.assertEmptyTopics(admin, requested.stream().map(NewTopic::name).toList());
            }
        }
    }

    void expandPartitions(List<String> names, int count) throws Exception {
        Map<String, NewPartitions> requested = new LinkedHashMap<>();
        for (String name : names) {
            assertTrue(count > partitionCounts.get(name), "Kafka partition expansion must add partitions.");
            requested.put(name, NewPartitions.increaseTo(count));
        }
        admin.createPartitions(requested).all().get(15, TimeUnit.SECONDS);
        names.forEach(name -> partitionCounts.put(name, count));
        await(() -> admin.describeTopics(names).allTopicNames().get(5, TimeUnit.SECONDS).values().stream()
                .allMatch(topic -> topic.partitions().size() == count), "Kafka expanded topic metadata");
    }

    void truncateBeforeOffsets(Map<TopicPartition, Long> offsets) throws Exception {
        Map<TopicPartition, RecordsToDelete> requested = new LinkedHashMap<>();
        offsets.forEach((partition, offset) -> requested.put(partition, RecordsToDelete.beforeOffset(offset)));
        var deleted = admin.deleteRecords(requested);
        deleted.all().get(15, TimeUnit.SECONDS);
        for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
            assertEquals(entry.getValue().longValue(), deleted.lowWatermarks().get(entry.getKey()).get(5, TimeUnit.SECONDS).lowWatermark(),
                    "Kafka must delete records before the requested offset.");
        }
    }

    void awaitFetchOffsets(Map<TopicPartition, Long> expected) {
        await(() -> proxy.hasFetchedOffsets(expected), "Kafka consumer Fetch positions " + expected);
    }

    Map<TopicPartition, Long> endOffsets(List<String> names) throws Exception {
        return offsets(names, OffsetSpec.latest());
    }

    Map<TopicPartition, Long> beginningOffsets(List<String> names) throws Exception {
        return offsets(names, OffsetSpec.earliest());
    }

    private Map<TopicPartition, Long> offsets(List<String> names, OffsetSpec specification) throws Exception {
        Map<TopicPartition, OffsetSpec> requested = new LinkedHashMap<>();
        for (String topic : names) {
            for (int partition = 0; partition < partitionCounts.get(topic); partition++) {
                requested.put(new TopicPartition(topic, partition), specification);
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                throw new TimeoutException("Timed out waiting for Kafka offsets for " + names + ".");
            }
            try {
                Map<TopicPartition, Long> result = new LinkedHashMap<>();
                admin.listOffsets(requested, new ListOffsetsOptions().timeoutMs((int) remainingMillis)).all()
                        .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        .forEach((partition, offset) -> result.put(partition, offset.offset()));
                return Map.copyOf(result);
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw exception;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(100),
                        Math.max(0, deadline - System.nanoTime())));
            }
        }
    }

    void commitOffsets(String groupId, Map<TopicPartition, Long> offsets) throws Exception {
        if (!offsets.isEmpty()) {
            Map<TopicPartition, OffsetAndMetadata> requested = new LinkedHashMap<>();
            offsets.forEach((partition, offset) -> requested.put(partition, new OffsetAndMetadata(offset)));
            admin.alterConsumerGroupOffsets(groupId, requested).all().get(10, TimeUnit.SECONDS);
            awaitCommitted(groupId, offsets);
        }
    }

    void awaitReady(String groupId, Map<TopicPartition, Long> initialOffsets) {
        // BG offset alignment commits the initial positions before seeking them in the rebalance callback.
        await(() -> {
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(groupId)).all()
                    .get(5, TimeUnit.SECONDS).get(groupId);
            Set<TopicPartition> assigned = group.members().stream()
                    .flatMap(member -> member.assignment().topicPartitions().stream()).collect(Collectors.toSet());
            return assigned.equals(initialOffsets.keySet()) && offsetsMatch(groupId, initialOffsets, true);
        }, "Kafka group '" + groupId + "' assignment and initial committed offsets");
    }

    void awaitCommitted(String groupId, Map<TopicPartition, Long> expected) {
        await(() -> offsetsMatch(groupId, expected, false), "Kafka group '" + groupId + "' committed offsets " + expected);
    }

    boolean hasCommitted(String groupId, Map<TopicPartition, Long> expected) throws Exception {
        return offsetsMatch(groupId, expected, false);
    }

    void awaitOffsetFailure(String groupId, Map<TopicPartition, Long> initialOffsets, BooleanSupplier failureObserved) throws Exception {
        await(() -> !committedOffsetValues(groupId).equals(initialOffsets) || failureObserved.getAsBoolean(),
                "Kafka group '" + groupId + "' to reject invalid or missing offsets");
        assertCommittedOffsetsUnchanged(groupId, initialOffsets);
    }

    void assertCommittedOffsetsUnchanged(String groupId, Map<TopicPartition, Long> initialOffsets) throws Exception {
        String description = initialOffsets.isEmpty()
                ? "Kafka autoOffsetReset=none initialized offsets for group '" + groupId + "' without a previous commit"
                : "Kafka autoOffsetReset=none changed committed offsets for group '" + groupId + "'";
        assertEquals(initialOffsets, committedOffsetValues(groupId), description);
    }

    private Map<TopicPartition, Long> committedOffsetValues(String groupId) throws Exception {
        Map<TopicPartition, Long> values = new LinkedHashMap<>();
        committedOffsets(groupId).forEach((partition, metadata) -> values.put(partition, metadata.offset()));
        return values;
    }

    Map<TopicPartition, Long> snapshotCommittedOffsets(String groupId) throws Exception {
        return Map.copyOf(committedOffsetValues(groupId));
    }

    private boolean offsetsMatch(String groupId, Map<TopicPartition, Long> expected, boolean allowProgress) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> actual = committedOffsets(groupId);
        return expected.entrySet().stream().allMatch(entry -> actual.get(entry.getKey()) != null
                && (allowProgress ? actual.get(entry.getKey()).offset() >= entry.getValue()
                : actual.get(entry.getKey()).offset() == entry.getValue()));
    }

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets(String groupId) throws Exception {
        return admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
    }

    void disconnect() {
        long previous = proxy.statistics().connectionResets();
        proxy.disconnect();
        assertTrue(proxy.statistics().connectionResets() > previous,
                "Kafka outage must close an established consumer connection.");
    }

    void disconnectBeforeStartup() {
        proxy.disconnect();
    }

    void restart() throws Exception {
        proxy.disconnect();
        container.restartBroker();
        await(45, () -> admin.describeCluster().nodes().get(5, TimeUnit.SECONDS).size() == 1,
                "Kafka broker to restart with its existing storage and listeners");
        proxy.restore();
    }

    void crashAndRestart() throws Exception {
        proxy.disconnect();
        container.restartBroker("KILL");
        await(45, () -> admin.describeCluster().nodes().get(5, TimeUnit.SECONDS).size() == 1,
                "Kafka broker to recover after its process is killed");
        proxy.restore();
    }

    KafkaConnectionProxy.Statistics authenticationStatistics() {
        return proxy.statistics();
    }

    long commitAuthorizationFailures(String groupId) {
        return proxy.commitAuthorizationFailures(groupId);
    }

    void awaitCommitAuthorizationFailure(String groupId, long previous, Map<TopicPartition, Long> expectedOffsets) {
        await(() -> proxy.hasDeniedCommit(groupId, previous, expectedOffsets),
                "Kafka group '" + groupId + "' OffsetCommit authorization failure");
    }

    void rotateTlsCertificate(String profile) throws Exception {
        assertTrue(tls != null, "Kafka certificate rotation requires a TLS deployment.");
        proxy.rotateTlsCertificate(tls.serverContext(profile), true);
    }

    void reconnect() {
        proxy.disconnect();
        proxy.restore();
    }

    void restore() {
        proxy.restore();
    }

    void verify() {
        proxy.verify();
    }

    private static void await(Callable<Boolean> condition, String description) {
        await(25, condition, description);
    }

    private static void await(int timeoutSeconds, Callable<Boolean> condition, String description) {
        RateLimiter limiter = RateLimiterBuilder.newBuilder().withRate(10, TimeUnit.SECONDS).withConstantThroughput().build();
        try {
            Unreliables.retryUntilTrue(timeoutSeconds, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition));
        } catch (RuntimeException exception) {
            throw new AssertionError("Timed out waiting for " + description + ".", exception);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            closeScenario();
        } finally {
            try {
                proxy.close();
            } finally {
                try {
                    if (container != null) {
                        container.close();
                    }
                } finally {
                    if (tls != null) {
                        tls.close();
                    }
                }
            }
        }
    }

    private static final class TriggerContainer extends KafkaSnapshotContainer {
        private static final String PROCESS_ID = "/tmp/kafka-trigger-process.pid";
        private static final String RESTART_REQUEST = "/tmp/kafka-trigger-restart";
        private final int proxyPort;

        private TriggerContainer(int proxyPort, String securityProtocol, String mechanism, KafkaTlsMaterial tls) {
            super("apache/kafka:3.8.0");
            this.proxyPort = proxyPort;
            withExposedPorts(9092, 9095);
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
            withEnv("KAFKA_LISTENERS",
                    "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094,ADMIN://0.0.0.0:9095");
            withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                    "PLAINTEXT:" + securityProtocol + ",ADMIN:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT");
            if (securityProtocol.startsWith("SASL")) {
                KafkaSaslSupport.configure(this);
                withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "PLAINTEXT:" + securityProtocol + ",ADMIN:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT");
                if (mechanism.startsWith("SCRAM")) {
                    withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN," + mechanism);
                    withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_" + mechanism.replace("-", "___") + "_SASL_JAAS_CONFIG",
                            "org.apache.kafka.common.security.scram.ScramLoginModule required;");
                }
            }
            if (tls != null) {
                String keyStorePath = "/tmp/kafka-trigger-fixture.p12";
                withEnv("KAFKA_SSL_KEYSTORE_LOCATION", keyStorePath);
                withEnv("KAFKA_SSL_KEYSTORE_TYPE", "PKCS12");
                withEnv("KAFKA_SSL_KEYSTORE_PASSWORD", tls.keyStorePassword());
                withEnv("KAFKA_SSL_KEY_PASSWORD", tls.keyStorePassword());
                withEnv("KAFKA_SSL_CLIENT_AUTH", "none");
                withCopyFileToContainer(MountableFile.forHostPath(tls.brokerKeyStore(), 0444), keyStorePath);
            }
            withStartupTimeout(Duration.ofMinutes(2));
        }

        @Override
        public String getBootstrapServers() {
            return "127.0.0.1:" + proxyPort;
        }

        private String adminAddress() {
            return getHost() + ':' + getMappedPort(9095);
        }

        private void restartBroker() throws Exception {
            restartBroker("TERM");
        }

        private void restartBroker(String signal) throws Exception {
            // Docker reallocates randomly mapped ports on container restart, so restart Kafka in place.
            String command = "read -r previous < " + PROCESS_ID + "\n"
                    + "touch " + RESTART_REQUEST + "\n"
                    + "kill -" + signal + " \"$previous\"\n"
                    + "for ((attempt = 0; attempt < 200; attempt++)); do\n"
                    + "  read -r current < " + PROCESS_ID + "\n"
                    + "  if [ \"$current\" != \"$previous\" ]; then exit 0; fi\n"
                    + "  sleep 0.1\n"
                    + "done\nexit 1\n";
            ExecResult result = execInContainer("bash", "-c", command);
            assertEquals(0, result.getExitCode(), "Kafka broker process did not restart: " + result.getStderr());
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse info) {
            String listeners = "PLAINTEXT://" + getBootstrapServers()
                    + ",BROKER://" + info.getConfig().getHostName() + ":9093,ADMIN://" + adminAddress();
            String script = "#!/bin/bash\nexport KAFKA_ADVERTISED_LISTENERS='"
                    + listeners.replace("'", "'\"'\"'") + "'\n"
                    + "trap 'kill -TERM \"$kafka_pid\"; wait \"$kafka_pid\"; exit 0' TERM INT\n"
                    + "while true; do\n"
                    + "  /etc/kafka/docker/run &\n"
                    + "  kafka_pid=$!\n"
                    + "  printf '%s\\n' \"$kafka_pid\" > " + PROCESS_ID + "\n"
                    + "  wait \"$kafka_pid\"\n"
                    + "  result=$?\n"
                    + "  if [ ! -f " + RESTART_REQUEST + " ]; then exit \"$result\"; fi\n"
                    + "  rm " + RESTART_REQUEST + "\n"
                    + "done\n";
            copyFileToContainer(Transferable.of(script, 0755), STARTER_SCRIPT);
        }
    }
}
