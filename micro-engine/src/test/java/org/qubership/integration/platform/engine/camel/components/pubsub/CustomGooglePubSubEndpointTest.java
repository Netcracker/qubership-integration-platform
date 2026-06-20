package org.qubership.integration.platform.engine.camel.components.pubsub;

import org.apache.camel.Consumer;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.PubSubTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubEndpointTest {

    @Test
    void shouldCreateCustomConsumerAndConfigureEndpointWhenCreateConsumerCalled() throws Exception {
        CustomGooglePubSubEndpoint endpoint = PubSubTestUtils.newEndpoint();
        Processor processor = mock(Processor.class);

        Consumer consumer = endpoint.createConsumer(processor);

        assertInstanceOf(CustomGooglePubSubConsumer.class, consumer);
        CustomGooglePubSubConsumer customConsumer = (CustomGooglePubSubConsumer) consumer;
        assertSame(endpoint, customConsumer.getEndpoint());
        assertSame(processor, customConsumer.getProcessor());
        assertEquals(ExchangePattern.InOnly, endpoint.getExchangePattern());
        assertNotNull(endpoint.getHeaderFilterStrategy());
    }
}
