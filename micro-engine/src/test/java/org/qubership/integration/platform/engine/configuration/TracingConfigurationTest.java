package org.qubership.integration.platform.engine.configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.service.debugger.tracing.MicrometerObservationTaggedTracer;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "quarkus.otel.traces.enabled", value = "true")
class TracingConfigurationTest {

    @Inject
    TracingConfiguration tracingConfiguration;

    @Test
    void shouldInjectTracingEnabledProperty() {
        assertTrue(tracingConfiguration.isTracingEnabled());
    }

    @Test
    void shouldCreateCamelObservationTracerAndInjectOptionalDependenciesWhenPresent() throws Exception {
        Instance<Tracer> tracerInstance = mockInstance();
        Instance<ObservationRegistry> observationRegistryInstance = mockInstance();

        Tracer tracer = mock(Tracer.class);
        ObservationRegistry observationRegistry = mock(ObservationRegistry.class);

        try (var injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(tracerInstance))
                    .thenReturn(Optional.of(tracer));
            injectUtil.when(() -> InjectUtil.injectOptional(observationRegistryInstance))
                    .thenReturn(Optional.of(observationRegistry));

            MicrometerObservationTracer result = tracingConfiguration.camelObservationTracer(
                    tracerInstance,
                    observationRegistryInstance
            );

            assertInstanceOf(MicrometerObservationTaggedTracer.class, result);
            assertSame(tracer, fieldValue(result, "tracer"));
            assertSame(observationRegistry, fieldValue(result, "observationRegistry"));
        }
    }

    @Test
    void shouldCreateCamelObservationTracerWithoutOptionalDependenciesWhenInstancesAreEmpty() throws Exception {
        Instance<Tracer> tracerInstance = mockInstance();
        Instance<ObservationRegistry> observationRegistryInstance = mockInstance();

        try (var injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(tracerInstance))
                    .thenReturn(Optional.empty());
            injectUtil.when(() -> InjectUtil.injectOptional(observationRegistryInstance))
                    .thenReturn(Optional.empty());

            MicrometerObservationTracer result = tracingConfiguration.camelObservationTracer(
                    tracerInstance,
                    observationRegistryInstance
            );

            assertInstanceOf(MicrometerObservationTaggedTracer.class, result);
            assertNull(fieldValue(result, "tracer"));
            assertNull(fieldValue(result, "observationRegistry"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> mockInstance() {
        return mock(Instance.class);
    }

    private static Object fieldValue(Object target, String fieldName) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
