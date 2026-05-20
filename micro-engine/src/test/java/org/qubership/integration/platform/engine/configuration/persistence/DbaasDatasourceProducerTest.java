package org.qubership.integration.platform.engine.configuration.persistence;

import com.netcracker.cloud.core.quarkus.dbaas.datasource.DbaaSDataSource;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.DbaaSPostgresDbCreationService;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaaSClassifierBuilder;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.NAMESPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "cloud.microservice.name", value = "qip-engine")
@TestConfigProperty(key = "cloud.microservice.namespace", value = "local")
class DbaasDatasourceProducerTest {

    @Inject
    DbaasDatasourceProducer producer;

    @Test
    void shouldCreateConfigsDataSourceWithExpectedClassifierAndCreationService() {
        DbaaSPostgresDbCreationService creationService = mock(DbaaSPostgresDbCreationService.class);
        AtomicReference<List<?>> constructorArguments = new AtomicReference<>();

        try (MockedConstruction<DbaaSDataSource> dataSources = mockConstruction(
                DbaaSDataSource.class,
                (mock, context) -> constructorArguments.set(List.copyOf(context.arguments()))
        )) {
            AgroalDataSource result = producer.configsDataSource(creationService);

            assertSame(dataSources.constructed().getFirst(), result);
            assertEquals(2, constructorArguments.get().size());

            Object classifierBuilder = constructorArguments.get().get(0);
            Object passedCreationService = constructorArguments.get().get(1);

            assertInstanceOf(DbaaSClassifierBuilder.class, classifierBuilder);
            assertSame(creationService, passedCreationService);

            assertTrue(containsEntry(classifierBuilder, NAMESPACE, "local"));
            assertTrue(containsEntry(classifierBuilder, MICROSERVICE_NAME, "qip-engine"));
            assertTrue(containsEntry(classifierBuilder, LOGICAL_DB_NAME, "configs"));
        }
    }

    private static boolean containsEntry(Object target, String expectedKey, String expectedValue) {
        return containsEntryRecursive(target, expectedKey, expectedValue, new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static boolean containsEntryRecursive(
            Object target,
            String expectedKey,
            String expectedValue,
            IdentityHashMap<Object, Boolean> visited
    ) {
        if (target == null || visited.containsKey(target)) {
            return false;
        }
        visited.put(target, Boolean.TRUE);

        if (target instanceof Map<?, ?> map) {
            Object value = map.get(expectedKey);
            if (expectedValue.equals(value)) {
                return true;
            }
            for (Object entryValue : map.values()) {
                if (containsEntryRecursive(entryValue, expectedKey, expectedValue, visited)) {
                    return true;
                }
            }
            return false;
        }

        if (target instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsEntryRecursive(item, expectedKey, expectedValue, visited)) {
                    return true;
                }
            }
            return false;
        }

        if (target.getClass().isArray() && !target.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(target);
            for (int i = 0; i < length; i++) {
                if (containsEntryRecursive(Array.get(target, i), expectedKey, expectedValue, visited)) {
                    return true;
                }
            }
            return false;
        }

        Package targetPackage = target.getClass().getPackage();
        String packageName = targetPackage == null ? "" : targetPackage.getName();
        if (packageName.startsWith("java.")
                || packageName.startsWith("javax.")
                || packageName.startsWith("jdk.")
                || packageName.startsWith("sun.")) {
            return false;
        }

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(target);
                    if (containsEntryRecursive(value, expectedKey, expectedValue, visited)) {
                        return true;
                    }
                } catch (RuntimeException | IllegalAccessException ignored) {
                    // skip inaccessible fields
                }
            }
            current = current.getSuperclass();
        }

        return false;
    }
}
