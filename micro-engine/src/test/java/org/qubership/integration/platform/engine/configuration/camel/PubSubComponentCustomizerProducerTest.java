package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PubSubComponentCustomizerProducerTest {

    private final PubSubComponentCustomizerProducer producer = new PubSubComponentCustomizerProducer();

    @Test
    void shouldCreateCustomizerAndApplyPubSubEmulatorEndpoint() {
        ComponentCustomizer customizer = producer.servletCustomComponentCustomizer("localhost:8085");
        GooglePubsubComponent component = new GooglePubsubComponent();

        assertNotNull(customizer);

        customizer.configure("google-pubsub", component);

        assertEquals("localhost:8085", component.getEndpoint());
    }
}
