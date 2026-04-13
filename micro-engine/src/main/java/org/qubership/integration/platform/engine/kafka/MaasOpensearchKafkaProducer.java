package org.qubership.integration.platform.engine.kafka;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;

import java.util.HashMap;
import java.util.Map;

import static org.reflections.Reflections.log;

@ApplicationScoped
@IfBuildProfile("dbaas")
@LookupIfProperty(name = "qip.opensearch.kafka-client.maas.enabled", stringValue = "true")
@LookupIfProperty(name = "qip.opensearch.kafka-client.enabled", stringValue = "true")
public class MaasOpensearchKafkaProducer implements OpenSearchKafkaProducer {
    @ConfigProperty(name = "qip.opensearch.kafka-client.maas.classifier.name")
    String name;

    @ConfigProperty(name = "qip.opensearch.kafka-client.maas.classifier.namespace")
    String namespace;

    @ConfigProperty(name = "qip.opensearch.kafka-client.maas.classifier.is-tenant")
    Boolean isTenant;

    @Inject
    TenantConfiguration tenantConfiguration;

    @Inject
    KafkaMaaSClient kafkaClient;

    private TopicAddress topicAddress;
    private KafkaProducer<String, KafkaQueueElement> producer;

    @PostConstruct
    public void init() {
        Classifier classifier = buildClassifier();
        topicAddress = kafkaClient.getTopic(classifier)
                .orElseThrow(() -> new RuntimeException("Failed to get Kafka topic"));
        producer = new KafkaProducer<>(
                topicAddress.formatConnectionProperties()
                        .orElseThrow(() -> new RuntimeException("Failed to get connection Kafka properties"))
        );
    }

    @Override
    public void send(String key, KafkaQueueElement kafkaQueueElement) {
        try {
            producer.send(new ProducerRecord<>(topicAddress.getTopicName(), key, kafkaQueueElement));
        } catch (Exception e) {
            log.error("Unable to send element to OpenSearch via Kafka", e);
        }
    }

    private Classifier buildClassifier() {
        Map<String, String> options = new HashMap<>();
        options.put(Classifier.NAME, name);
        options.put(Classifier.NAMESPACE, namespace);
        if (isTenant) {
            options.put(Classifier.TENANT_ID, tenantConfiguration.getDefaultTenant());
        }
        return new Classifier(options);
    }
}
