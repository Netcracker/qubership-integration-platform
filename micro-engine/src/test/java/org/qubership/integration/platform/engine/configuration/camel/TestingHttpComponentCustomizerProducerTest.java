package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TestingHttpComponentCustomizerProducerTest {

    private final TestingHttpComponentCustomizerProducer producer = new TestingHttpComponentCustomizerProducer();

    // Without the listener camel-http files no exchange into the client context, and endpoint mocking cannot
    // tell a test case run from a live one. The @LookupIfProperty switch is not covered: it is resolved by a
    // Quarkus build step, so verify it by hand.
    @Test
    void shouldInstallAnActivityListenerOnTheHttpComponent() {
        ComponentCustomizer customizer = producer.testingHttpComponentCustomizer();
        HttpComponent component = new HttpComponent();

        customizer.configure("http", component);

        assertNotNull(component.getHttpActivityListener());
    }
}
