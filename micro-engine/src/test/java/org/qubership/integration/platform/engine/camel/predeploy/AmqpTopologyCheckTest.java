package org.qubership.integration.platform.engine.camel.predeploy;

import com.rabbitmq.client.Channel;
import org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class AmqpTopologyCheckTest {

    @Mock
    private SpringRabbitMQEndpoint endpoint;

    @Mock
    private ConnectionFactory connectionFactory;

    @Mock
    private Connection connection;

    @Mock
    private Channel channel;

    private final AmqpTopologyCheck check = new AmqpTopologyCheck(true);

    @Test
    void shouldApplyToRabbitmqCustomSchemeOnlyWhenEnabled() {
        assertTrue(check.applicableTo("rabbitmq-custom"));
        assertFalse(check.applicableTo("kafka-custom"));
        assertFalse(new AmqpTopologyCheck(false).applicableTo("rabbitmq-custom"));
    }

    @Test
    void shouldDeclareEveryConsumedQueuePassivelyAsWritten() throws Exception {
        givenOpenChannel();
        when(endpoint.getQueues()).thenReturn("orders, payments");

        check.check(endpoint, true);

        verify(channel).queueDeclarePassive("orders");
        verify(channel).queueDeclarePassive(" payments");
        verify(channel, never()).exchangeDeclarePassive(anyString());
    }

    @Test
    void shouldFailNamingQueueBrokerDoesNotCarry() throws Exception {
        givenOpenChannel();
        when(endpoint.getQueues()).thenReturn("orders,missing");
        // Lenient because the check also declares "orders", which the broker carries.
        lenient().when(channel.queueDeclarePassive("missing")).thenThrow(new IOException("NOT_FOUND"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> check.check(endpoint, true));

        assertEquals("AMQP queue 'missing' not found. Declare the queue or change the element.", failure.getMessage());
    }

    @Test
    void shouldFailNamingExchangeBrokerDoesNotCarry() throws Exception {
        givenOpenChannel();
        when(endpoint.getExchangeName()).thenReturn("missing");
        when(channel.exchangeDeclarePassive("missing")).thenThrow(new IOException("NOT_FOUND"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check.check(endpoint, false));

        assertEquals("AMQP exchange 'missing' not found. Declare the exchange or change the element.",
                failure.getMessage());
        verify(channel, never()).queueDeclarePassive(anyString());
    }

    @Test
    void shouldNotDeclareDefaultExchange() throws Exception {
        givenOpenChannel();
        when(endpoint.getExchangeName()).thenReturn("default");

        assertDoesNotThrow(() -> check.check(endpoint, false));

        verify(channel, never()).exchangeDeclarePassive(anyString());
    }

    private void givenOpenChannel() {
        when(endpoint.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
    }
}
