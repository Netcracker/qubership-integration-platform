package org.qubership.integration.platform.engine.configuration.camel.kafkacustom;

import org.apache.camel.component.kafka.springboot.KafkaComponentConfiguration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaCustomComponentConfigurationTest {

    @Test
    void shouldCopyDefaultLegacyHeaderNamesToComponentConfiguration() {
        KafkaCustomConfiguration configuration = configure(List.of("kafka.KEY", "kafka.TOPIC", "kafka.HEADERS"));

        assertEquals(List.of("kafka.KEY", "kafka.TOPIC", "kafka.HEADERS"), configuration.getLegacyHeaderNames());
    }

    @Test
    void shouldCopyConfiguredLegacyHeaderNamesToComponentConfiguration() {
        KafkaCustomConfiguration configuration = configure(List.of("kafka.KEY"));

        assertEquals(List.of("kafka.KEY"), configuration.getLegacyHeaderNames());
    }

    private KafkaCustomConfiguration configure(List<String> legacyHeaderNames) {
        KafkaCustomComponentConfiguration componentConfiguration = new KafkaCustomComponentConfiguration(
            new KafkaComponentConfiguration(), legacyHeaderNames);
        KafkaCustomComponent component = new KafkaCustomComponent();

        componentConfiguration.kafkaCustomComponentCustomizer().configure("kafka-custom", component);

        return component.getConfiguration();
    }
}
