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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelExchangeResponseContextDataTest {

    @Test
    void shouldPutStringValueWhenNameAndValueArePresent() {
        Map<String, Object> headers = new HashMap<>();
        CamelExchangeResponseContextData contextData = new CamelExchangeResponseContextData(headers);

        contextData.set("X-Request-Id", "a0e0f8b4-7d34-4717-9384-edec63665379");

        assertEquals("a0e0f8b4-7d34-4717-9384-edec63665379", headers.get("X-Request-Id"));
    }

    @Test
    void shouldOverwriteExistingHeaderWhenHeaderAlreadyExists() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("X-Request-Id", "a0e0f8b4-7d34-4717-9384-edec63665379");
        CamelExchangeResponseContextData contextData = new CamelExchangeResponseContextData(headers);

        contextData.set("X-Request-Id", "07219d13-23c0-4fbe-be83-f8f30df9a6c8");

        assertEquals("07219d13-23c0-4fbe-be83-f8f30df9a6c8", headers.get("X-Request-Id"));
    }

    @ParameterizedTest
    @MethodSource("invalidSetArguments")
    void shouldNotChangeHeadersWhenNameOrValueIsNull(String name, Object value) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("Existing-Header", "existing-value");
        CamelExchangeResponseContextData contextData = new CamelExchangeResponseContextData(headers);

        contextData.set(name, value);

        assertEquals(1, headers.size());
        assertEquals("existing-value", headers.get("Existing-Header"));
    }

    @Test
    void shouldAllowEmptyHeadersMapWhenNothingIsSet() {
        Map<String, Object> headers = new HashMap<>();

        new CamelExchangeResponseContextData(headers);

        assertTrue(headers.isEmpty());
    }

    private static Stream<Arguments> invalidSetArguments() {
        return Stream.of(
            Arguments.of(null, "value"),
            Arguments.of("X-Request-Id", null),
            Arguments.of(null, null)
        );
    }
}
