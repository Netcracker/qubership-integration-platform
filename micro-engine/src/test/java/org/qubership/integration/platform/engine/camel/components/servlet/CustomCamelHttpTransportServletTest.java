package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.component.servlet.ServletEndpoint;
import org.apache.camel.http.common.HttpCommonEndpoint;
import org.apache.camel.http.common.HttpConsumer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomCamelHttpTransportServletTest {

    @Test
    void shouldAddConsumerWhenServletNamesMatch() {
        TestableCustomCamelHttpTransportServlet servlet = new TestableCustomCamelHttpTransportServlet("test-servlet");

        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletEndpoint endpoint = mock(ServletEndpoint.class);

        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getServletName()).thenReturn("test-servlet");
        when(endpoint.getEndpointUri()).thenReturn("servlet-custom:/orders");

        servlet.connect(consumer);

        assertEquals(1, servlet.getConsumers().size());
        assertSame(consumer, servlet.getConsumers().get("servlet-custom:/orders"));
    }

    @Test
    void shouldNotAddConsumerWhenServletNamesDoNotMatch() {
        TestableCustomCamelHttpTransportServlet servlet = new TestableCustomCamelHttpTransportServlet("test-servlet");

        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletEndpoint endpoint = mock(ServletEndpoint.class);

        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getServletName()).thenReturn("another-servlet");

        servlet.connect(consumer);

        assertTrue(servlet.getConsumers().isEmpty());
    }

    @Test
    void shouldRemoveConsumerWhenDisconnectCalled() {
        TestableCustomCamelHttpTransportServlet servlet = new TestableCustomCamelHttpTransportServlet("test-servlet");

        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletEndpoint endpoint = mock(ServletEndpoint.class);

        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getServletName()).thenReturn("test-servlet");
        when(endpoint.getEndpointUri()).thenReturn("servlet-custom:/orders");

        servlet.connect(consumer);
        servlet.disconnect(consumer);

        assertTrue(servlet.getConsumers().isEmpty());
    }

    @Test
    void shouldReturnUnmodifiableConsumersMap() {
        TestableCustomCamelHttpTransportServlet servlet = new TestableCustomCamelHttpTransportServlet("test-servlet");

        HttpConsumer consumer = mock(HttpConsumer.class);
        ServletEndpoint endpoint = mock(ServletEndpoint.class);

        when(consumer.getEndpoint()).thenReturn(endpoint);
        when(endpoint.getServletName()).thenReturn("test-servlet");
        when(endpoint.getEndpointUri()).thenReturn("servlet-custom:/orders");

        servlet.connect(consumer);

        Map<String, HttpConsumer> consumers = servlet.getConsumers();

        assertThrows(UnsupportedOperationException.class, () -> consumers.put("another", mock(HttpConsumer.class)));
    }

    @Test
    void shouldThrowWhenConsumerEndpointIsNotServletEndpoint() {
        TestableCustomCamelHttpTransportServlet servlet = new TestableCustomCamelHttpTransportServlet("test-servlet");

        HttpConsumer consumer = mock(HttpConsumer.class);
        HttpCommonEndpoint endpoint = mock(HttpCommonEndpoint.class);

        when(consumer.getEndpoint()).thenReturn(endpoint);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> servlet.connect(consumer));

        assertTrue(exception.getMessage().contains("Invalid consumer type"));
    }

    private static class TestableCustomCamelHttpTransportServlet extends CustomCamelHttpTransportServlet {
        private final String servletName;

        private TestableCustomCamelHttpTransportServlet(String servletName) {
            this.servletName = servletName;
        }

        @Override
        public String getServletName() {
            return servletName;
        }
    }
}
