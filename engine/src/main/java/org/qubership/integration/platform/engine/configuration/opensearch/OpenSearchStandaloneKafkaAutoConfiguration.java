package org.qubership.integration.platform.engine.configuration.opensearch;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.qubership.integration.platform.engine.kafka.DefaultOpenSearchKafkaProducer;
import org.qubership.integration.platform.engine.kafka.OpenSearchKafkaProducer;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@AutoConfiguration
@ConditionalOnProperty(prefix = "qip", name = {"opensearch.kafka-client.enabled", "standalone"}, havingValue = "true")
public class OpenSearchStandaloneKafkaAutoConfiguration {

    private final String kafkaClientBootstrapServers;
    private final String kafkaClientTopic;

    @Autowired
    public OpenSearchStandaloneKafkaAutoConfiguration(
            @Value("${qip.opensearch.kafka-client.bootstrap-servers:}") String kafkaClientBootstrapServers,
            @Value("${qip.opensearch.kafka-client.topic:}") String kafkaClientTopic
    ) {
        this.kafkaClientBootstrapServers = kafkaClientBootstrapServers;
        this.kafkaClientTopic = kafkaClientTopic;
    }

    @Bean
    public OpenSearchKafkaProducer openSearchKafkaProducer() {
        return new DefaultOpenSearchKafkaProducer(
                new KafkaTemplate<>(openSearchProducerFactory()),
                kafkaClientTopic
        );
    }

    private ProducerFactory<String, KafkaQueueElement> openSearchProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaClientBootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(configProps);
    }
}
