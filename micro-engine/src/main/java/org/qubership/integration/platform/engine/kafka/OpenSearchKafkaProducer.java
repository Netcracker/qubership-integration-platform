package org.qubership.integration.platform.engine.kafka;

import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;

public interface OpenSearchKafkaProducer {

    void send(String key, SessionElementElastic sessionElementElastic);
}
