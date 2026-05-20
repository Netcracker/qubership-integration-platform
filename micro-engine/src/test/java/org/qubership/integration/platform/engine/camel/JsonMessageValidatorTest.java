package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class JsonMessageValidatorTest {

    JsonMessageValidator validator;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        validator = new JsonMessageValidator(objectMapper);
    }

    @Test
    void shouldValidateMessageWhenJsonMatchesSchema() {
        String jsonMessage = """
                {
                  "name": "Harry",
                  "age": 30
                }
                """;

        String jsonSchema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string"
                    },
                    "age": {
                      "type": "integer"
                    }
                  },
                  "required": ["name", "age"]
                }
                """;

        assertDoesNotThrow(() -> validator.validate(jsonMessage, jsonSchema));
    }

    @Test
    void shouldThrowValidationExceptionWhenMessageBodyIsBlank() {
        String jsonSchema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object"
                }
                """;

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate("   ", jsonSchema)
        );

        assertEquals("Message body is empty", exception.getMessage());
    }

    @Test
    void shouldThrowValidationExceptionWhenMessageBodyCannotBeParsed() {
        String jsonSchema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object"
                }
                """;

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate("{invalid-json}", jsonSchema)
        );

        assertEquals("Unable to parse message body", exception.getMessage());
    }

    @Test
    void shouldThrowValidationExceptionWhenMessageDoesNotMatchSchema() {
        String jsonMessage = """
                {
                  "name": 123
                }
                """;

        String jsonSchema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string"
                    }
                  },
                  "required": ["name"]
                }
                """;

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> validator.validate(jsonMessage, jsonSchema)
        );

        assertTrue(exception.getMessage().startsWith(JsonMessageValidator.MESSAGE_VALIDATION_ERROR));
    }
}
