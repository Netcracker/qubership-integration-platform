package org.qubership.integration.platform.engine.configuration.camel.kafkacustom;

import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "camel.component.kafka.ssl-truststore-location", value = "/tmp/truststore/defaulttruststore.jks")
@TestConfigProperty(key = "camel.component.kafka.ssl-truststore-password", value = "qwerty123")
@TestConfigProperty(key = "camel.component.kafka.ssl-truststore-type", value = "JKS")
class KafkaCustomComponentCustomizerProducerTest {

    @Inject
    KafkaCustomComponentCustomizerProducer producer;

    @Test
    void shouldCreateCustomizerAndApplyConfiguredTruststorePropertiesToKafkaCustomComponent() {
        ComponentCustomizer customizer = producer.kafkaCustomComponentCustomizer();
        KafkaCustomComponent component = new KafkaCustomComponent();

        assertNotNull(customizer);
        assertNotNull(component.getConfiguration());

        customizer.configure("kafkaCustom", component);

        KafkaCustomConfiguration configuration = component.getConfiguration();

        assertEquals("/tmp/truststore/defaulttruststore.jks", configuration.getSslTruststoreLocation());
        assertEquals("qwerty123", configuration.getSslTruststorePassword());
        assertEquals("JKS", configuration.getSslTruststoreType());
    }
}
