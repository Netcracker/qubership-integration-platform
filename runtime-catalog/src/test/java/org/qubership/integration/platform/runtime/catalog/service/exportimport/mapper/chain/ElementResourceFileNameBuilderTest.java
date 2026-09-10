package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ElementResourceFileNameBuilderTest {

    private final ElementResourceFileNameBuilder builder = new ElementResourceFileNameBuilder() {
        @Override
        public String generatePropertiesFileName(
                org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity externalElement,
                java.util.List<String> propsToExportInSeparateFile) {
            return "";
        }

        @Override
        public String generateAfterScriptFileName(String id, Map<String, Object> afterProp) {
            return "";
        }

        @Override
        public String generateBeforeScriptFileName(String id) {
            return "";
        }

        @Override
        public String generateAfterMapperFileName(String id, Map<String, Object> afterProp) {
            return "";
        }

        @Override
        public String generateBeforeMapperFileName(String id) {
            return "";
        }
    };

    @ParameterizedTest(name = "{0} normalizes to {1}")
    @CsvSource({
        "100..199, 1xx",
        "200..299, 2xx",
        "300..399, 3xx",
        "400..499, 4xx",
        "500..599, 5xx"
    })
    @DisplayName("normalizes each n00..n99 range to nxx")
    void normalizeAfterIdNormalizesEachRange(String input, String expected) {
        assertEquals(expected, builder.normalizeAfterId(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"600..699", "100..299", "200..200", "abc", "1xx", "", "100..199 ", " 100..199", "100..1990"})
    @DisplayName("leaves non-matching values unchanged")
    void normalizeAfterIdLeavesNonMatchingValuesUnchanged(String input) {
        assertEquals(input, builder.normalizeAfterId(input));
    }

    @DisplayName("handles numeric input via String.valueOf")
    @Test
    void normalizeAfterIdHandlesNumericInputViaStringValueOf() {
        assertEquals("404", builder.normalizeAfterId(404));
    }

    @DisplayName("handles null input as the string null")
    @Test
    void normalizeAfterIdHandlesNullInputAsStringNull() {
        assertEquals("null", builder.normalizeAfterId(null));
    }

    @DisplayName("prefers id over code when both are present")
    @Test
    void getIdOrCodePrefersIdOverCode() {
        Map<String, Object> map = Map.of("id", "theId", "code", "theCode");

        Object result = builder.getIdOrCode(map);

        assertEquals("theId", result);
    }

    @DisplayName("falls back to code when id is absent")
    @Test
    void getIdOrCodeFallsBackToCodeWhenIdAbsent() {
        Map<String, Object> map = Map.of("code", "theCode");

        Object result = builder.getIdOrCode(map);

        assertEquals("theCode", result);
    }

    @DisplayName("falls back to code when id is null")
    @Test
    void getIdOrCodeFallsBackToCodeWhenIdNull() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", null);
        map.put("code", "theCode");

        Object result = builder.getIdOrCode(map);

        assertEquals("theCode", result);
    }

    @DisplayName("returns null when neither id nor code is present")
    @Test
    void getIdOrCodeReturnsNullWhenNeitherPresent() {
        Map<String, Object> map = Map.of();

        Object result = builder.getIdOrCode(map);

        assertNull(result);
    }

    @DisplayName("returns null when both id and code are absent in a mutable map")
    @Test
    void getIdOrCodeReturnsNullWhenBothAbsentMutable() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("other", "x");

        Object result = builder.getIdOrCode(map);

        assertNull(result);
    }
}
