package org.qubership.integration.platform.engine.camel.processors;

import jakarta.ws.rs.core.HttpHeaders;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpTriggerProcessorTest {

    private static final String HTTP_TRIGGER_STEP_ID = "f4a4d31c-9a2a-46d7-bb1a-57f2660fd101";
    private static final String VALIDATION_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["customerId", "orderId", "customer"],
              "properties": {
                "customerId": {
                  "type": "string",
                  "pattern": "^C-\\\\d+$"
                },
                "orderId": {
                  "type": "string",
                  "pattern": "^O-\\\\d+$"
                },
                "customer": {
                  "type": "object",
                  "required": ["firstName", "lastName", "email"],
                  "properties": {
                    "firstName": {
                      "type": "string",
                      "minLength": 1
                    },
                    "lastName": {
                      "type": "string",
                      "minLength": 1
                    },
                    "email": {
                      "type": "string",
                      "format": "email"
                    },
                    "vip": {
                      "type": "boolean"
                    }
                  },
                  "additionalProperties": false
                },
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["sku", "quantity"],
                    "properties": {
                      "sku": {
                        "type": "string",
                        "minLength": 1
                      },
                      "quantity": {
                        "type": "integer",
                        "minimum": 1
                      }
                    },
                    "additionalProperties": false
                  }
                }
              },
              "additionalProperties": false
            }
            """;

    private final CorrelationIdSetter correlationIdSetter = mock(CorrelationIdSetter.class);
    private final JsonMessageValidator validator = mock(JsonMessageValidator.class);
    private final HttpTriggerProcessor processor = new HttpTriggerProcessor(correlationIdSetter, validator);

    @Test
    void shouldParsePathVariablesSaveLoggingContextParseResponseFiltersAndRemoveHeadersWhenRequestValid() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.ALLOWED_CONTENT_TYPES_PROP, new String[]{"application/json"});
        exchange.setProperty(Properties.VALIDATION_SCHEMA, VALIDATION_SCHEMA);
        exchange.setProperty(Properties.RESPONSE_FILTER, true);

        exchange.getMessage().setBody("""
                {
                  "customerId": "C-100500",
                  "orderId": "O-456",
                  "customer": {
                    "firstName": "Harry",
                    "lastName": "Potter",
                    "email": "hp@example.com",
                    "vip": true
                  },
                  "items": [
                    {
                      "sku": "SKU-1",
                      "quantity": 2
                    }
                  ]
                }
                """);
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, "POST");
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123/orders/456");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123/orders/456");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}/orders/{orderId}");
        exchange.getMessage().setHeader(Exchange.HTTP_PATH, "/customers/123/orders/456");
        exchange.getMessage().setHeader(Exchange.HTTP_QUERY, "fields=name,email&excludeFields=password");

        processor.process(exchange);

        assertEquals(
                "http://localhost:8080/routes/customers/123/orders/456",
                exchange.getProperty(Properties.SERVLET_REQUEST_URL)
        );
        assertEquals(
                HTTP_TRIGGER_STEP_ID,
                exchange.getProperty(Properties.HTTP_TRIGGER_STEP_ID)
        );
        assertEquals("123", exchange.getProperty("customerId"));
        assertEquals("456", exchange.getProperty("orderId"));
        assertEquals("name,email", exchange.getProperty(Properties.RESPONSE_FILTER_INCLUDE_FIELDS));
        assertEquals("password", exchange.getProperty(Properties.RESPONSE_FILTER_EXCLUDE_FIELDS));

        assertNull(exchange.getMessage().getHeader(Exchange.HTTP_URI));
        assertNull(exchange.getMessage().getHeader(Exchange.HTTP_URL));
        assertNull(exchange.getMessage().getHeader(Exchange.HTTP_PATH));

        verify(correlationIdSetter).setCorrelationId(exchange);
        verify(validator).validate("""
                {
                  "customerId": "C-100500",
                  "orderId": "O-456",
                  "customer": {
                    "firstName": "Harry",
                    "lastName": "Potter",
                    "email": "hp@example.com",
                    "vip": true
                  },
                  "items": [
                    {
                      "sku": "SKU-1",
                      "quantity": 2
                    }
                  ]
                }
                """, VALIDATION_SCHEMA);
    }

    @Test
    void shouldSkipPathVariablesParsingWhenCheckpointTriggerStep() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.IS_CHECKPOINT_TRIGGER_STEP, true);

        exchange.getMessage().setBody("{\"customerId\":\"C-100500\"}");
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, "POST");
        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");
        exchange.getMessage().setHeader(Exchange.HTTP_PATH, "/customers/123");

        processor.process(exchange);

        assertEquals(
                "http://localhost:8080/routes/customers/123",
                exchange.getProperty(Properties.SERVLET_REQUEST_URL)
        );
        assertEquals(
                HTTP_TRIGGER_STEP_ID,
                exchange.getProperty(Properties.HTTP_TRIGGER_STEP_ID)
        );
        assertNull(exchange.getProperty("customerId"));

        verifyNoInteractions(correlationIdSetter, validator);
    }

    @Test
    void shouldThrowValidationExceptionWhenGetRequestContainsBodyAndValidationEnabled() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.REJECT_REQUEST_IF_NULL_BODY_GET_DELETE_PROP, true);

        exchange.getMessage().setBody("{\"customerId\":\"C-100500\"}");
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, "GET");
        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> processor.process(exchange)
        );

        assertEquals(
                "Not empty body is not allowed with [GET] method, request rejected",
                exception.getMessage()
        );
        verifyNoInteractions(correlationIdSetter, validator);
    }

    @Test
    void shouldThrowValidationExceptionWhenRequestContentTypeUnsupported() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.ALLOWED_CONTENT_TYPES_PROP, new String[]{"application/json"});

        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, "invalid");

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> processor.process(exchange)
        );

        assertEquals("Unsupported content type: 'invalid'", exception.getMessage());
        verifyNoInteractions(correlationIdSetter, validator);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenAllowedContentTypeConfigurationUnsupported() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.ALLOWED_CONTENT_TYPES_PROP, new String[]{"not-a-mime"});

        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> processor.process(exchange)
        );

        assertEquals(
                "Unsupported content type found in validation list: 'not-a-mime', please fix it",
                exception.getMessage()
        );
        verifyNoInteractions(correlationIdSetter, validator);
    }

    @Test
    void shouldParseOnlyPresentResponseFilterParameters() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Exchange.STEP_ID, "request--" + HTTP_TRIGGER_STEP_ID);
        exchange.setProperty(Properties.RESPONSE_FILTER, true);

        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");
        exchange.getMessage().setHeader(Exchange.HTTP_QUERY, "fields=name,email");

        processor.process(exchange);

        assertEquals("name,email", exchange.getProperty(Properties.RESPONSE_FILTER_INCLUDE_FIELDS));
        assertNull(exchange.getProperty(Properties.RESPONSE_FILTER_EXCLUDE_FIELDS));
        verify(correlationIdSetter).setCorrelationId(exchange);
        verifyNoInteractions(validator);
    }
}
