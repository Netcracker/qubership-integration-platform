package org.qubership.integration.platform.engine.routes.fixture;

import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;

final class KafkaSenderSnapshotBroker implements SnapshotWorkerBrokerPool.Resource {
    private final KafkaConnectionProxy proxy = new KafkaConnectionProxy();
    private final KafkaContainer container = new KafkaSnapshotContainer("apache/kafka:3.8.0") {
        @Override
        public String getBootstrapServers() {
            return "127.0.0.1:" + proxy.port();
        }
    };

    static KafkaSenderSnapshotBroker start() throws Exception {
        KafkaSenderSnapshotBroker broker = new KafkaSenderSnapshotBroker();
        try {
            broker.proxy.start();
            broker.container.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").withStartupTimeout(Duration.ofSeconds(90));
            broker.container.start();
            broker.proxy.connectTo(broker.container.getHost(), broker.container.getMappedPort(9092));
            return broker;
        } catch (Exception | Error failure) {
            try {
                broker.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    KafkaContainer container() {
        return container;
    }

    KafkaConnectionProxy proxy() {
        return proxy;
    }

    @Override
    public void resetAfterScenario() throws Exception {
        KafkaSnapshotBrokerReset.reset(container.getBootstrapServers());
        proxy.resetAfterScenario();
    }

    @Override
    public void close() throws Exception {
        try {
            proxy.close();
        } finally {
            container.close();
        }
    }
}
