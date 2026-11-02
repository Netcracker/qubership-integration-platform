package org.qubership.integration.platform.engine.routes.fixture;

import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PubSubTriggerSnapshotBroker implements AutoCloseable {
    private String projectId;
    private String topicId;
    private String subscriptionId;
    private String topic;
    private String subscription;
    private PubSubEmulator emulator;
    private ManagedChannel channel;
    private PubSubSubscriberForwarder forwarder;
    private Server server;
    private PubSubConnectionProxy proxy;

    void start(String project, String topicName, String subscriptionName,
               boolean missingSubscription, boolean unavailableAtStart) throws IOException {
        projectId = project;
        topicId = topicName;
        subscriptionId = subscriptionName;
        topic = TopicName.of(projectId, topicId).toString();
        subscription = SubscriptionName.of(projectId, subscriptionId).toString();
        emulator = new PubSubEmulator();
        emulator.start();
        emulator.createTopicWithoutSubscription(topic);
        if (!missingSubscription) {
            createSubscription();
        }
        channel = ManagedChannelBuilder.forAddress(emulator.host(), emulator.port()).usePlaintext().build();
        forwarder = new PubSubSubscriberForwarder(channel);
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(forwarder.service()).build().start();
        proxy = new PubSubConnectionProxy();
        proxy.start("127.0.0.1", server.getPort());
        if (unavailableAtStart) {
            proxy.disconnect();
        }
    }

    String endpoint() {
        return "127.0.0.1:" + proxy.port();
    }

    String projectId() {
        return projectId;
    }

    String topicId() {
        return topicId;
    }

    String subscriptionId() {
        return subscriptionId;
    }

    String topic() {
        return topic;
    }

    String subscription() {
        return subscription;
    }

    PublishResponse publish(List<PubsubMessage> messages) {
        return emulator.publish(PublishRequest.newBuilder().setTopic(topic).addAllMessages(messages).build());
    }

    void createSubscription() {
        emulator.createSubscription(topic, subscription);
    }

    void deleteSubscription() {
        emulator.deleteSubscription(subscription);
    }

    void disconnect() {
        proxy.disconnect();
    }

    void restore() {
        proxy.restore();
    }

    Statistics statistics() {
        PubSubSubscriberForwarder.Statistics rpc = forwarder.statistics();
        PubSubConnectionProxy.Statistics connections = proxy.statistics();
        return new Statistics(rpc.streamingPullRequests(), rpc.acknowledgedCount(), rpc.negativeAcknowledgedCount(),
                rpc.acknowledgedMessageIds(), rpc.negativeAcknowledgedMessageIds(), rpc.activeStreams(),
                connections.acceptedConnections(), connections.rejectedConnections(), connections.activeConnections());
    }

    Timestamp publishTime(String messageId) {
        return forwarder.publishTime(messageId);
    }

    void maxDeliveriesPerMessage(int maximum) {
        forwarder.maxDeliveriesPerMessage(maximum);
    }

    @Override
    public void close() throws IOException {
        try {
            if (proxy != null) {
                proxy.close();
            }
        } finally {
            try {
                if (forwarder != null) {
                    forwarder.close();
                }
                if (server != null) {
                    server.shutdownNow();
                    awaitServer();
                }
            } finally {
                try {
                    if (channel != null) {
                        channel.shutdownNow();
                        awaitChannel();
                    }
                } finally {
                    if (emulator != null) {
                        emulator.close();
                    }
                }
            }
        }
    }

    private void awaitServer() {
        try {
            assertTrue(server.awaitTermination(10, TimeUnit.SECONDS), "Pub/Sub subscriber forwarding server did not stop.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the Pub/Sub subscriber forwarding server.", exception);
        }
    }

    private void awaitChannel() {
        try {
            assertTrue(channel.awaitTermination(10, TimeUnit.SECONDS), "Pub/Sub subscriber forwarding channel did not stop.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the Pub/Sub subscriber forwarding channel.", exception);
        }
    }

    record Statistics(long streamingPullRequests, long acknowledgedCount, long negativeAcknowledgedCount,
                      Set<String> acknowledgedMessageIds, Set<String> negativeAcknowledgedMessageIds, int activeStreams,
                      long acceptedConnections, long rejectedConnections, int activeConnections) {
    }
}
