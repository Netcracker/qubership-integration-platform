package org.qubership.integration.platform.engine.camel;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaHeaderStringDeserializerTest {

    private final KafkaHeaderStringDeserializer deserializer = new KafkaHeaderStringDeserializer();

    @Test
    void shouldReturnNullWhenHeaderValueNull() {
        Object result = deserializer.deserialize("customerId", null);

        assertNull(result);
    }

    @Test
    void shouldDeserializeHeaderValueToStringWhenBytesProvided() {
        byte[] value = "C-100500".getBytes(StandardCharsets.UTF_8);

        Object result = deserializer.deserialize("customerId", value);

        assertEquals("C-100500", result);
    }

    @Test
    void shouldDeserializeUnicodeHeaderValueToStringWhenBytesProvided() {
        byte[] value = "Hello".getBytes(StandardCharsets.UTF_8);

        Object result = deserializer.deserialize("message", value);

        assertEquals("Hello", result);
    }
}
