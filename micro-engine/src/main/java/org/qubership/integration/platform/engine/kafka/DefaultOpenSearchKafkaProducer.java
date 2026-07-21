package org.qubership.integration.platform.engine.kafka;

import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;

@Slf4j
@ApplicationScoped
@DefaultBean
@IfBuildProperty(name = "qip.opensearch.kafka-client.enabled", stringValue = "true")
@IfBuildProperty(name = "qip.opensearch.kafka-client.maas.enabled", stringValue = "false")
public class DefaultOpenSearchKafkaProducer implements OpenSearchKafkaProducer {
    @Inject
    @Channel("sessions")
    Emitter<Record<String, KafkaQueueElement>> emitter;

    @Override
    public void send(String key, KafkaQueueElement kafkaQueueElement) {
        try {
            emitter.send(Record.of(key, kafkaQueueElement));
        } catch (Exception e) {
            log.error("Unable to send element to OpenSearch via Kafka", e);
        }
    }
}
