package org.qubership.integration.platform.engine.camel.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetadataConverterTest {

    private ObjectMapper objectMapper;
    private MetadataConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = mock(ObjectMapper.class);
        converter = new MetadataConverter();
        converter.objectMapper = objectMapper;
    }

    @Test
    void shouldSerializeMetadataWhenToStringCalled() throws Exception {
        Metadata metadata = mock(Metadata.class);
        when(objectMapper.writeValueAsString(metadata)).thenReturn("{\"key\":\"value\"}");

        String result = converter.toString(metadata);

        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenSerializationFails() throws Exception {
        Metadata metadata = mock(Metadata.class);
        JsonProcessingException exception = new JsonProcessingException("serialize failed") {
        };
        when(objectMapper.writeValueAsString(metadata)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> converter.toString(metadata));

        assertEquals("Failed to serialize metadata", thrown.getMessage());
        assertSame(exception, thrown.getCause());
    }

    @Test
    void shouldDeserializeMetadataWhenToMetadataCalled() throws Exception {
        Metadata metadata = mock(Metadata.class);
        when(objectMapper.readValue("{\"key\":\"value\"}", Metadata.class)).thenReturn(metadata);

        Metadata result = converter.toMetadata("{\"key\":\"value\"}");

        assertSame(metadata, result);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenDeserializationFails() throws Exception {
        JsonProcessingException exception = new JsonProcessingException("deserialize failed") {
        };
        when(objectMapper.readValue("invalid-json", Metadata.class)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> converter.toMetadata("invalid-json"));

        assertEquals("Failed to deserialize metadata", thrown.getMessage());
        assertSame(exception, thrown.getCause());
        assertInstanceOf(JsonProcessingException.class, thrown.getCause());
    }
}
