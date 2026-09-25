package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.contextstorage.ContextStorageService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.INTERNAL_PROPERTY_PREFIX;

@ExtendWith(MockitoExtension.class)
class ContextStorageProcessorTest {

    private static final String PREFIX = INTERNAL_PROPERTY_PREFIX + "contextStorage_";
    private static final String CONTEXT_SERVICE_ID = "context-service";
    private static final String CORRELATION_ID_VALUE = "abc";

    @Mock
    private ContextStorageService contextStorageService;
    private ContextStorageProcessor processor;
    private Exchange exchange;

    // The element template sets the context ID to an empty string when the element has none.
    @BeforeEach
    void setUp() {
        processor = new ContextStorageProcessor(contextStorageService);
        exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(PREFIX + "useCorrelationId", "true");
        exchange.setProperty(PREFIX + "contextServiceId", CONTEXT_SERVICE_ID);
        exchange.setProperty(PREFIX + "contextId", "");
    }

    @Test
    void setStoresUnderCorrelationIdWhenContextIdIsEmpty() throws Exception {
        exchange.setProperty(PREFIX + "operation", "SET");
        exchange.setProperty(PREFIX + "key", "k");
        exchange.setProperty(PREFIX + "value", "v");
        exchange.setProperty(PREFIX + "ttl", "600");

        processor.process(exchange);

        verify(contextStorageService).storeValue("k", "v", CONTEXT_SERVICE_ID, CORRELATION_ID_VALUE, 600L);
    }

    @Test
    void setStoresUnderEmptyContextIdWhenExchangeHasNoCorrelationId() throws Exception {
        exchange.removeProperty(CORRELATION_ID);
        exchange.setProperty(PREFIX + "operation", "SET");
        exchange.setProperty(PREFIX + "key", "k");
        exchange.setProperty(PREFIX + "value", "v");
        exchange.setProperty(PREFIX + "ttl", "600");

        processor.process(exchange);

        verify(contextStorageService).storeValue("k", "v", CONTEXT_SERVICE_ID, "", 600L);
    }

    @Test
    void getReadsUnderCorrelationIdWhenContextIdIsEmpty() throws Exception {
        exchange.setProperty(PREFIX + "operation", "GET");
        exchange.setProperty(PREFIX + "keys", "k");
        exchange.setProperty(PREFIX + "target", "BODY");
        exchange.setProperty(PREFIX + "unwrap", "false");
        when(contextStorageService.getValue(CONTEXT_SERVICE_ID, CORRELATION_ID_VALUE, List.of("k")))
                .thenReturn(Map.of("k", "v"));

        processor.process(exchange);

        assertEquals(Map.of("k", "v"), exchange.getMessage().getBody());
    }

    @Test
    void deleteRemovesCorrelationIdContextWhenContextIdIsEmpty() throws Exception {
        exchange.setProperty(PREFIX + "operation", "DELETE");

        processor.process(exchange);

        verify(contextStorageService).deleteValue(CONTEXT_SERVICE_ID, CORRELATION_ID_VALUE);
    }
}
