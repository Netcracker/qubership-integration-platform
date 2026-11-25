package org.qubership.integration.platform.engine.camel.components.kafka.producer;

import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.util.KeyValueHolder;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyValueHolderCustomIteratorTest {

    @Test
    void shouldUseLegacyKeyWhenBothKeyHeadersDiffer() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setHeader("kafka.KEY", "legacy-key");
        exchange.getMessage().setHeader(KafkaConstants.KEY, "current-key");
        exchange.getMessage().setBody("payload");

        KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
        configuration.setLegacyHeaderNames(List.of("kafka.KEY"));
        KeyValueHolderCustomIterator iterator = new KeyValueHolderCustomIterator(
            List.<Object>of(exchange.getMessage()).iterator(), exchange, configuration, "endpoint-topic", List.of());

        KeyValueHolder<Object, ProducerRecord<Object, Object>> record = iterator.next();

        assertEquals("legacy-key", record.getValue().key());
    }
}
