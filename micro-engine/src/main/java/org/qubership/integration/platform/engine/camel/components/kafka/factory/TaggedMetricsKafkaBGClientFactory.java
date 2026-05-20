package org.qubership.integration.platform.engine.camel.components.kafka.factory;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class TaggedMetricsKafkaBGClientFactory extends DefaultKafkaBGClientFactory {
    private final MeterRegistry meterRegistry;
    private final Collection<Tag> tags;

    public TaggedMetricsKafkaBGClientFactory(
            KafkaClientFactory delegate,
            MeterRegistry meterRegistry,
            Collection<Tag> tags,
            BlueGreenStatePublisher deploymentVersionTracker
    ) {
        super(delegate, deploymentVersionTracker);
        this.meterRegistry = meterRegistry;
        this.tags = tags;
    }

    @Override
    public Pair<Producer, Runnable> getProducerWithCloseCallback(Properties kafkaProps) {
        Producer<?, ?> producer = super.getProducerWithCloseCallback(kafkaProps).getLeft();
        KafkaClientMetrics kafkaClientMetrics = new KafkaClientMetrics(producer, tags);
        kafkaClientMetrics.bindTo(meterRegistry);
        return Pair.of(producer, kafkaClientMetrics::close);
    }

    @Override
    public BGKafkaConsumerExtended getConsumer(Properties kafkaProps,
                                               ConsumerConsistencyMode consistencyMode, List<String> topics) {
        BGKafkaConsumerExtended consumer = super.getConsumer(kafkaProps, consistencyMode, topics);

        // provide metrics
        final KafkaClientMetrics kafkaClientMetrics = new KafkaClientMetrics(
            new MockConsumer<>(OffsetResetStrategy.NONE) {
                @Override
                public Map<MetricName, ? extends Metric> metrics() {
                    return consumer.metrics();
                }
            }, tags);
        kafkaClientMetrics.bindTo(meterRegistry);
        consumer.setOnCloseCallback(kafkaClientMetrics::close);
        return consumer;
    }

    public String getBrokers(KafkaCustomConfiguration configuration) {
        return super.getBrokers(configuration);
    }
}
