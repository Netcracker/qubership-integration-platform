package org.qubership.integration.platform.engine.opensearch.ism.deserializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TimeValueDeserializerTest {

    private final TimeValueDeserializer deserializer = new TimeValueDeserializer();

    @Mock
    private JsonParser jsonParser;

    @Test
    void shouldDeserializeTimeValueWhenNodeIsTextNode() throws IOException {
        TextNode node = TextNode.valueOf("5m");
        TimeValue expected = mock(TimeValue.class);

        when(jsonParser.readValueAsTree()).thenReturn(node);
        when(jsonParser.getCurrentName()).thenReturn("timeout");

        try (MockedStatic<TimeValue> timeValueMock = org.mockito.Mockito.mockStatic(TimeValue.class)) {
            timeValueMock
                    .when(() -> TimeValue.parseTimeValue("5m", "timeout"))
                    .thenReturn(expected);

            TimeValue result = deserializer.deserialize(jsonParser, null);

            assertSame(expected, result);
        }
    }

    @Test
    void shouldThrowInvalidFormatExceptionWhenNodeIsNotTextNode() throws IOException {
        IntNode node = IntNode.valueOf(10);

        when(jsonParser.readValueAsTree()).thenReturn(node);
        when(jsonParser.getCurrentName()).thenReturn("timeout");

        InvalidFormatException exception = assertThrows(
                InvalidFormatException.class,
                () -> deserializer.deserialize(jsonParser, null)
        );

        assertEquals("Wrong timeout field type", exception.getOriginalMessage());
        assertEquals(TimeValue.class, exception.getTargetType());
    }
}
