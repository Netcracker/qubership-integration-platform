package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ServletCustomEndpointTest {

    @Test
    void shouldGenerateServletCustomIdAndConfigureUrisWhenCreated() throws Exception {
        HeaderFilterStrategy headerFilterStrategy = mock(HeaderFilterStrategy.class);
        ServletCustomEndpoint endpoint = createEndpoint(headerFilterStrategy);

        String servletCustomId = endpoint.getServletCustomId();

        assertNotNull(servletCustomId);
        assertTrue(endpoint.getEndpointUri().contains("servletCustomId=" + servletCustomId));
        assertTrue(endpoint.getHttpUri().getQuery().contains("servletCustomId=" + servletCustomId));
        assertSame(headerFilterStrategy, endpoint.getHeaderFilterStrategy());
    }

    @Test
    void shouldIgnoreProvidedServletCustomIdWhenSetterCalled() throws Exception {
        ServletCustomEndpoint endpoint = createEndpoint(mock(HeaderFilterStrategy.class));

        String originalServletCustomId = endpoint.getServletCustomId();

        endpoint.setServletCustomId("manually-set-id");

        assertSame(originalServletCustomId, endpoint.getServletCustomId());
        assertNotEquals("manually-set-id", endpoint.getServletCustomId());
    }

    @Test
    void shouldSetAndGetTagsProvider() throws Exception {
        ServletCustomEndpoint endpoint = createEndpoint(mock(HeaderFilterStrategy.class));
        ServletTagsProvider tagsProvider = mock(ServletTagsProvider.class);

        endpoint.setTagsProvider(tagsProvider);

        assertSame(tagsProvider, endpoint.getTagsProvider());
    }

    @Test
    void shouldCreateServletCustomConsumerWhenCreateConsumerCalled() throws Exception {
        ServletCustomEndpoint endpoint = createEndpoint(mock(HeaderFilterStrategy.class));
        Processor processor = mock(Processor.class);

        Consumer consumer = endpoint.createConsumer(processor);

        assertInstanceOf(ServletCustomConsumer.class, consumer);
        assertSame(endpoint, ((ServletCustomConsumer) consumer).getEndpoint());
        assertSame(processor, ((ServletCustomConsumer) consumer).getProcessor());
        assertTrue(((ServletCustomConsumer) consumer).getCreationTime() > 0);
    }

    private static ServletCustomEndpoint createEndpoint(HeaderFilterStrategy headerFilterStrategy) throws Exception {
        ServletCustomComponent component = new ServletCustomComponent();
        component.setCamelContext(new DefaultCamelContext());

        return new ServletCustomEndpoint(
                "servlet-custom:/test",
                component,
                new URI("http://localhost:8080/test"),
                headerFilterStrategy
        );
    }
}
