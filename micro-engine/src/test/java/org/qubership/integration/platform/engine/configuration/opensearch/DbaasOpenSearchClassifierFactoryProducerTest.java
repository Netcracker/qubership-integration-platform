package org.qubership.integration.platform.engine.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.management.classifier.DbaaSClassifierBuilder;
import com.netcracker.cloud.dbaas.common.classifier.DbaaSClassifierFactory;
import com.netcracker.cloud.dbaas.common.classifier.TenantClassifierBuilder;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DbaasOpenSearchClassifierFactoryProducerTest {

    private final DbaasOpenSearchClassifierFactoryProducer producer =
            new DbaasOpenSearchClassifierFactoryProducer();

    @Test
    void shouldCreateFactoryAndReturnTenantClassifierBuilderWithSessionsLogicalDbName() {
        DbaaSClassifierFactory factory = producer.opensearchClassifierFactory();

        assertNotNull(factory);

        DbaaSClassifierBuilder builder = factory.newTenantClassifierBuilder(Map.of("tenantId", "t1"));

        assertNotNull(builder);
        assertInstanceOf(TenantClassifierBuilder.class, builder);
        assertTrue(containsLogicalDbName(builder, LOGICAL_DB_NAME, "sessions"));
    }

    @Test
    void shouldCreateFactoryAndReturnServiceClassifierBuilder() {
        DbaaSClassifierFactory factory = producer.opensearchClassifierFactory();

        assertNotNull(factory);
        assertNotNull(factory.newServiceClassifierBuilder(Map.of("serviceId", "s1")));
    }

    private static boolean containsLogicalDbName(Object target, String expectedKey, String expectedValue) {
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
