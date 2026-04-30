package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import org.apache.camel.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringRabbitMQCustomEndpointTest {

    @Mock
    private Component component;

    @Mock
    private CachingConnectionFactory cachingConnectionFactory;

    @Mock
    private ConnectionFactory nonCachingConnectionFactory;

    @Test
    void testDoStop() throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = new SpringRabbitMQCustomEndpoint("rabbitmq-custom:test", component, "test");
        endpoint.setConnectionFactory(cachingConnectionFactory);

        endpoint.doStop();

        verify(cachingConnectionFactory).destroy();
    }

    @Test
    void testDoShutdown() throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = new SpringRabbitMQCustomEndpoint("rabbitmq-custom:test", component, "test");
        endpoint.setConnectionFactory(cachingConnectionFactory);

        endpoint.doShutdown();

        verify(cachingConnectionFactory).destroy();
    }

    @Test
    void testDestroyConnectionFactoryWithCachingConnectionFactory() throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = spy(new SpringRabbitMQCustomEndpoint("rabbitmq-custom:test", component, "test"));
        when(endpoint.getConnectionFactory()).thenReturn(cachingConnectionFactory);

        Method method = SpringRabbitMQCustomEndpoint.class.getDeclaredMethod("destroyConnectionFactory");
        method.setAccessible(true);

        method.invoke(endpoint);

        verify(cachingConnectionFactory).destroy();
    }

    @Test
    void testDestroyConnectionFactoryWithNonCachingConnectionFactory() throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = spy(new SpringRabbitMQCustomEndpoint("rabbitmq-custom:test", component, "test"));
        when(endpoint.getConnectionFactory()).thenReturn(nonCachingConnectionFactory);

        Method method = SpringRabbitMQCustomEndpoint.class.getDeclaredMethod("destroyConnectionFactory");
        method.setAccessible(true);

        method.invoke(endpoint);

        verifyNoInteractions(nonCachingConnectionFactory);
    }

    @Test
    void testDestroyConnectionFactoryWithException() throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = spy(new SpringRabbitMQCustomEndpoint("rabbitmq-custom:test", component, "test"));
        when(endpoint.getConnectionFactory()).thenReturn(cachingConnectionFactory);
        doThrow(new RuntimeException("Destroy failed")).when(cachingConnectionFactory).destroy();

        Method method = SpringRabbitMQCustomEndpoint.class.getDeclaredMethod("destroyConnectionFactory");
        method.setAccessible(true);

        method.invoke(endpoint);

        verify(cachingConnectionFactory).destroy();
    }
}
