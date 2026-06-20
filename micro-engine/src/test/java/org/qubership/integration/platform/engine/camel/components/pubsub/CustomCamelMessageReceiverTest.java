package org.qubership.integration.platform.engine.camel.components.pubsub;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.google.pubsub.GooglePubsubEndpoint;
import org.apache.camel.support.DefaultConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomCamelMessageReceiverTest {

    private CustomCamelMessageReceiver receiver;

    @Mock
    private DefaultConsumer consumer;
    @Mock
    private GooglePubsubEndpoint endpoint;
    @Mock
    private Processor processor;
    @Mock
    private AckReplyConsumer ackReplyConsumer;

    private Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        when(endpoint.getLoggerId()).thenReturn(null);
        when(consumer.createExchange(true)).thenReturn(exchange);
        receiver = new CustomCamelMessageReceiver(consumer, endpoint, processor);
    }

    @Test
    void shouldNotProcessWhenPubsubMessageIsMissing() {
        assertThrows(NullPointerException.class, () -> receiver.receiveMessage(null, ackReplyConsumer));

        verifyNoInteractions(processor, ackReplyConsumer);
    }
}
