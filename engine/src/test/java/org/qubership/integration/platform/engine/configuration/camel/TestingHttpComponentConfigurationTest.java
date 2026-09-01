package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestingHttpComponentConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestingHttpComponentConfiguration.class);

    @Test
    void theCustomizerIsAbsentWhenMockingIsOff() {
        runner.run(context -> assertEquals(0, context.getBeanNamesForType(ComponentCustomizer.class).length));
    }

    @Test
    void theCustomizerIsAbsentWhenTheFlagIsFalse() {
        runner.withPropertyValues("qip.testing.enabled=false")
                .run(context -> assertEquals(0, context.getBeanNamesForType(ComponentCustomizer.class).length));
    }

    // Without the listener camel-http files no exchange into the client context, and endpoint mocking cannot
    // tell a test case run from a live one.
    @Test
    void theCustomizerInstallsAnActivityListenerWhenMockingIsOn() {
        runner.withPropertyValues("qip.testing.enabled=true").run(context -> {
            HttpComponent component = new HttpComponent();
            context.getBean(ComponentCustomizer.class).configure("http", component);

            assertNotNull(component.getHttpActivityListener());
        });
    }
}
