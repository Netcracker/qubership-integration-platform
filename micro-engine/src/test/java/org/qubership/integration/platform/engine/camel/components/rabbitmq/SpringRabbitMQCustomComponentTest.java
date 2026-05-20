package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import org.apache.camel.Endpoint;
import org.apache.camel.component.springrabbit.MessagePropertiesConverter;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.TrustManager;

import static org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint.ARG_PREFIX;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SpringRabbitMQCustomComponentTest {

    private SpringRabbitMQCustomComponent component;

    @BeforeEach
    void setUp() {
        component = new SpringRabbitMQCustomComponent();
        component.setCamelContext(new DefaultCamelContext());

        MessagePropertiesConverter mpc = mock(MessagePropertiesConverter.class);
        component.setMessagePropertiesConverter(mpc);

        HeaderFilterStrategy hfs = mock(HeaderFilterStrategy.class);
        component.setHeaderFilterStrategy(hfs);
    }

    @Test
    void shouldCreateEndpointAndCopyComponentSettings() throws Exception {
        ConnectionFactory cf = mock(ConnectionFactory.class);
        MessageConverter converter = mock(MessageConverter.class);
        TrustManager tm = mock(TrustManager.class);
        RetryOperationsInterceptor retryInterceptor = mock(RetryOperationsInterceptor.class);

        component.setConnectionFactory(cf);
        component.setMessageConverter(converter);
        component.setTrustManager(tm);

        component.setTestConnectionOnStartup(true);
        component.setAutoStartup(true);
        component.setAutoDeclare(true);
        component.setReplyTimeout(1234);
        component.setPrefetchCount(10);
        component.setConcurrentConsumers(2);
        component.setMaxConcurrentConsumers(5);

        component.setRetry(retryInterceptor);
        component.setMaximumRetryAttempts(3);
        component.setRetryDelay(1000);

        component.setRejectAndDontRequeue(true);
        component.setAllowNullBody(true);

        Map<String, Object> params = paramsWithAddresses();
        params.put(ARG_PREFIX + "x-dead-letter-exchange", "dlx");
        params.put("prefetchCount", 42);

        Endpoint ep = component.createEndpoint("rabbitmq-custom:ex", "ex", params);

        assertNotNull(ep);
        assertInstanceOf(SpringRabbitMQCustomEndpoint.class, ep);

        SpringRabbitMQCustomEndpoint endpoint = (SpringRabbitMQCustomEndpoint) ep;

        assertNotNull(endpoint.getConnectionFactory());
        assertInstanceOf(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class, endpoint.getConnectionFactory());

        assertSame(converter, endpoint.getMessageConverter());
        assertSame(tm, endpoint.getTrustManager());

        assertTrue(endpoint.isTestConnectionOnStartup());
        assertTrue(endpoint.isAutoStartup());
        assertTrue(endpoint.isAutoDeclare());
        assertEquals(1234, endpoint.getReplyTimeout());
        assertEquals(42, endpoint.getPrefetchCount());
        assertEquals(2, endpoint.getConcurrentConsumers());
        assertEquals(5, endpoint.getMaxConcurrentConsumers());

        assertSame(retryInterceptor, endpoint.getRetry());
        assertEquals(3, endpoint.getMaximumRetryAttempts());
        assertEquals(1000, endpoint.getRetryDelay());

        assertTrue(endpoint.isRejectAndDontRequeue());
        assertTrue(endpoint.isAllowNullBody());

        assertEquals("dlx", endpoint.getArgs().get("x-dead-letter-exchange"));

        verify(component.getMessagePropertiesConverter()).setHeaderFilterStrategy(component.getHeaderFilterStrategy());
    }

    @Test
    void shouldUseTrustManagerFromParametersWhenProvided() throws Exception {
        TrustManager fromParams = mock(TrustManager.class);
        TrustManager fromComponent = mock(TrustManager.class);

        component.setTrustManager(fromComponent);

        Map<String, Object> params = paramsWithAddresses();
        params.put("trustManager", fromParams);

        Endpoint ep = component.createEndpoint("rabbitmq-custom:ex", "ex", params);
        SpringRabbitMQCustomEndpoint endpoint = (SpringRabbitMQCustomEndpoint) ep;

        assertSame(fromParams, endpoint.getTrustManager());
    }

    @Test
    void shouldFallbackToComponentTrustManagerWhenParameterNotProvided() throws Exception {
        TrustManager fromComponent = mock(TrustManager.class);
        component.setTrustManager(fromComponent);

        Endpoint ep = component.createEndpoint("rabbitmq-custom:ex", "ex", paramsWithAddresses());
        SpringRabbitMQCustomEndpoint endpoint = (SpringRabbitMQCustomEndpoint) ep;

        assertSame(fromComponent, endpoint.getTrustManager());
    }

    private Map<String, Object> paramsWithAddresses() {
        Map<String, Object> params = new HashMap<>();
        params.put("addresses", "host1:5672");
        return params;
    }
}
