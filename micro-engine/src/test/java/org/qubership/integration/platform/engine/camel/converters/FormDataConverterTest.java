package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.TypeConversionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FormDataConverterTest {

    private FormDataConverter converter;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        converter = new FormDataConverter(objectMapper);
    }

    @Test
    void shouldConvertJsonStringToFormData() {
        String json = """
                [
                  {
                    "name": "username",
                    "value": "bob"
                  },
                  {
                    "name": "document",
                    "fileName": "resume.txt",
                    "value": "file-content"
                  }
                ]
                """;

        FormData result = converter.convert(json);

        assertEquals(2, result.getEntries().size());

        assertEquals("username", result.getEntries().getFirst().getName());
        assertEquals("bob", result.getEntries().getFirst().getValue());
        assertNull(result.getEntries().getFirst().getFileName());
        assertNull(result.getEntries().getFirst().getMimeType());

        assertEquals("document", result.getEntries().get(1).getName());
        assertEquals("resume.txt", result.getEntries().get(1).getFileName());
        assertEquals("file-content", result.getEntries().get(1).getValue());
        assertNull(result.getEntries().get(1).getMimeType());
    }

    @Test
    void shouldConvertNonStringValueUsingStringValueOf() {
        Object value = new Object() {
            @Override
            public String toString() {
                return """
                        [
                          {
                            "name": "email",
                            "value": "bob@example.com"
                          }
                        ]
                        """;
            }
        };

        FormData result = converter.convert(value);

        assertEquals(1, result.getEntries().size());
        assertEquals("email", result.getEntries().getFirst().getName());
        assertEquals("bob@example.com", result.getEntries().getFirst().getValue());
        assertNull(result.getEntries().getFirst().getFileName());
        assertNull(result.getEntries().getFirst().getMimeType());
    }

    @Test
    void shouldThrowTypeConversionExceptionWhenJsonIsInvalid() {
        TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> converter.convert("not-a-json")
        );

        assertEquals(FormData.class, exception.getToType());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }
}
