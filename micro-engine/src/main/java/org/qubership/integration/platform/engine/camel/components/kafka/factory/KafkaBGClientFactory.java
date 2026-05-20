package org.qubership.integration.platform.engine.camel.components.kafka.factory;

import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.Producer;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.List;
import java.util.Properties;

/**
 * Based on {@link KafkaClientFactory}
 */
public interface KafkaBGClientFactory {

    Pair<Producer, Runnable> getProducerWithCloseCallback(Properties kafkaProps);

    BGKafkaConsumerExtended getConsumer(Properties kafkaProps,
                                        ConsumerConsistencyMode consistencyMode, List<String> topics);

    String getBrokers(KafkaCustomConfiguration configuration);
}
