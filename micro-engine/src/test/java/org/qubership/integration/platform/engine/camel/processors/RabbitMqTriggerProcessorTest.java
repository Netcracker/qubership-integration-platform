package org.qubership.integration.platform.engine.camel.processors;

import com.rabbitmq.client.Channel;
import org.apache.camel.Exchange;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.ExchangeUtils;
import org.springframework.amqp.core.AcknowledgeMode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RabbitMqTriggerProcessorTest {

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

    private static final String VALID_RABBIT_MESSAGE = """
        {
          "eventId": "7f2ef9e7-4b8d-4c17-9911-cd2b76a9e101",
          "customerId": "C-100500",
          "eventType": "CUSTOMER_UPDATED"
        }
        """;

    private final JsonMessageValidator validator = mock(JsonMessageValidator.class);
    private final RabbitMqTriggerProcessor processor = new RabbitMqTriggerProcessor(validator);

    @Test
    void shouldAcknowledgeMessageAndValidateWhenAcknowledgeModeManualAndSchemaPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Channel channel = mock(Channel.class);

        exchange.setProperty(Properties.ACKNOWLEDGE_MODE_PROP, AcknowledgeMode.MANUAL.name());
        exchange.setProperty(SpringRabbitMQConstants.CHANNEL, channel);
        exchange.setProperty(Properties.ASYNC_VALIDATION_SCHEMA, ASYNC_VALIDATION_SCHEMA);
        exchange.getMessage().setHeader(SpringRabbitMQConstants.DELIVERY_TAG, 42L);
        exchange.getMessage().setBody(VALID_RABBIT_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verify(channel).basicAck(42L, false);
        verify(validator).validate(VALID_RABBIT_MESSAGE, ASYNC_VALIDATION_SCHEMA);
    }

    @Test
    void shouldNotAcknowledgeWhenAcknowledgeModePropertyMissingAndShouldValidateWhenSchemaPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Properties.ASYNC_VALIDATION_SCHEMA, ASYNC_VALIDATION_SCHEMA);
        exchange.getMessage().setBody(VALID_RABBIT_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verify(validator).validate(VALID_RABBIT_MESSAGE, ASYNC_VALIDATION_SCHEMA);
    }

    @Test
    void shouldNotAcknowledgeWhenAcknowledgeModeNotManual() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Channel channel = mock(Channel.class);

        exchange.setProperty(Properties.ACKNOWLEDGE_MODE_PROP, AcknowledgeMode.AUTO.name());
        exchange.setProperty(SpringRabbitMQConstants.CHANNEL, channel);
        exchange.getMessage().setBody(VALID_RABBIT_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verifyNoInteractions(channel, validator);
    }

    @Test
    void shouldSetContentTypeAndSkipValidationWhenAsyncValidationSchemaNull() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setBody(VALID_RABBIT_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verifyNoInteractions(validator);
    }

    @Test
    void shouldSetContentTypeAndSkipValidationWhenAsyncValidationSchemaEmpty() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Properties.ASYNC_VALIDATION_SCHEMA, "");
        exchange.getMessage().setBody(VALID_RABBIT_MESSAGE);

        try (MockedStatic<ExchangeUtils> exchangeUtils = mockStatic(ExchangeUtils.class)) {
            processor.process(exchange);

            exchangeUtils.verify(() -> ExchangeUtils.setContentTypeIfMissing(exchange));
        }

        verifyNoInteractions(validator);
    }
}
