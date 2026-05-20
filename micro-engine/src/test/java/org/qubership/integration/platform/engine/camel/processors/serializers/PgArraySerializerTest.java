package org.qubership.integration.platform.engine.camel.processors.serializers;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.jdbc.PgArray;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PgArraySerializerTest {

    private final PgArraySerializer serializer = new PgArraySerializer(PgArray.class);

    @Mock
    private PgArray value;

    @Test
    void shouldSerializeStringArrayWhenPgArrayContainsStrings() throws Exception {
        when(value.getArray()).thenReturn(new String[]{"one", "two"});

        String result = serialize(value);

        assertEquals("[\"one\",\"two\"]", result);
    }

    @Test
    void shouldSerializeIntArrayWhenPgArrayContainsInts() throws Exception {
        when(value.getArray()).thenReturn(new int[]{1, 2, 3});

        String result = serialize(value);

        assertEquals("[1,2,3]", result);
    }

    @Test
    void shouldSerializeLongArrayWhenPgArrayContainsLongs() throws Exception {
        when(value.getArray()).thenReturn(new long[]{1L, 2L, 3L});

        String result = serialize(value);

        assertEquals("[1,2,3]", result);
    }

    @Test
    void shouldSerializeDoubleArrayWhenPgArrayContainsDoubles() throws Exception {
        when(value.getArray()).thenReturn(new double[]{1.25d, 2.5d, 3.75d});

        String result = serialize(value);

        assertEquals("[1.25,2.5,3.75]", result);
    }

    @Test
    void shouldSerializeFloatArrayAsDoubleArrayWhenPgArrayContainsFloats() throws Exception {
        when(value.getArray()).thenReturn(new float[]{1.25f, 2.5f, 3.75f});

        String result = serialize(value);

        assertEquals("[1.25,2.5,3.75]", result);
    }

    @Test
    void shouldThrowIOExceptionWhenPgArrayContainsUnsupportedArrayType() throws Exception {
        when(value.getArray()).thenReturn(new Integer[]{1, 2, 3});

        IOException exception = assertThrows(
                IOException.class,
                () -> serializer.serialize(value, mock(JsonGenerator.class), null)
        );

        assertEquals(
                "Failed to serialize PgArray object, invalid array type: [Ljava.lang.Integer;",
                exception.getMessage()
        );
    }

    @Test
    void shouldRethrowIOExceptionWhenGeneratorWriteFails() throws Exception {
        JsonGenerator generator = mock(JsonGenerator.class);
        when(value.getArray()).thenReturn(new int[]{1, 2});
        doThrow(new IOException("write failed"))
                .when(generator)
                .writeArray(any(int[].class), eq(0), eq(2));

        IOException exception = assertThrows(
                IOException.class,
                () -> serializer.serialize(value, generator, null)
        );

        assertEquals("write failed", exception.getMessage());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenPgArrayAccessFails() throws Exception {
        when(value.getArray()).thenThrow(new SQLException("db failure"));

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> serializer.serialize(value, mock(JsonGenerator.class), null)
        );

        assertInstanceOf(SQLException.class, exception.getCause());
        assertEquals("db failure", exception.getCause().getMessage());
    }

    private String serialize(PgArray value) throws IOException {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(writer)) {
            serializer.serialize(value, generator, null);
        }
        return writer.toString();
    }
}
