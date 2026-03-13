package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class JsonMessageValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldValidateMessageWhenJsonMatchesSchema() {
        JsonMessageValidator validator = new JsonMessageValidator(objectMapper);

        String jsonMessage = """
                {
                  "name": "Alex",
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
        JsonMessageValidator validator = new JsonMessageValidator(objectMapper);

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
        JsonMessageValidator validator = new JsonMessageValidator(objectMapper);

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
        JsonMessageValidator validator = new JsonMessageValidator(objectMapper);

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

        assertEquals(
                true,
                exception.getMessage().startsWith(JsonMessageValidator.MESSAGE_VALIDATION_ERROR)
        );
    }
}
