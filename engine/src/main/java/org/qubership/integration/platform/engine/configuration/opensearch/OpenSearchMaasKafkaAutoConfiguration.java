package org.qubership.integration.platform.engine.configuration.opensearch;

import com.netcracker.maas.declarative.kafka.client.api.MaasKafkaClient;
import com.netcracker.maas.declarative.kafka.client.api.MaasKafkaClientFactory;
import com.netcracker.maas.declarative.kafka.client.api.MaasKafkaProducer;
import com.netcracker.maas.declarative.kafka.client.api.model.MaasKafkaProducerCreationRequest;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.configuration.opensearch.condition.OpenSearchMaasKafkaClientCondition;
import org.qubership.integration.platform.engine.kafka.MaasOpenSearchKafkaProducer;
import org.qubership.integration.platform.engine.kafka.OpenSearchKafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.function.Function;

@Slf4j
@AutoConfiguration
@Conditional(OpenSearchMaasKafkaClientCondition.class)
public class OpenSearchMaasKafkaAutoConfiguration {

    @Bean
    public OpenSearchKafkaProducer openSearchKafkaProducer(
            MaasKafkaClientFactory maasKafkaClientFactory,
            @Value("${qip.opensearch.kafka-client.maas-producer-name:opensearch-kafka-producer}") String producerName
    ) {
        MaasKafkaProducerCreationRequest producerCreationRequest = MaasKafkaProducerCreationRequest.builder()
                        .setProducerDefinition(maasKafkaClientFactory.getProducerDefinition(producerName))
                        .setHandler(Function.identity())
                        .build();
        MaasKafkaProducer maasKafkaProducer = maasKafkaClientFactory.createProducer(producerCreationRequest);
        initAndActivateAsync(maasKafkaProducer);
        return new MaasOpenSearchKafkaProducer(maasKafkaProducer);
    }

    private void initAndActivateAsync(MaasKafkaClient client) {
        client.initAsync().handle((v, e) -> {
            if (e != null) {
                log.error("Failed to init OpenSearch kafka producer");
            }
            client.activateAsync().handle((vv, ee) -> {
                if (ee != null) {
                    log.error("OpenSearch kafka producer not started", ee);
                }
                return null;
            });
            return null;
        });
    }

}
