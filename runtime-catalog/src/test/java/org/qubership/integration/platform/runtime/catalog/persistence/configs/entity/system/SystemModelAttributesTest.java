package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression guard: {@code SystemModel} has no {@code active} attribute, so no Criteria query may reference
 * one. A behavioral test is impossible here — the module has no database harness, and the existing Criteria
 * tests mock {@code Root}, so {@code get("active")} returns a mock instead of failing.
 */
class SystemModelAttributesTest {

    @Test
    @DisplayName("SystemModel exposes no 'active' attribute, so no query may filter on it")
    void systemModelHasNoActiveAttribute() {
        assertFalse(mappedAttributeNames().contains("active"),
                "SystemModel has an 'active' attribute again; revisit the operation filter query");
    }

    private static Set<String> mappedAttributeNames() {
        Set<String> names = new HashSet<>();
        for (Class<?> type = SystemModel.class; type != null && type != Object.class; type = type.getSuperclass()) {
            Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isAnnotationPresent(Transient.class))
                    .map(Field::getName)
                    .forEach(names::add);
        }
        return names;
    }
}
