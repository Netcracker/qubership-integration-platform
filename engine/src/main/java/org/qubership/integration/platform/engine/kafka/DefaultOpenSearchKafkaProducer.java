package org.qubership.integration.platform.engine.kafka;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.springframework.kafka.core.KafkaTemplate;

@Slf4j
public class DefaultOpenSearchKafkaProducer implements OpenSearchKafkaProducer {

    private final KafkaTemplate<String, SessionElementElastic> openSearchKafkaTemplate;
    private final String kafkaClientTopic;

    public DefaultOpenSearchKafkaProducer(KafkaTemplate<String, SessionElementElastic> openSearchKafkaTemplate, String kafkaClientTopic) {
        this.openSearchKafkaTemplate = openSearchKafkaTemplate;
        this.kafkaClientTopic = kafkaClientTopic;
    }

    @Override
    public void send(String key, SessionElementElastic sessionElementElastic) {
        try {
            openSearchKafkaTemplate.send(kafkaClientTopic, key, sessionElementElastic);
        } catch (Exception e) {
            log.error("Unable to send element to opensearch via kafka", e);
        }
    }
}
