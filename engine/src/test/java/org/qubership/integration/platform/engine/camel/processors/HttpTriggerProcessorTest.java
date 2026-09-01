package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class HttpTriggerProcessorTest {

    private static final String STEP_ID = "request--f4a4d31c-9a2a-46d7-bb1a-57f2660fd101";
    private static final String SESSION_ID = "testing-session-1";

    @Mock
    private CorrelationIdSetter correlationIdSetter;

    @Mock
    private JsonMessageValidator validator;

    private HttpTriggerProcessor processor;
    private Exchange exchange;

    @BeforeEach
    void setUp() {
        processor = new HttpTriggerProcessor(correlationIdSetter, validator);
        exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(Exchange.STEP_ID, STEP_ID);
        exchange.getMessage().setHeader(Exchange.HTTP_URL, "http://localhost:8080/routes/customers/123");
        exchange.getMessage().setHeader(Exchange.HTTP_URI, "routes/customers/123");
        exchange.getMessage().setHeader(Headers.URI_TEMPLATE, "customers/{customerId}");
    }

    // The testing service activates the trigger with this header, and endpoint mocking reads the run back off
    // the property rather than off the header, which a chain is free to remove before the call it would mock.
    @Test
    void remembersTheTestCaseRunTheSessionHeaderNames() throws Exception {
        exchange.getMessage().setHeader(Headers.EXTERNAL_SESSION_CIP_ID, SESSION_ID);

        processor.process(exchange);

        assertEquals(SESSION_ID, exchange.getProperty(Properties.TESTING_SESSION_ID));
    }

    @Test
    void remembersNoTestCaseRunWhenTheSessionHeaderIsAbsent() throws Exception {
        processor.process(exchange);

        assertNull(exchange.getProperty(Properties.TESTING_SESSION_ID));
    }

    @Test
    void remembersNoTestCaseRunWhenTheSessionHeaderIsBlank() throws Exception {
        exchange.getMessage().setHeader(Headers.EXTERNAL_SESSION_CIP_ID, "  ");

        processor.process(exchange);

        assertNull(exchange.getProperty(Properties.TESTING_SESSION_ID));
    }
}
