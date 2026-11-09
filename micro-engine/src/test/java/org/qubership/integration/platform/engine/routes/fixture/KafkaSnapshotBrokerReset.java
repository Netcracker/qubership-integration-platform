package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KafkaSnapshotBrokerReset {
    private static final int TIMEOUT_SECONDS = 15;

    private KafkaSnapshotBrokerReset() {
    }

    static void reset(String address) throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, address);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);
        try (Admin admin = Admin.create(properties)) {
            List<String> groups = admin.listConsumerGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .map(group -> group.groupId()).toList();
            if (!groups.isEmpty()) {
                await(() -> admin.describeConsumerGroups(groups).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).values()
                        .stream().allMatch(group -> group.members().isEmpty()), "Kafka consumer groups to become empty");
                admin.deleteConsumerGroups(groups).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            await(() -> admin.listConsumerGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                    "Kafka consumer groups to be deleted");
            // This broker belongs to one worker and has served only the scenario being released.
            var topics = admin.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!topics.isEmpty()) {
                admin.deleteTopics(topics).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            await(() -> admin.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                    "Kafka scenario topics to be deleted");
        }
    }

    static void assertEmptyTopics(Admin admin, List<String> names) throws Exception {
        Map<TopicPartition, OffsetSpec> requested = new LinkedHashMap<>();
        await(() -> {
            try {
                admin.describeTopics(names).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).values()
                        .forEach(topic -> topic.partitions().forEach(partition -> requested.put(
                                new TopicPartition(topic.name(), partition.partition()), OffsetSpec.latest())));
                return true;
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof UnknownTopicOrPartitionException) {
                    return false;
                }
                throw exception;
            }
        }, "Kafka recreated topic metadata");
        var offsets = admin.listOffsets(requested).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        offsets.forEach((partition, offset) -> assertEquals(0, offset.offset(),
                "Kafka reused broker must start with an empty partition: " + partition));
    }

    private static void await(Callable<Boolean> condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!condition.call()) {
            if (System.nanoTime() >= deadline) {
                throw new TimeoutException("Timed out waiting for " + description + '.');
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
    }
}
