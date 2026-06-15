package org.qubership.integration.platform.engine.camel.context.propagation;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelExchangeRequestContextDataTest {

    @Test
    void shouldReturnHeaderValueCaseInsensitivelyWhenHeaderExists() {
        CamelExchangeRequestContextData contextData = new CamelExchangeRequestContextData(Map.of(
            "X-Request-Id", "2b43d893-cb15-4ef9-98af-ab95fa28b1e1"
        ));

        assertEquals("2b43d893-cb15-4ef9-98af-ab95fa28b1e1", contextData.get("X-Request-Id"));
        assertEquals("2b43d893-cb15-4ef9-98af-ab95fa28b1e1", contextData.get("x-request-id"));
        assertEquals("2b43d893-cb15-4ef9-98af-ab95fa28b1e1", contextData.get("X-REQUEST-ID"));
    }

    @Test
    void shouldJoinListHeaderValuesWithCommaWhenHeaderValueIsList() {
        CamelExchangeRequestContextData contextData = new CamelExchangeRequestContextData(Map.of(
            "X-Forwarded-For", List.of("10.0.0.1", "10.0.0.2", 100)
        ));

        assertEquals("10.0.0.1,10.0.0.2,100", contextData.get("X-Forwarded-For"));
    }

    @Test
    void shouldIgnoreHeadersWithNullNameOrValueWhenCreated() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(null, "value");
        headers.put("X-Null-Value", null);

        CamelExchangeRequestContextData contextData = new CamelExchangeRequestContextData(headers);

        assertTrue(contextData.getAll().isEmpty());
        assertNull(contextData.get("X-Null-Value"));
    }

    @Test
    void shouldReturnAllHeadersAsSingleValueListsWhenHeadersArePresent() {
        CamelExchangeRequestContextData contextData = new CamelExchangeRequestContextData(Map.of(
            "X-Request-Id", "2b43d893-cb15-4ef9-98af-ab95fa28b1e1",
            "X-Tenant", "default-tenant",
            "X-Forwarded-For", List.of("10.0.0.1", "10.0.0.2")
        ));

        Map<String, List<?>> headers = contextData.getAll();

        assertEquals(3, headers.size());
        assertEquals(List.of("2b43d893-cb15-4ef9-98af-ab95fa28b1e1"), getHeaderValue(headers, "X-Request-Id"));
        assertEquals(List.of("default-tenant"), getHeaderValue(headers, "X-Tenant"));
        assertEquals(List.of("10.0.0.1,10.0.0.2"), getHeaderValue(headers, "X-Forwarded-For"));
    }

    @ParameterizedTest
    @MethodSource("headerValues")
    void shouldConvertHeaderValueToStringWhenCreated(Object sourceValue, String expectedValue) {
        CamelExchangeRequestContextData contextData = new CamelExchangeRequestContextData(Map.of(
            "X-Test-Header", sourceValue
        ));

        assertEquals(expectedValue, contextData.get("X-Test-Header"));
    }

    private static Stream<Arguments> headerValues() {
        return Stream.of(
            Arguments.of("value", "value"),
            Arguments.of(42, "42"),
            Arguments.of(true, "true")
        );
    }

    private static List<?> getHeaderValue(Map<String, List<?>> headers, String name) {
        return headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    }
}
