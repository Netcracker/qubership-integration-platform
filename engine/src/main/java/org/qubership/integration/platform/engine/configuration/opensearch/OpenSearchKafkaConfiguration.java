package org.qubership.integration.platform.engine.configuration.opensearch;


import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "qip.opensearch.kafka-client", name = {"enabled", "standalone"}, havingValue = "true")
public class OpenSearchKafkaConfiguration {

    @Value("${qip.opensearch.kafka-client.bootstrap-servers:}")
    private String kafkaClientBootstrapServers;

    private ProducerFactory<String, KafkaQueueElement> openSearchProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaClientBootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean("openSearchKafkaTemplate")
    public KafkaTemplate<String, KafkaQueueElement> openSearchKafkaTemplate() {
        return new KafkaTemplate<>(openSearchProducerFactory());
    }
}
