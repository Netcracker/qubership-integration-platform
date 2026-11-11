package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import jakarta.enterprise.inject.spi.CDI;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class KafkaTriggerSnapshotMaas {
    private final Classifier classifier;
    private final String topic;
    private final boolean missing;
    private KafkaMaaSClient client;
    private String expectedAddress;

    KafkaTriggerSnapshotMaas(Map<String, Object> controls) {
        classifier = new Classifier((String) controls.get("maasClassifier"),
                Classifier.NAMESPACE, (String) controls.get("maasNamespace"));
        topic = (String) controls.get("maasTopic");
        missing = Boolean.TRUE.equals(controls.get("maasMissingTopic"));
    }

    void beforeRouteLoad(String address) {
        expectedAddress = address;
        client = CDI.current().select(KafkaMaaSClient.class).get();
        TopicAddress result = mock(TopicAddress.class);
        when(result.getTopicName()).thenReturn(topic);
        when(result.getBoostrapServers("PLAINTEXT")).thenReturn(address);
        when(result.getCredentials(anyString())).thenReturn(Optional.empty());
        when(client.getTopic(classifier)).thenReturn(missing ? Optional.empty() : Optional.of(result));
    }

    void verifyResolvedEndpoint(Map<String, String> parameters, String resolvedTopics) {
        assertEquals(expectedAddress, parameters.get("brokers"), "Kafka trigger must use the MaaS broker address.");
        assertEquals("PLAINTEXT", parameters.get("securityProtocol"), "Kafka trigger must use the MaaS protocol.");
        assertEquals(topic, resolvedTopics, "Kafka trigger must use the MaaS topic name.");
    }

    void verifyLookups() {
        verify(client, atLeastOnce()).getTopic(classifier);
        verifyNoMoreInteractions(client);
    }
}
