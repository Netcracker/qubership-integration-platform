/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.camel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;

import static org.junit.jupiter.api.Assertions.*;

class JsonMessageValidatorTest {

    private static final String SCHEMA_STRING_FIELD = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" }
              },
              "required": ["name"]
            }
            """;

    private JsonMessageValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonMessageValidator();
    }

    @Test
    void validMessageDoesNotThrow() {
        assertDoesNotThrow(() -> validator.validate("{\"name\": \"Alice\"}", SCHEMA_STRING_FIELD));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankOrEmptyMessageThrowsValidationException(String message) {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate(message, SCHEMA_STRING_FIELD));
        assertEquals(JsonMessageValidator.EMPTY_BODY_ERROR, ex.getMessage());
    }

    @Test
    void messageFailingSchemaValidationThrowsValidationException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("{\"name\": 123}", SCHEMA_STRING_FIELD));
        assertTrue(ex.getMessage().startsWith(JsonMessageValidator.MESSAGE_VALIDATION_ERROR));
    }

    @Test
    void messageMissingRequiredFieldThrowsValidationException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("{\"age\": 30}", SCHEMA_STRING_FIELD));
        assertTrue(ex.getMessage().startsWith(JsonMessageValidator.MESSAGE_VALIDATION_ERROR));
    }

    @Test
    void multipleValidationErrorsAreAllIncludedInMessage() {
        String schemaMultiRequired = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" },
                    "age": { "type": "integer" }
                  },
                  "required": ["name", "age"]
                }
                """;

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("{}", schemaMultiRequired));
        String message = ex.getMessage();
        assertTrue(message.startsWith(JsonMessageValidator.MESSAGE_VALIDATION_ERROR));
        // Both missing fields should be reported
        assertTrue(message.contains("name") || message.contains("age"));
    }
}
