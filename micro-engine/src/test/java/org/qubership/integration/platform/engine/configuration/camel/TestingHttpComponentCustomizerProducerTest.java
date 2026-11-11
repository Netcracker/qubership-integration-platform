package org.qubership.integration.platform.engine.configuration.camel;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.lookup.LookupIfProperty;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TestingHttpComponentCustomizerProducerTest {

    private final TestingHttpComponentCustomizerProducer producer = new TestingHttpComponentCustomizerProducer();

    // Without the listener camel-http files no exchange into the client context, and endpoint mocking cannot
    // tell a test case run from a live one.
    @Test
    void shouldInstallAnActivityListenerOnTheHttpComponent() {
        ComponentCustomizer customizer = producer.testingHttpComponentCustomizer();
        HttpComponent component = new HttpComponent();

        customizer.configure("http", component);

        assertNotNull(component.getHttpActivityListener());
    }

    // Camel looks the customizer up programmatically: without @Unremovable ArC removes the producer, and a test case
    // run calls the real endpoint instead of its mock. No unit test runs the @LookupIfProperty switch itself, so
    // verify it by hand.
    @Test
    void shouldCarryTheAnnotationsTheProgrammaticLookupNeeds() throws NoSuchMethodException {
        Method method = TestingHttpComponentCustomizerProducer.class.getMethod("testingHttpComponentCustomizer");

        assertNotNull(method.getAnnotation(Unremovable.class),
                "ArC removes a producer nothing injects, so @Unremovable is required");

        LookupIfProperty lookup = method.getAnnotation(LookupIfProperty.class);

        assertNotNull(lookup, "@LookupIfProperty is what gates the lookup on the property");
        assertEquals("cip.testing.enabled", lookup.name());
        assertEquals("true", lookup.stringValue());
    }
}
