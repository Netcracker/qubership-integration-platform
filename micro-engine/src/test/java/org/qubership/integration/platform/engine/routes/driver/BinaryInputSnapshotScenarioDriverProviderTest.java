package org.qubership.integration.platform.engine.routes.driver;

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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
class BinaryInputSnapshotScenarioDriverProviderTest {
    private static final String ENDPOINT_URI = "direct:original-chain";
    private final BinaryInputSnapshotScenarioDriverProvider provider = new BinaryInputSnapshotScenarioDriverProvider();

    @Mock
    private ProducerTemplate producerTemplate;

    @Test
    void shouldPreserveAllByteValuesWhenBodyUsesHex() throws Exception {
        byte[] expected = new byte[256];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) index;
        }
        String hex = HexFormat.of().formatHex(expected);
        SnapshotScenarioInvocation invocation = invocation(hex);

        Exchange result = execute(driver("bytes"), invocation);

        assertArrayEquals(expected, assertInstanceOf(byte[].class, result.getMessage().getBody()));
        assertEquals(hex, invocation.getBody());
    }

    @Test
    void shouldPreserveNullBytesAndInvalidUtf8WhenBodyUsesHex() throws Exception {
        Exchange result = execute(driver("bytes"), invocation("00C328fF00"));

        assertArrayEquals(new byte[]{0, (byte) 0xc3, 0x28, (byte) 0xff, 0},
                assertInstanceOf(byte[].class, result.getMessage().getBody()));
    }

    @Test
    void shouldDistinguishEmptyBytesFromNullWhenBodyHasNoData() throws Exception {
        SnapshotScenarioDriver driver = driver("bytes");

        Exchange empty = execute(driver, invocation(""));
        Exchange missing = execute(driver, invocation(null));

        assertArrayEquals(new byte[0], assertInstanceOf(byte[].class, empty.getMessage().getBody()));
        assertNull(missing.getMessage().getBody());
    }

    @Test
    void shouldCreateFreshBytesWhenInvocationIsRepeated() throws Exception {
        SnapshotScenarioDriver driver = driver("bytes");
        SnapshotScenarioInvocation invocation = invocation("00ff");
        byte[] first = assertInstanceOf(byte[].class, execute(driver, invocation).getMessage().getBody());
        first[0] = 7;

        byte[] second = assertInstanceOf(byte[].class, execute(driver, invocation).getMessage().getBody());

        assertNotSame(first, second);
        assertArrayEquals(new byte[]{0, (byte) 0xff}, second);
        assertEquals("00ff", invocation.getBody());
    }

    @Test
    void shouldCreateFreshStreamWhenInvocationIsRepeated() throws Exception {
        SnapshotScenarioDriver driver = driver("input-stream");
        SnapshotScenarioInvocation invocation = invocation("00ff");
        InputStream first = assertInstanceOf(InputStream.class, execute(driver, invocation).getMessage().getBody());
        assertArrayEquals(new byte[]{0, (byte) 0xff}, first.readAllBytes());

        InputStream second = assertInstanceOf(InputStream.class, execute(driver, invocation).getMessage().getBody());

        assertNotSame(first, second);
        assertArrayEquals(new byte[]{0, (byte) 0xff}, second.readAllBytes());
        assertEquals("00ff", invocation.getBody());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void shouldCreateFreshBufferAtRequestedPositionWhenInvocationIsRepeated(int position) throws Exception {
        SnapshotScenarioDriver driver = provider.create(null, definition(Map.of(
                "endpointUri", ENDPOINT_URI, "bodyType", "byte-buffer", "bufferPosition", position
        )), List.of());
        SnapshotScenarioInvocation invocation = invocation("00ff01");
        ByteBuffer first = assertInstanceOf(ByteBuffer.class, execute(driver, invocation).getMessage().getBody());
        assertEquals(position, first.position());
        assertEquals(3, first.limit());
        first.position(3);
        first.put(0, (byte) 7);

        ByteBuffer second = assertInstanceOf(ByteBuffer.class, execute(driver, invocation).getMessage().getBody());

        assertNotSame(first, second);
        assertEquals(position, second.position());
        assertEquals(3, second.limit());
        assertArrayEquals(new byte[]{0, (byte) 0xff, 1}, second.array());
        assertEquals("00ff01", invocation.getBody());
    }

    @Test
    void shouldDefaultBufferPositionToZeroWhenPositionIsOmitted() throws Exception {
        ByteBuffer result = assertInstanceOf(ByteBuffer.class, execute(driver("byte-buffer"), invocation("00ff")).getMessage().getBody());

        assertEquals(0, result.position());
        assertEquals(2, result.remaining());
        assertArrayEquals(new byte[]{0, (byte) 0xff}, result.array());
    }

    @ParameterizedTest
    @ValueSource(strings = {"byte-buffer", "input-stream"})
    void shouldPreserveNullWhenScalarBodyHasNoData(String bodyType) throws Exception {
        Exchange result = execute(driver(bodyType), invocation(null));

        assertNull(result.getMessage().getBody());
    }

    @Test
    void shouldRejectBufferPositionBeyondBodyBeforeCallingChain() {
        SnapshotScenarioDriver driver = provider.create(null, definition(Map.of(
                "endpointUri", ENDPOINT_URI, "bodyType", "byte-buffer", "bufferPosition", 3
        )), List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation("00ff")));

        assertEquals("Binary input driver bufferPosition exceeds the body length.", exception.getMessage());
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void shouldConvertOnlyHexMarkersWhenBatchHasMixedValues() throws Exception {
        Map<String, Object> ordinaryMap = Map.of("value", "unchanged");
        List<Object> values = Arrays.asList(Map.of("hex", "00ff"), null, "invalid-type", 42, ordinaryMap, Map.of("hex", ""));
        SnapshotScenarioInvocation invocation = invocation(values);

        List<?> result = assertInstanceOf(List.class, execute(driver("batch"), invocation).getMessage().getBody());

        assertArrayEquals(new byte[]{0, (byte) 0xff}, assertInstanceOf(byte[].class, result.get(0)));
        assertNull(result.get(1));
        assertEquals("invalid-type", assertInstanceOf(String.class, result.get(2)));
        assertEquals(42, assertInstanceOf(Integer.class, result.get(3)));
        assertSame(ordinaryMap, result.get(4));
        assertArrayEquals(new byte[0], assertInstanceOf(byte[].class, result.get(5)));
        assertEquals(values, invocation.getBody());
        assertNotSame(values, result);
    }

    @Test
    void shouldCreateFreshListAndBytesWhenBatchInvocationIsRepeated() throws Exception {
        SnapshotScenarioDriver driver = driver("batch");
        SnapshotScenarioInvocation invocation = invocation(List.of(Map.of("hex", "00ff")));
        List<?> first = assertInstanceOf(List.class, execute(driver, invocation).getMessage().getBody());
        byte[] firstBytes = assertInstanceOf(byte[].class, first.getFirst());
        firstBytes[0] = 7;
        first.clear();

        List<?> second = assertInstanceOf(List.class, execute(driver, invocation).getMessage().getBody());

        assertNotSame(first, second);
        assertNotSame(firstBytes, second.getFirst());
        assertArrayEquals(new byte[]{0, (byte) 0xff}, assertInstanceOf(byte[].class, second.getFirst()));
        assertEquals(List.of(Map.of("hex", "00ff")), invocation.getBody());
    }

    @Test
    void shouldPreserveEmptyBatchWhenBodyContainsNoEntries() throws Exception {
        Exchange result = execute(driver("batch"), invocation(List.of()));

        assertEquals(List.of(), result.getMessage().getBody());
    }

    @Test
    void shouldPreserveHeadersAndPropertiesWhenBodyIsConverted() throws Exception {
        Map<String, Object> headers = Map.of("kafka.KEY", "original-key", "X-Request-Id", "request-id", "count", 42);
        Map<String, Object> properties = Map.of("kafkaKey", "configured-key", "outboundRequestId", "outbound-id");
        SnapshotScenarioInvocation invocation = new SnapshotScenarioInvocation(
                "binary-input", null, null, null, "00ff", headers, properties,
                null, null, null, null, null, null, null, null
        );

        Exchange result = execute(driver("bytes"), invocation);

        headers.forEach((name, value) -> assertEquals(value, result.getMessage().getHeader(name)));
        properties.forEach((name, value) -> assertEquals(value, result.getProperty(name)));
        assertEquals(headers, invocation.getHeaders());
        assertEquals(properties, invocation.getProperties());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "gg", "00 ff"})
    void shouldRejectInvalidHexBeforeCallingChain(String value) {
        SnapshotScenarioDriver driver = driver("bytes");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(value)));

        assertEquals("Binary input driver body contains invalid hex.", exception.getMessage());
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        verifyNoInteractions(producerTemplate);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void shouldRejectInvalidBodyBeforeCallingChain(String bodyType, Object body, String message) {
        SnapshotScenarioDriver driver = driver(bodyType);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation(body)));

        assertEquals(message, exception.getMessage());
        verifyNoInteractions(producerTemplate);
    }

    private static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("bytes", 42, "Binary input driver body must be a hex string."),
                Arguments.of("batch", "00ff", "Binary input driver body must be a list for bodyType batch."),
                Arguments.of("batch", null, "Binary input driver body must be a list for bodyType batch."),
                Arguments.of("batch", List.of(Map.of("hex", "0")), "Binary input driver body[0].hex contains invalid hex."),
                Arguments.of("batch", List.of(Map.of("hex", 42)), "Binary input driver body[0].hex must be a hex string."),
                Arguments.of("batch", List.of(Collections.singletonMap("hex", null)),
                        "Binary input driver body[0].hex must be a hex string."),
                Arguments.of("batch", List.of(Map.of("hex", "00", "extra", true)),
                        "Binary input driver body[0] hex marker must contain only the hex field.")
        );
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
                Arguments.of(Map.of("bodyType", "bytes"), "endpointUri"),
                Arguments.of(Map.of("endpointUri", " ", "bodyType", "bytes"), "endpointUri"),
                Arguments.of(Map.of("endpointUri", 42, "bodyType", "bytes"), "endpointUri"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI), "bodyType"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "string"), "bodyType"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "bytes", "unknown", true), "unknown"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "bytes", "bufferPosition", 0), "requires bodyType byte-buffer"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "byte-buffer", "bufferPosition", -1), "nonnegative integer"),
                Arguments.of(Map.of("endpointUri", ENDPOINT_URI, "bodyType", "byte-buffer", "bufferPosition", "1"), "nonnegative integer")
        );
    }

    private SnapshotScenarioDriver driver(String bodyType) {
        return provider.create(null, definition(Map.of("endpointUri", ENDPOINT_URI, "bodyType", bodyType)), List.of());
    }

    private SnapshotScenarioDriverDefinition definition(Map<String, Object> parameters) {
        return new SnapshotScenarioDriverDefinition("binaryInput", null, parameters);
    }

    private SnapshotScenarioInvocation invocation(Object body) {
        return new SnapshotScenarioInvocation(
                "binary-input", null, null, null, body, Map.of(), Map.of(),
                null, null, null, null, null, null, null, null
        );
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
