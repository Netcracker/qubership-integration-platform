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
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PubSubTriggerSnapshotBroker implements AutoCloseable {
    private String projectId;
    private String topicId;
    private String subscriptionId;
    private String topic;
    private String subscription;
    private PubSubEmulator emulator;
    private SnapshotWorkerBrokerPool.Lease<PubSubEmulator> emulatorLease;
    private ManagedChannel channel;
    private PubSubSubscriberForwarder forwarder;
    private Server server;
    private PubSubConnectionProxy proxy;

    void start(String project, String topicName, String subscriptionName,
               boolean missingSubscription, boolean unavailableAtStart) throws Exception {
        start(project, topicName, subscriptionName, missingSubscription, unavailableAtStart, null);
    }

    void start(String project, String topicName, String subscriptionName,
               boolean missingSubscription, boolean unavailableAtStart, PubSubOAuthServer oauth) throws Exception {
        projectId = project;
        topicId = topicName;
        subscriptionId = subscriptionName;
        topic = TopicName.of(projectId, topicId).toString();
        subscription = SubscriptionName.of(projectId, subscriptionId).toString();
        emulatorLease = SnapshotWorkerBrokerPool.acquire("pubsub-trigger:emulator", () -> {
            PubSubEmulator created = new PubSubEmulator();
            try {
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
        emulator = emulatorLease.resource();
        emulator.createTopicWithoutSubscription(topic);
        if (!missingSubscription) {
            createSubscription();
        }
        channel = ManagedChannelBuilder.forAddress(emulator.host(), emulator.port()).usePlaintext().build();
        forwarder = new PubSubSubscriberForwarder(channel);
        NettyServerBuilder builder = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(forwarder.service());
        if (oauth != null) {
            oauth.configureTls(builder).intercept(oauth.interceptor());
        }
        server = builder.build().start();
        proxy = new PubSubConnectionProxy();
        proxy.start("127.0.0.1", server.getPort());
        if (unavailableAtStart) {
            proxy.disconnect();
        }
    }

    String endpoint() {
        return "127.0.0.1:" + proxy.port();
    }

    int port() {
        return proxy.port();
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

    void expireOutstandingLeases() {
        forwarder.expireOutstandingLeases(subscription);
    }

    void rejectStreamingPulls(String code) {
        forwarder.rejectStreamingPulls(Status.Code.valueOf(code));
    }

    void allowStreamingPulls() {
        forwarder.allowStreamingPulls();
    }

    long injectedStreamingFailures() {
        return forwarder.injectedStreamingFailures();
    }

    int streamAckDeadlineSeconds() {
        return forwarder.streamAckDeadlineSeconds();
    }

    long leaseExtensions(List<String> messageIds) {
        return forwarder.leaseExtensions(messageIds);
    }

    long leaseDeadlineNanos(List<String> messageIds) {
        return forwarder.leaseDeadlineNanos(messageIds);
    }

    @Override
    public void close() throws Exception {
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
                    if (emulatorLease != null) {
                        try {
                            if (forwarder != null) {
                                assertEquals(0, forwarder.statistics().activeStreams(), "Pub/Sub forwarding streams must close before backend reuse.");
                            }
                            if (proxy != null) {
                                assertEquals(0, proxy.statistics().activeConnections(), "Pub/Sub consumer connections must close before backend reuse.");
                            }
                        } finally {
                            emulatorLease.close();
                        }
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
