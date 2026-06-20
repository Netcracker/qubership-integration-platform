package org.qubership.integration.platform.engine.kafka;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaasOpensearchKafkaProducerTest {

    private static final String CLASSIFIER_NAME = "opensearch-classifier";
    private static final String CLASSIFIER_NAMESPACE = "opensearch-namespace";
    private static final String DEFAULT_TENANT = "default-tenant";
    private static final String TOPIC_NAME = "opensearch-topic";
    private static final String KEY = "session-id";

    @Mock
    private TenantConfiguration tenantConfiguration;

    @Mock
    private KafkaMaaSClient kafkaClient;

    @Mock
    private TopicAddress topicAddress;

    @Mock
    private KafkaQueueElement kafkaQueueElement;

    private MaasOpensearchKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new MaasOpensearchKafkaProducer();
        producer.name = CLASSIFIER_NAME;
        producer.namespace = CLASSIFIER_NAMESPACE;
        producer.isTenant = false;
        producer.tenantConfiguration = tenantConfiguration;
        producer.kafkaClient = kafkaClient;
    }

    @Test
    void shouldInitializeKafkaProducerWithNonTenantClassifier() {
        Map<String, Object> connectionProperties = Map.of("bootstrap.servers", "kafka:9092");
        List<Map<String, String>> classifierOptions = new ArrayList<>();

        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(
            Classifier.class,
            (mock, context) -> classifierOptions.add(castClassifierOptions(context.arguments().get(0)))
        );
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            when(kafkaClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
            when(topicAddress.formatConnectionProperties()).thenReturn(Optional.of(connectionProperties));

            producer.init();

            assertEquals(1, classifierConstruction.constructed().size());
            assertEquals(1, kafkaProducerConstruction.constructed().size());

            Map<String, String> options = classifierOptions.get(0);
            assertEquals(CLASSIFIER_NAME, options.get(Classifier.NAME));
            assertEquals(CLASSIFIER_NAMESPACE, options.get(Classifier.NAMESPACE));
            assertFalse(options.containsKey(Classifier.TENANT_ID));

            verify(kafkaClient).getTopic(classifierConstruction.constructed().get(0));
            verify(topicAddress).formatConnectionProperties();
        }
    }

    @Test
    void shouldInitializeKafkaProducerWithTenantClassifier() {
        Map<String, Object> connectionProperties = Map.of("bootstrap.servers", "kafka:9092");
        List<Map<String, String>> classifierOptions = new ArrayList<>();

        producer.isTenant = true;

        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(
            Classifier.class,
            (mock, context) -> classifierOptions.add(castClassifierOptions(context.arguments().get(0)))
        );
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            when(tenantConfiguration.getDefaultTenant()).thenReturn(DEFAULT_TENANT);
            when(kafkaClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
            when(topicAddress.formatConnectionProperties()).thenReturn(Optional.of(connectionProperties));

            producer.init();

            assertEquals(1, classifierConstruction.constructed().size());
            assertEquals(1, kafkaProducerConstruction.constructed().size());

            Map<String, String> options = classifierOptions.get(0);
            assertEquals(CLASSIFIER_NAME, options.get(Classifier.NAME));
            assertEquals(CLASSIFIER_NAMESPACE, options.get(Classifier.NAMESPACE));
            assertEquals(DEFAULT_TENANT, options.get(Classifier.TENANT_ID));

            verify(kafkaClient).getTopic(classifierConstruction.constructed().get(0));
            verify(topicAddress).formatConnectionProperties();
        }
    }

    @Test
    void shouldThrowRuntimeExceptionWhenKafkaTopicIsMissing() {
        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(Classifier.class);
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            when(kafkaClient.getTopic(any(Classifier.class))).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> producer.init());

            assertEquals("Failed to get Kafka topic", exception.getMessage());
            assertEquals(1, classifierConstruction.constructed().size());
            assertEquals(0, kafkaProducerConstruction.constructed().size());
        }
    }

    @Test
    void shouldThrowRuntimeExceptionWhenKafkaConnectionPropertiesAreMissing() {
        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(Classifier.class);
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            when(kafkaClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
            when(topicAddress.formatConnectionProperties()).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> producer.init());

            assertEquals("Failed to get connection Kafka properties", exception.getMessage());
            assertEquals(1, classifierConstruction.constructed().size());
            assertEquals(0, kafkaProducerConstruction.constructed().size());
        }
    }

    @Test
    void shouldSendKafkaQueueElementToResolvedTopic() {
        Map<String, Object> connectionProperties = Map.of("bootstrap.servers", "kafka:9092");

        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(Classifier.class);
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            stubInitializedProducerForSend(connectionProperties);

            producer.init();
            producer.send(KEY, kafkaQueueElement);

            KafkaProducer<String, KafkaQueueElement> kafkaProducer = constructedKafkaProducer(kafkaProducerConstruction);
            ArgumentCaptor<ProducerRecord<String, KafkaQueueElement>> recordCaptor = producerRecordCaptor();

            verify(kafkaProducer).send(recordCaptor.capture());

            ProducerRecord<String, KafkaQueueElement> record = recordCaptor.getValue();
            assertEquals(TOPIC_NAME, record.topic());
            assertEquals(KEY, record.key());
            assertSame(kafkaQueueElement, record.value());
            assertEquals(1, classifierConstruction.constructed().size());
        }
    }

    @Test
    void shouldNotPropagateExceptionWhenKafkaProducerSendFails() {
        Map<String, Object> connectionProperties = Map.of("bootstrap.servers", "kafka:9092");

        try (MockedConstruction<Classifier> classifierConstruction = mockConstruction(Classifier.class);
             MockedConstruction<KafkaProducer> kafkaProducerConstruction = mockConstruction(KafkaProducer.class)) {
            stubInitializedProducerForSend(connectionProperties);

            producer.init();

            KafkaProducer<String, KafkaQueueElement> kafkaProducer = constructedKafkaProducer(kafkaProducerConstruction);
            when(kafkaProducer.send(any())).thenThrow(new RuntimeException("Kafka send failed"));

            assertDoesNotThrow(() -> producer.send(KEY, kafkaQueueElement));

            verify(kafkaProducer).send(any());
            assertEquals(1, classifierConstruction.constructed().size());
        }
    }

    private void stubInitializedProducerForSend(Map<String, Object> connectionProperties) {
        when(kafkaClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
        when(topicAddress.formatConnectionProperties()).thenReturn(Optional.of(connectionProperties));
        when(topicAddress.getTopicName()).thenReturn(TOPIC_NAME);
    }

    @SuppressWarnings("unchecked")
    private static KafkaProducer<String, KafkaQueueElement> constructedKafkaProducer(
        MockedConstruction<KafkaProducer> kafkaProducerConstruction
    ) {
        return (KafkaProducer<String, KafkaQueueElement>) kafkaProducerConstruction.constructed().get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castClassifierOptions(Object options) {
        return (Map<String, String>) options;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ProducerRecord<String, KafkaQueueElement>> producerRecordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }
}
