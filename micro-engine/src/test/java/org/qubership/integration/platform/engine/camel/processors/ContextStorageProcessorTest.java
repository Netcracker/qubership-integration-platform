package org.qubership.integration.platform.engine.camel.processors;

import io.quarkus.test.Mock;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.contextstorage.ContextStorageService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextStorageProcessorTest {

    private static final String CORRELATION_ID_VALUE = "e0f6d4f9-5b7e-4c61-9d62-3d9a1a44a101";
    private static final String CONTEXT_ID_VALUE = "4f7ad2c8-1b31-4f21-b5db-2aa18f18b202";
    private static final String CONTEXT_SERVICE_ID_VALUE = "8bbff8d1-59f7-447a-b7f2-98ffaf216303";
    private static final String ANOTHER_CONTEXT_SERVICE_ID_VALUE = "3e465f4b-6f49-4d92-95cd-c54a6b69f404";
    private static final String TARGET_NAME = "storedContext";
    private static final String CONTEXT_KEY = "customerId";
    private static final String CONTEXT_VALUE = "C-100500";

    private static final String PROPERTY_USE_CORRELATION_ID = getStaticString("PROPERTY_USE_CORRELATION_ID");
    private static final String PROPERTY_CONTEXT_ID = getStaticString("PROPERTY_CONTEXT_ID");
    private static final String PROPERTY_CONTEXT_SERVICE_ID = getStaticString("PROPERTY_CONTEXT_SERVICE_ID");
    private static final String PROPERTY_OPERATION = getStaticString("PROPERTY_OPERATION");
    private static final String PROPERTY_KEY = getStaticString("PROPERTY_KEY");
    private static final String PROPERTY_VALUE = getStaticString("PROPERTY_VALUE");
    private static final String PROPERTY_TTL = getStaticString("PROPERTY_TTL");
    private static final String PROPERTY_KEYS = getStaticString("PROPERTY_KEYS");
    private static final String PROPERTY_TARGET = getStaticString("PROPERTY_TARGET");
    private static final String PROPERTY_TARGET_NAME = getStaticString("PROPERTY_TARGET_NAME");
    private static final String PROPERTY_UNWRAP = getStaticString("PROPERTY_UNWRAP");

    private final ContextStorageService contextStorageService = mock(ContextStorageService.class);
    private final ContextStorageProcessor processor = new ContextStorageProcessor(contextStorageService);


    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
    }

    @Test
    void shouldGetWrappedValueToBodyWhenOperationGetAndTargetBody() throws Exception {
        Map<String, String> storedValues = new LinkedHashMap<>();
        storedValues.put("customerId", "C-100500");
        storedValues.put("orderId", "O-42");

        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, true);
        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_KEYS, "customerId,orderId");
        exchange.setProperty(PROPERTY_OPERATION, "get");
        exchange.setProperty(PROPERTY_TARGET, "body");
        exchange.setProperty(PROPERTY_UNWRAP, false);

        when(contextStorageService.getValue(
                CONTEXT_SERVICE_ID_VALUE,
                CORRELATION_ID_VALUE,
                List.of("customerId", "orderId")
        )).thenReturn(storedValues);

        processor.process(exchange);

        assertEquals(storedValues, exchange.getMessage().getBody());
    }

    @Test
    void shouldGetUnwrappedValueToHeaderWhenOperationGetAndTargetHeader() throws Exception {
        Map<String, String> storedValues = new LinkedHashMap<>();
        storedValues.put("customerId", "C-100500");

        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, false);
        exchange.setProperty(PROPERTY_CONTEXT_ID, CONTEXT_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_KEYS, "customerId");
        exchange.setProperty(PROPERTY_OPERATION, "GET");
        exchange.setProperty(PROPERTY_TARGET, "HEADER");
        exchange.setProperty(PROPERTY_TARGET_NAME, TARGET_NAME);
        exchange.setProperty(PROPERTY_UNWRAP, true);

        when(contextStorageService.getValue(
                CONTEXT_SERVICE_ID_VALUE,
                CONTEXT_ID_VALUE,
                List.of("customerId")
        )).thenReturn(storedValues);

        processor.process(exchange);

        assertEquals("C-100500", exchange.getMessage().getHeader(TARGET_NAME));
    }

    @Test
    void shouldGetWrappedValueToPropertyWhenOperationGetAndTargetProperty() throws Exception {
        Map<String, String> storedValues = Map.of("customerId", "C-100500");

        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, true);
        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_KEYS, "customerId");
        exchange.setProperty(PROPERTY_OPERATION, "GET");
        exchange.setProperty(PROPERTY_TARGET, "PROPERTY");
        exchange.setProperty(PROPERTY_TARGET_NAME, TARGET_NAME);
        exchange.setProperty(PROPERTY_UNWRAP, false);

        when(contextStorageService.getValue(
                CONTEXT_SERVICE_ID_VALUE,
                CORRELATION_ID_VALUE,
                List.of("customerId")
        )).thenReturn(storedValues);

        processor.process(exchange);

        assertEquals(storedValues, exchange.getProperty(TARGET_NAME));
    }

    @Test
    void shouldUseExplicitContextIdForGetWhenProvided() throws Exception {
        Map<String, String> storedValues = Map.of("customerId", "C-100500");

        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, true);
        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_ID, CONTEXT_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_KEYS, "customerId");
        exchange.setProperty(PROPERTY_OPERATION, "GET");
        exchange.setProperty(PROPERTY_TARGET, "BODY");
        exchange.setProperty(PROPERTY_UNWRAP, false);

        when(contextStorageService.getValue(
                CONTEXT_SERVICE_ID_VALUE,
                CONTEXT_ID_VALUE,
                List.of("customerId")
        )).thenReturn(storedValues);

        processor.process(exchange);

        assertEquals(storedValues, exchange.getMessage().getBody());
    }

    @Test
    void shouldStoreValueWhenOperationSetAndContextIdFallsBackToCorrelationId() throws Exception {
        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, true);
        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_OPERATION, "SET");
        exchange.setProperty(PROPERTY_KEY, CONTEXT_KEY);
        exchange.setProperty(PROPERTY_VALUE, CONTEXT_VALUE);
        exchange.setProperty(PROPERTY_TTL, 300L);

        processor.process(exchange);

        verify(contextStorageService).storeValue(
                CONTEXT_KEY,
                CONTEXT_VALUE,
                CONTEXT_SERVICE_ID_VALUE,
                CORRELATION_ID_VALUE,
                300L
        );
    }

    @Test
    void shouldDeleteContextWhenOperationDeleteAndExplicitContextIdProvided() throws Exception {
        exchange.setProperty(PROPERTY_USE_CORRELATION_ID, false);
        exchange.setProperty(PROPERTY_CONTEXT_ID, CONTEXT_ID_VALUE);
        exchange.setProperty(PROPERTY_CONTEXT_SERVICE_ID, ANOTHER_CONTEXT_SERVICE_ID_VALUE);
        exchange.setProperty(PROPERTY_OPERATION, "DELETE");

        processor.process(exchange);

        verify(contextStorageService).deleteValue(
                ANOTHER_CONTEXT_SERVICE_ID_VALUE,
                CONTEXT_ID_VALUE
        );
    }

    private static String getStaticString(String fieldName) {
        try {
            Field field = ContextStorageProcessor.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access field: " + fieldName, e);
        }
    }
}
