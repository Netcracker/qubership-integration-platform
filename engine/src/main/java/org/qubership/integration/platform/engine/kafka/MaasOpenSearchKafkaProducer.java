package org.qubership.integration.platform.engine.kafka;

import com.netcracker.maas.declarative.kafka.client.api.MaasKafkaProducer;
import com.netcracker.maas.declarative.kafka.client.api.model.MaasProducerRecord;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;

import java.util.Date;

@Slf4j
public class MaasOpenSearchKafkaProducer implements OpenSearchKafkaProducer {

    private final MaasKafkaProducer maasKafkaProducer;

    public MaasOpenSearchKafkaProducer(MaasKafkaProducer maasKafkaProducer) {
        this.maasKafkaProducer = maasKafkaProducer;
    }

    @Override
    public void send(String key, KafkaQueueElement kafkaQueueElement) {
        try {
            MaasProducerRecord<String, KafkaQueueElement> record = new MaasProducerRecord<>(
                    null,
                    key,
                    kafkaQueueElement,
                    new Date().getTime(),
                    null
            );
            maasKafkaProducer.sendAsync(record);
        } catch (Exception e) {
            log.error("Unable to send element to opensearch via kafka", e);
        }
    }
}
