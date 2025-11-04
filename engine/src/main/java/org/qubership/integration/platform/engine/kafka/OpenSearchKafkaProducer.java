package org.qubership.integration.platform.engine.kafka;

import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;

public interface OpenSearchKafkaProducer {

    void send(String key, KafkaQueueElement value);
}
