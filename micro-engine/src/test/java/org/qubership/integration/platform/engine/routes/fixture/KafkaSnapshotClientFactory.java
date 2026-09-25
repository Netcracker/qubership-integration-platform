package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

final class KafkaSnapshotClientFactory implements KafkaBGClientFactory {
    private final KafkaBGClientFactory delegate;
    private final List<Producer<?, ?>> activeProducers = new CopyOnWriteArrayList<>();

    KafkaSnapshotClientFactory(KafkaBGClientFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Pair<Producer, Runnable> getProducerWithCloseCallback(Properties kafkaProps) {
        Pair<Producer, Runnable> client = delegate.getProducerWithCloseCallback(kafkaProps);
        Producer<?, ?> producer = client.getLeft();
        activeProducers.add(producer);
        return Pair.of(producer, () -> {
            activeProducers.remove(producer);
            client.getRight().run();
        });
    }

    @Override
    public BGKafkaConsumerExtended getConsumer(
            Properties kafkaProps, ConsumerConsistencyMode consistencyMode, List<String> topics) {
        return delegate.getConsumer(kafkaProps, consistencyMode, topics);
    }

    @Override
    public String getBrokers(KafkaCustomConfiguration configuration) {
        return delegate.getBrokers(configuration);
    }

    void awaitMetadata(String topic, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Producer<?, ?> producer : activeProducers) {
            while (true) {
                try {
                    // Reading metadata consumes TLS errors cached before the fixture restored the certificate.
                    producer.partitionsFor(topic);
                    break;
                } catch (SslAuthenticationException error) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("Kafka producer did not recover TLS for topic '" + topic + "'.", error);
                    }
                }
            }
        }
    }
}
