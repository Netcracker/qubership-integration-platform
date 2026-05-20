package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomComponent;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomFilterStrategy;
import org.qubership.integration.platform.engine.camel.context.propagation.ContextPropsProvider;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelServletCustomizerProducerTest {

    private final CamelServletCustomizerProducer producer = new CamelServletCustomizerProducer();

    @Mock
    private ContextPropsProvider contextPropsProvider;

    @Test
    void shouldCreateCustomizerAndApplyServletCustomFilterStrategy() {
        ComponentCustomizer customizer = producer.servletCustomComponentCustomizer(contextPropsProvider);
        ServletCustomComponent component = new ServletCustomComponent();

        assertNotNull(customizer);

        customizer.configure("servletCustom", component);

        assertNotNull(component.getHeaderFilterStrategy());
        assertInstanceOf(ServletCustomFilterStrategy.class, component.getHeaderFilterStrategy());
    }
}
