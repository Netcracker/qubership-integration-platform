package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import com.rabbitmq.client.MetricsCollector;
import org.apache.camel.Endpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.TrustManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SpringRabbitMQCustomEndpointTest {

    private SpringRabbitMQCustomEndpoint endpoint;

    @BeforeEach
    void setUp() {
        DefaultCamelContext ctx = new DefaultCamelContext();
        DummyComponent component = new DummyComponent();
        component.setCamelContext(ctx);

        endpoint = new SpringRabbitMQCustomEndpoint(
                "rabbitmq-custom:ex",
                component,
                "ex"
        );

        endpoint.setAddresses("host1:5672,host2:5673");
    }

    @Test
    void shouldBuildAndSetCachingConnectionFactoryWhenConfigureProperties() {
        endpoint.configureProperties(new HashMap<>());

        ConnectionFactory cf = endpoint.getConnectionFactory();
        assertNotNull(cf);
        assertInstanceOf(CachingConnectionFactory.class, cf);
    }

    @Test
    void shouldConfigureUnderlyingRabbitFactoryFromEndpointSettings() throws Exception {
        endpoint.setUsername("u");
        endpoint.setPassword("p");
        endpoint.setVhost("vh");

        endpoint.setConnectionTimeout(111);
        endpoint.setRequestedChannelMax(222);
        endpoint.setRequestedFrameMax(333);
        endpoint.setRequestedHeartbeat(444);

        Map<String, Object> clientProps = Map.of("a", "b");
        endpoint.setClientProperties(clientProps);

        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        endpoint.setMetricsCollector(metricsCollector);

        endpoint.configureProperties(new HashMap<>());

        CachingConnectionFactory ccf = (CachingConnectionFactory) endpoint.getConnectionFactory();
        com.rabbitmq.client.ConnectionFactory rabbit = rabbitFactory(ccf);

        assertEquals("host1", rabbit.getHost());
        assertEquals(5672, rabbit.getPort());

        assertEquals("u", rabbit.getUsername());
        assertEquals("p", rabbit.getPassword());
        assertEquals("vh", rabbit.getVirtualHost());

        assertEquals(111, rabbit.getConnectionTimeout());
        assertEquals(222, rabbit.getRequestedChannelMax());
        assertEquals(333, rabbit.getRequestedFrameMax());
        assertEquals(444, rabbit.getRequestedHeartbeat());

        assertEquals(clientProps, rabbit.getClientProperties());

        assertSame(metricsCollector, rabbit.getMetricsCollector());

        assertFalse(rabbit.isAutomaticRecoveryEnabled());
    }

    @Test
    void shouldEnableSslWhenSslProtocolTrue() {
        endpoint.setSslProtocol("true");

        assertDoesNotThrow(() -> endpoint.configureProperties(new HashMap<>()));
    }

    @Test
    void shouldEnableSslWithProtocolAndTrustManagerWhenProvided() {
        endpoint.setSslProtocol("TLS");
        endpoint.setTrustManager(mock(TrustManager.class));

        assertDoesNotThrow(() -> endpoint.configureProperties(new HashMap<>()));
    }

    @Test
    void shouldThrowIllegalArgumentWhenSslProtocolInvalid() {
        endpoint.setSslProtocol("NO_SUCH_PROTOCOL");

        assertThrows(IllegalArgumentException.class, () -> endpoint.configureProperties(new HashMap<>()));
    }

    private static com.rabbitmq.client.ConnectionFactory rabbitFactory(CachingConnectionFactory ccf) {
        try {
            Method m = ccf.getClass().getMethod("getRabbitConnectionFactory");
            Object out = m.invoke(ccf);
            if (out instanceof com.rabbitmq.client.ConnectionFactory rabbit) {
                return rabbit;
            }
            throw new IllegalStateException("getRabbitConnectionFactory() returned " + (out == null ? "null" : out.getClass()));
        } catch (NoSuchMethodException e) {
            // fall through to reflective scan
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke getRabbitConnectionFactory()", e);
        }

        for (Method m : ccf.getClass().getMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (!com.rabbitmq.client.ConnectionFactory.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                Object out = m.invoke(ccf);
                if (out instanceof com.rabbitmq.client.ConnectionFactory rabbit) {
                    return rabbit;
                }
            } catch (ReflectiveOperationException e) {
                // ignore this candidate, try others
            }
        }

        throw new IllegalStateException(
                "Cannot access underlying com.rabbitmq.client.ConnectionFactory from " + ccf.getClass().getName()
        );
    }

    private static final class DummyComponent extends DefaultComponent {
        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
            return null;
        }
    }
}
