package org.qubership.integration.platform.engine.camel.components.kafka.factory;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.OffsetSetupStrategy;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.Producer;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtendedImpl;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.*;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;

@Slf4j
public class DefaultKafkaBGClientFactory implements KafkaBGClientFactory {

    private final KafkaClientFactory delegate;
    private final BlueGreenStatePublisher blueGreenStatePublisher;

    public DefaultKafkaBGClientFactory(KafkaClientFactory delegate, BlueGreenStatePublisher blueGreenStatePublisher) {
        this.delegate = delegate;
        this.blueGreenStatePublisher = blueGreenStatePublisher;
    }

    @Override
    public Pair<Producer, Runnable> getProducerWithCloseCallback(Properties kafkaProps) {
        return Pair.of((Producer<?, ?>) delegate.getProducer(kafkaProps), () -> {});
    }

    // kafkaProps contains ONLY consumer props (not camel specific)
    @Override
    public BGKafkaConsumerExtended getConsumer(Properties kafkaProps,
                                               ConsumerConsistencyMode consistencyMode, List<String> topics) {
        String offsetResetMode = (String) kafkaProps.get(AUTO_OFFSET_RESET_CONFIG);
        OffsetSetupStrategy offsetSetupStrategy = OffsetSetupStrategy.LATEST;
        if (StringUtils.isNotEmpty(offsetResetMode)
                && "earliest".equalsIgnoreCase(offsetResetMode)) {
            offsetSetupStrategy = OffsetSetupStrategy.EARLIEST;
            kafkaProps.remove(AUTO_OFFSET_RESET_CONFIG);
        }

        return new BGKafkaConsumerExtendedImpl(BGKafkaConsumerConfig
            .builder(propertiesToMap(kafkaProps),
                new HashSet<>(topics),
                blueGreenStatePublisher)
            .consistencyMode(consistencyMode)
            .activeOffsetSetupStrategy(offsetSetupStrategy)
            .candidateOffsetSetupStrategy(offsetSetupStrategy)
            .build());
    }

    public String getBrokers(KafkaCustomConfiguration configuration) {
        // broker urls is mandatory in this implementation
        String brokers = configuration.getBrokers();
        if (ObjectHelper.isEmpty(brokers)) {
            throw new IllegalArgumentException("URL to the Kafka brokers must be configured with the brokers option.");
        }
        return brokers;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HashMap<String, Object> propertiesToMap(Properties prop) {
        return new HashMap<>((Map<String, Object>) ((Map) prop));
    }
}
