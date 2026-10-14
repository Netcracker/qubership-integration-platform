package org.qubership.integration.platform.engine.routes.fixture;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.DeleteTopicRequest;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.TopicName;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PubSubEmulator implements AutoCloseable {
    private static final String EMULATOR_IMAGE =
            "gcr.io/google.com/cloudsdktool/google-cloud-cli:583.0.0-emulators";
    private static final int EMULATOR_PORT = 8085;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EMPTY_PULL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_PULL_MESSAGES = 100;

    private final Map<String, String> subscriptionsByTopic = new HashMap<>();
    private GenericContainer<?> container;
    private ManagedChannel channel;
    private TopicAdminClient topicClient;
    private SubscriptionAdminClient subscriptionClient;

    void start() throws IOException {
        container = new GenericContainer<>(DockerImageName.parse(EMULATOR_IMAGE))
                .withExposedPorts(EMULATOR_PORT)
                .withCommand("gcloud", "beta", "emulators", "pubsub", "start",
                        "--host-port=0.0.0.0:" + EMULATOR_PORT, "--project=sandbox")
                .waitingFor(Wait.forLogMessage(".*Server started, listening on " + EMULATOR_PORT + ".*", 1))
                .withStartupTimeout(STARTUP_TIMEOUT);
        container.start();
        channel = ManagedChannelBuilder.forAddress(container.getHost(), container.getMappedPort(EMULATOR_PORT))
                .usePlaintext()
                .build();
        FixedTransportChannelProvider transport = FixedTransportChannelProvider.create(
                GrpcTransportChannel.create(channel));
        topicClient = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                .setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
        subscriptionClient = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                .setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
    }

    void createTopic(String topic) {
        topicClient.createTopicCallable().call(Topic.newBuilder().setName(topic).build(), callContext(RPC_TIMEOUT));
        String subscriptionName = SubscriptionName.of(
                TopicName.parse(topic).getProject(), "snapshot-" + UUID.randomUUID()).toString();
        Subscription subscription = subscriptionClient.createSubscriptionCallable().call(Subscription.newBuilder()
                .setName(subscriptionName)
                .setTopic(topic)
                .setAckDeadlineSeconds(30)
                .setEnableMessageOrdering(true)
                .build(), callContext(RPC_TIMEOUT));
        assertTrue(subscription.getEnableMessageOrdering(), "The Pub/Sub subscription must enable message ordering.");
        subscriptionsByTopic.put(topic, subscriptionName);
    }

    void deleteTopic(String topic) {
        topicClient.deleteTopicCallable().call(DeleteTopicRequest.newBuilder()
                .setTopic(topic)
                .build(), callContext(RPC_TIMEOUT));
    }

    PublishResponse publish(PublishRequest request) {
        return topicClient.publishCallable().call(request, callContext(RPC_TIMEOUT));
    }

    List<PubsubMessage> receive(String topic, int expectedCount) {
        String subscription = subscriptionsByTopic.get(topic);
        List<PubsubMessage> messages = new ArrayList<>();
        Set<String> messageIds = new HashSet<>();
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (messages.size() < expectedCount && System.nanoTime() < deadline) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            Duration timeout = remaining.compareTo(RPC_TIMEOUT) < 0 ? remaining : RPC_TIMEOUT;
            collectMessages(subscription, pull(subscription, timeout), messages, messageIds);
        }
        assertEquals(expectedCount, messages.size(), "Unexpected number of delivered messages for " + topic + '.');

        // Read the remaining backlog before returning so retries cannot hide duplicate deliveries.
        collectMessages(subscription, pull(subscription, EMPTY_PULL_TIMEOUT), messages, messageIds);
        assertEquals(expectedCount, messages.size(), "Pub/Sub delivered extra messages for " + topic + '.');
        return List.copyOf(messages);
    }

    private PullResponse pull(String subscription, Duration timeout) {
        try {
            return subscriptionClient.pullCallable().call(PullRequest.newBuilder()
                    .setSubscription(subscription)
                    .setMaxMessages(MAX_PULL_MESSAGES)
                    .build(), callContext(timeout));
        } catch (ApiException exception) {
            if (exception.getStatusCode().getCode() == StatusCode.Code.DEADLINE_EXCEEDED) {
                return PullResponse.getDefaultInstance();
            }
            throw exception;
        }
    }

    private void collectMessages(
            String subscription,
            PullResponse response,
            List<PubsubMessage> messages,
            Set<String> messageIds
    ) {
        if (response.getReceivedMessagesCount() == 0) {
            return;
        }
        AcknowledgeRequest.Builder acknowledgment = AcknowledgeRequest.newBuilder().setSubscription(subscription);
        for (ReceivedMessage received : response.getReceivedMessagesList()) {
            PubsubMessage message = received.getMessage();
            assertTrue(messageIds.add(message.getMessageId()),
                    "Pub/Sub delivered message '" + message.getMessageId() + "' more than once.");
            messages.add(message);
            acknowledgment.addAckIds(received.getAckId());
        }
        subscriptionClient.acknowledgeCallable().call(acknowledgment.build(), callContext(RPC_TIMEOUT));
    }

    private static GrpcCallContext callContext(Duration timeout) {
        return GrpcCallContext.createDefault()
                .withRetryableCodes(Set.of())
                .withCallOptions(CallOptions.DEFAULT.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS));
    }

    @Override
    public void close() {
        try {
            if (subscriptionClient != null) {
                subscriptionClient.close();
            }
        } finally {
            try {
                if (topicClient != null) {
                    topicClient.close();
                }
            } finally {
                try {
                    if (channel != null) {
                        stopChannel();
                    }
                } finally {
                    if (container != null) {
                        container.stop();
                    }
                }
            }
        }
    }

    private void stopChannel() {
        channel.shutdownNow();
        try {
            assertTrue(channel.awaitTermination(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "The Pub/Sub emulator channel did not terminate.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing the Pub/Sub emulator channel.", exception);
        }
    }
}
