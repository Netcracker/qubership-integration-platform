package org.qubership.integration.platform.engine.kafka;

import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;

public class KafkaQueueElementSerializer extends ObjectMapperSerializer<KafkaQueueElement> {
}
