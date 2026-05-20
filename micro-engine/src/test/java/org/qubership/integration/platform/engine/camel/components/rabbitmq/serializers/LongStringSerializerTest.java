package org.qubership.integration.platform.engine.camel.components.rabbitmq.serializers;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.rabbitmq.client.LongString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LongStringSerializerTest {

    private LongStringSerializer serializer;

    @Mock
    JsonGenerator generator;
    @Mock
    SerializerProvider provider;
    @Mock
    LongString value;

    @BeforeEach()
    void setUp() {
        value = mock(LongString.class);
        serializer = new LongStringSerializer(LongString.class);
    }

    @Test
    void shouldWriteUtf8StringWhenSerialize() throws Exception {
        when(value.getBytes()).thenReturn("hello".getBytes(StandardCharsets.UTF_8));

        serializer.serialize(value, generator, provider);

        verify(generator).writeString("hello");
    }

    @Test
    void shouldRethrowIOExceptionWhenGeneratorFails() throws Exception {
        when(value.getBytes()).thenReturn("hello".getBytes(StandardCharsets.UTF_8));

        IOException boom = new IOException("boom");
        doThrow(boom).when(generator).writeString(anyString());

        assertThrows(IOException.class, () -> serializer.serialize(value, generator, provider));

        verify(generator).writeString("hello");
    }
}
