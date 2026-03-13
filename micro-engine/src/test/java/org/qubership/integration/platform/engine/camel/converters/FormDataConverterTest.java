package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.TypeConversionException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FormDataConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldConvertJsonStringToFormData() throws Exception {
        FormDataConverter converter = new FormDataConverter(objectMapper);

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

        assertEquals("username", result.getEntries().get(0).getName());
        assertEquals("bob", result.getEntries().get(0).getValue());
        assertEquals(null, result.getEntries().get(0).getFileName());
        assertEquals(null, result.getEntries().get(0).getMimeType());

        assertEquals("document", result.getEntries().get(1).getName());
        assertEquals("resume.txt", result.getEntries().get(1).getFileName());
        assertEquals("file-content", result.getEntries().get(1).getValue());
        assertEquals(null, result.getEntries().get(1).getMimeType());
    }

    @Test
    void shouldConvertNonStringValueUsingStringValueOf() throws Exception {
        FormDataConverter converter = new FormDataConverter(objectMapper);

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
        assertEquals("email", result.getEntries().get(0).getName());
        assertEquals("bob@example.com", result.getEntries().get(0).getValue());
        assertEquals(null, result.getEntries().get(0).getFileName());
        assertEquals(null, result.getEntries().get(0).getMimeType());
    }

    @Test
    void shouldThrowTypeConversionExceptionWhenJsonIsInvalid() {
        FormDataConverter converter = new FormDataConverter(objectMapper);

        TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> converter.convert("not-a-json")
        );

        assertEquals(FormData.class, exception.getToType());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }
}
