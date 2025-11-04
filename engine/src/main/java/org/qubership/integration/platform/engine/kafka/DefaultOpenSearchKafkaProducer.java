package org.qubership.integration.platform.engine.kafka;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
public class DefaultOpenSearchKafkaProducer implements OpenSearchKafkaProducer {

    private final KafkaTemplate<String, KafkaQueueElement> openSearchKafkaTemplate;
    private final String kafkaClientTopic;

    public DefaultOpenSearchKafkaProducer(KafkaTemplate<String, KafkaQueueElement> openSearchKafkaTemplate, String kafkaClientTopic) {
        this.openSearchKafkaTemplate = openSearchKafkaTemplate;
        this.kafkaClientTopic = kafkaClientTopic;
    }

    @Override
    public void send(String key, KafkaQueueElement kafkaQueueElement) {
        try {
            openSearchKafkaTemplate.send(kafkaClientTopic, key, kafkaQueueElement);
        } catch (Exception e) {
            log.error("Unable to send element to opensearch via kafka", e);
        }
    }
}
