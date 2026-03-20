package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.errorhandling.ResponseValidationException;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ResponseValidationProcessorTest {

    private static final String VALIDATION_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["customerId", "orderId", "status"],
          "properties": {
            "customerId": {
              "type": "string",
              "pattern": "^C-\\\\d+$"
            },
            "orderId": {
              "type": "string",
              "pattern": "^O-\\\\d+$"
            },
            "status": {
              "type": "string",
              "enum": ["CREATED", "PAID", "SHIPPED"]
            }
          },
          "additionalProperties": false
        }
        """;

    private static final String VALID_RESPONSE_BODY = """
        {
          "customerId": "C-100500",
          "orderId": "O-456",
          "status": "PAID"
        }
        """;

    private final JsonMessageValidator validator = mock(JsonMessageValidator.class);
    private final ResponseValidationProcessor processor = new ResponseValidationProcessor(validator);

    @Test
    void shouldValidateJsonResponseWhenValidationSchemaPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CamelConstants.Properties.VALIDATION_SCHEMA, VALIDATION_SCHEMA);
        exchange.getMessage().setBody(VALID_RESPONSE_BODY);

        processor.process(exchange);

        verify(validator).validate(VALID_RESPONSE_BODY, VALIDATION_SCHEMA);
    }

    @Test
    void shouldSkipValidationWhenValidationSchemaNull() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setBody(VALID_RESPONSE_BODY);

        processor.process(exchange);

        verifyNoInteractions(validator);
    }

    @Test
    void shouldSkipValidationWhenValidationSchemaBlank() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CamelConstants.Properties.VALIDATION_SCHEMA, "   ");
        exchange.getMessage().setBody(VALID_RESPONSE_BODY);

        processor.process(exchange);

        verifyNoInteractions(validator);
    }

    @Test
    void shouldThrowResponseValidationExceptionWhenValidatorThrowsValidationException() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CamelConstants.Properties.VALIDATION_SCHEMA, VALIDATION_SCHEMA);
        exchange.getMessage().setBody(VALID_RESPONSE_BODY);

        doThrow(new ValidationException("Response field 'status' is invalid"))
                .when(validator)
                .validate(VALID_RESPONSE_BODY, VALIDATION_SCHEMA);

        ResponseValidationException exception = assertThrows(
                ResponseValidationException.class,
                () -> processor.process(exchange)
        );

        assertEquals("Response field 'status' is invalid", exception.getMessage());
    }
}
