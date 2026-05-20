package org.qubership.integration.platform.engine.configuration;

import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.debugger.metrics.SessionsMetricsService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "qip.metrics.enabled", value = "true")
@TestConfigProperty(key = "qip.opensearch.index.elements.name", value = "qip-elements-local")
class SessionsMetricsServiceProducerTest {

    @Inject
    SessionsMetricsServiceProducer producer;

    @InjectMock
    MetricsStore metricsStore;

    @InjectMock
    OpenSearchClientSupplier openSearchClientSupplier;

    @InjectMock
    CheckpointRepository checkpointRepository;

    @Test
    void shouldCreateSessionsMetricsServiceWithConfiguredIndexNameAndDependencies() throws Exception {
        SessionsMetricsService result = producer.getMetricsService(
                metricsStore,
                openSearchClientSupplier,
                checkpointRepository
        );

        assertNotNull(result);
        assertEquals("qip-elements-local", fieldValue(result, "indexName"));
        assertSame(metricsStore, fieldValue(result, "metricsStore"));
        assertSame(openSearchClientSupplier, fieldValue(result, "openSearchClientSupplier"));
        assertSame(checkpointRepository, fieldValue(result, "checkpointRepository"));
    }

    private static Object fieldValue(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
