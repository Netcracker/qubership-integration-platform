package org.qubership.integration.platform.engine.routes.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class NumericInputSnapshotScenarioDriverProviderTest {
    private static final String ENDPOINT_URI = "direct:original-chain";
    private final NumericInputSnapshotScenarioDriverProvider provider = new NumericInputSnapshotScenarioDriverProvider();

    @Mock
    private ProducerTemplate producerTemplate;

    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"-9223372036854775808", "9223372036854775807", "9007199254740993"})
    void shouldPreserveLongPrecisionWhenInputUsesDecimalText(String value) throws Exception {
        SnapshotScenarioInvocation invocation = invocation(value, Map.of(), Map.of());

        Exchange result = execute(driver("long"), invocation);

        assertEquals(Long.valueOf(value), assertInstanceOf(Long.class, result.getMessage().getBody()));
        assertEquals(value, invocation.getBody());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void shouldCreateLongWhenInputFitsInInteger(int value) throws Exception {
        SnapshotScenarioInvocation invocation = invocation(value, Map.of(), Map.of());

        Exchange result = execute(driver("long"), invocation);

        assertEquals((long) value, assertInstanceOf(Long.class, result.getMessage().getBody()));
        assertInstanceOf(Integer.class, invocation.getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "2147483647", "0"})
    void shouldCreateIntegerWhenBodyTypeIsInteger(String value) throws Exception {
        Exchange result = execute(driver("integer"), invocation(value, Map.of(), Map.of()));

        assertEquals(Integer.valueOf(value), assertInstanceOf(Integer.class, result.getMessage().getBody()));
    }

    @Test
    void shouldPreserveNullsAndPrecisionWhenBodyContainsLongValues() throws Exception {
        List<Object> values = Arrays.asList("-9223372036854775808", 0, null, "9223372036854775807");
        SnapshotScenarioInvocation invocation = invocation(values, Map.of(), Map.of());

        Exchange result = execute(driver("long-list"), invocation);

        assertEquals(Arrays.asList(Long.MIN_VALUE, 0L, null, Long.MAX_VALUE), result.getMessage().getBody());
        assertEquals(values, invocation.getBody());
    }

    @Test
    void shouldPreserveEmptyListWhenBodyContainsNoValues() throws Exception {
        Exchange result = execute(driver("long-list"), invocation(List.of(), Map.of(), Map.of()));

        assertEquals(List.of(), result.getMessage().getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "integer", "long-list"})
    void shouldPreserveNullWhenBodyIsNull(String bodyType) throws Exception {
        Exchange result = execute(driver(bodyType), invocation(null, Map.of(), Map.of()));

        assertNull(result.getMessage().getBody());
    }

    @Test
    void shouldConvertOnlyDeclaredHeadersWhenInputHasHeadersAndProperties() throws Exception {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("kafka.KEY", 7);
        headers.put("count", "2147483647");
        headers.put("unchanged", 8);
        headers.put("nullable", null);
        SnapshotScenarioInvocation invocation = new SnapshotScenarioInvocation(
                "typed-input", null, null, null, 0, headers, Map.of("kafkaKey", 9, "requestId", "original"),
                null, null, null, null, null, null, null, null
        );
        SnapshotScenarioDriver driver = provider.create(null, definition(Map.of(
                "endpointUri", ENDPOINT_URI,
                "bodyType", "long",
                "headerTypes", Map.of("kafka.KEY", "long", "count", "integer", "nullable", "long", "absent", "long")
        )), List.of(invocation));

        Exchange result = execute(driver, invocation);

        assertEquals(7L, assertInstanceOf(Long.class, result.getMessage().getHeader("kafka.KEY")));
        assertEquals(Integer.MAX_VALUE, assertInstanceOf(Integer.class, result.getMessage().getHeader("count")));
        assertEquals(8, assertInstanceOf(Integer.class, result.getMessage().getHeader("unchanged")));
        assertNull(result.getMessage().getHeader("nullable"));
        assertFalse(result.getMessage().getHeaders().containsKey("absent"));
        assertEquals(9, assertInstanceOf(Integer.class, result.getProperty("kafkaKey")));
        assertEquals("original", result.getProperty("requestId"));
        assertEquals(headers, invocation.getHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "1.5", "invalid"})
    void shouldRejectInvalidLongBeforeCallingChain(String value) {
        SnapshotScenarioDriver driver = driver("long");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(value, Map.of(), Map.of())));

        assertEquals("Numeric input driver body cannot be represented as long.", exception.getMessage());
        assertInstanceOf(NumberFormatException.class, exception.getCause());
        verifyNoInteractions(producerTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649"})
    void shouldRejectIntegerOverflowBeforeCallingChain(String value) {
        SnapshotScenarioDriver driver = driver("integer");

        assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(value, Map.of(), Map.of())));

        verifyNoInteractions(producerTemplate);
    }

    @Test
    void shouldRejectInvalidListItemBeforeCallingChain() {
        SnapshotScenarioDriver driver = driver("long-list");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(List.of(0, "1.5"), Map.of(), Map.of())));

        assertEquals("Numeric input driver body[1] cannot be represented as long.", exception.getMessage());
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void shouldRejectScalarWhenBodyTypeRequiresList() {
        SnapshotScenarioDriver driver = driver("long-list");

        assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(0, Map.of(), Map.of())));

        verifyNoInteractions(producerTemplate);
    }

    @Test
    void shouldRejectInvalidTypedHeaderBeforeCallingChain() {
        SnapshotScenarioDriver driver = provider.create(null, definition(Map.of(
                "endpointUri", ENDPOINT_URI, "bodyType", "long", "headerTypes", Map.of("kafka.KEY", "integer")
        )), List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(0, Map.of("kafka.KEY", "2147483648"), Map.of())));

        assertEquals("Numeric input driver header 'kafka.KEY' cannot be represented as integer.", exception.getMessage());
        verifyNoInteractions(producerTemplate);
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void shouldRejectInvalidConfigurationBeforeCreatingDriver(Map<String, Object> parameters, String message) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> provider.create(null, definition(parameters), List.of()));

        assertTrue(exception.getMessage().contains(message));
    }

    private static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of(Map.of("bodyType", "long"), "endpointUri"),
                Arguments.of(Map.of("endpointUri", " ", "bodyType", "long"), "endpointUri"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI), "bodyType"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "double"), "bodyType"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "long", "unknown", true), "unknown"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "long", "headerTypes", "long"), "headerTypes"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "long", "headerTypes", Map.of(" ", "long")), "keys"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "long", "headerTypes", Map.of(7, "long")), "keys"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "long", "headerTypes", Map.of("key", "long-list")), "header 'key'")
        );
    }

    private SnapshotScenarioDriver driver(String bodyType) {
        return provider.create(null, definition(Map.of("endpointUri", ENDPOINT_URI, "bodyType", bodyType)), List.of());
    }

    private SnapshotScenarioDriverDefinition definition(Map<String, Object> parameters) {
        return new SnapshotScenarioDriverDefinition("numericInput", null, parameters);
    }

    private SnapshotScenarioInvocation invocation(Object body, Map<String, Object> headers, Map<String, Object> properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "typed-input");
        values.put("body", body);
        values.put("headers", headers);
        values.put("properties", properties);
        return objectMapper.convertValue(values, SnapshotScenarioInvocation.class);
    }

    private Exchange execute(SnapshotScenarioDriver driver, SnapshotScenarioInvocation invocation) throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        when(producerTemplate.request(eq(ENDPOINT_URI), any(Processor.class))).thenAnswer(call -> {
            call.getArgument(1, Processor.class).process(exchange);
            return exchange;
        });

        Exchange result = driver.execute(producerTemplate, invocation);

        assertSame(exchange, result);
        return result;
    }
}
