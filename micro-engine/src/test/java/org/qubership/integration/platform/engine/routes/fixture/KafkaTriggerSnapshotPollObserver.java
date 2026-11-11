package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.Producer;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaTriggerSnapshotPollObserver implements KafkaBGClientFactory {
    private final KafkaBGClientFactory delegate;
    private final List<Integer> sizes = new CopyOnWriteArrayList<>();

    KafkaTriggerSnapshotPollObserver(KafkaBGClientFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Pair<Producer, Runnable> getProducerWithCloseCallback(Properties properties) {
        return delegate.getProducerWithCloseCallback(properties);
    }

    @Override
    public BGKafkaConsumerExtended getConsumer(Properties properties, ConsumerConsistencyMode mode, List<String> topics) {
        BGKafkaConsumerExtended consumer = delegate.getConsumer(properties, mode, topics);
        return (BGKafkaConsumerExtended) Proxy.newProxyInstance(BGKafkaConsumerExtended.class.getClassLoader(),
                new Class<?>[] {BGKafkaConsumerExtended.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(consumer, arguments);
                        if ("poll".equals(method.getName()) && result instanceof Optional<?> batch) {
                            sizes.add(batch.map(value -> ((RecordsBatch<?, ?>) value).getBatch().size()).orElse(0));
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Override
    public String getBrokers(KafkaCustomConfiguration configuration) {
        return delegate.getBrokers(configuration);
    }

    void verify(int maximum) {
        assertTrue(sizes.stream().anyMatch(size -> size == maximum),
                "Kafka must return at least one full poll batch to exercise its configured limit.");
        assertTrue(sizes.stream().allMatch(size -> size <= maximum),
                () -> "Kafka exceeded maxPollRecords=" + maximum + " in observed batches " + sizes);
    }
}
