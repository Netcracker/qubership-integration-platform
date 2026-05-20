package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.ExchangeUtils;

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaTriggerProcessorTest {

    private static final String ASYNC_VALIDATION_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["eventId", "customerId", "eventType"],
          "properties": {
            "eventId": {
              "type": "string",
              "format": "uuid"
            },
            "customerId": {
              "type": "string",
              "pattern": "^C-\\\\d+$"
            },
            "eventType": {
              "type": "string",
              "minLength": 1
            }
          },
          "additionalProperties": false
        }
        """;

    private static final String VALID_KAFKA_MESSAGE = """
        {
          "eventId": "7f2ef9e7-4b8d-4c17-9911-cd2b76a9e101",
          "customerId": "C-100500",
          "eventType": "CUSTOMER_UPDATED"
        }
        """;

    private KafkaTriggerProcessor processor;

    @Mock
    JsonMessageValidator validator;
    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        processor = new KafkaTriggerProcessor(validator);
    }


    @Test
    void shouldSetContentTypeAndValidateMessageWhenAsyncValidationSchemaPresent() throws Exception {
        exchange.setProperty(Properties.ASYNC_VALIDATION_SCHEMA, ASYNC_VALIDATION_SCHEMA);
        exchange.getMessage().setBody(VALID_KAFKA_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verify(validator).validate(VALID_KAFKA_MESSAGE, ASYNC_VALIDATION_SCHEMA);
    }

    @Test
    void shouldSetContentTypeAndSkipValidationWhenAsyncValidationSchemaNull() throws Exception {
        exchange.getMessage().setBody(VALID_KAFKA_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verifyNoInteractions(validator);
    }

    @Test
    void shouldSetContentTypeAndSkipValidationWhenAsyncValidationSchemaEmpty() throws Exception {
        exchange.setProperty(Properties.ASYNC_VALIDATION_SCHEMA, "");
        exchange.getMessage().setBody(VALID_KAFKA_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verifyNoInteractions(validator);
    }
}
