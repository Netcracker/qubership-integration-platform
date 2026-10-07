package org.qubership.integration.platform.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementUtilsTest {

    @ParameterizedTest
    @CsvSource({"true, true", "false, false", "TRUE, true", "False, false"})
    void readsAFlagStoredAsAString(String stored, boolean expected) {
        assertEquals(expected, ElementUtils.getPropertyAsBoolean(Map.of("flag", stored), "flag", !expected));
    }

    @Test
    void readsAFlagStoredAsABoolean() {
        assertTrue(ElementUtils.getPropertyAsBoolean(Map.of("flag", true), "flag", false));
        assertFalse(ElementUtils.getPropertyAsBoolean(Map.of("flag", false), "flag", true));
    }

    @Test
    void returnsTheDefaultForAMissingOrNullFlag() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("nullFlag", null);

        assertTrue(ElementUtils.getPropertyAsBoolean(properties, "missing", true));
        assertTrue(ElementUtils.getPropertyAsBoolean(properties, "nullFlag", true));
        assertFalse(ElementUtils.getPropertyAsBoolean(properties, "missing", false));
    }
}
