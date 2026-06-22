package org.qubership.integration.platform.engine.interceptors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.logging.ContextHeaders;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RequestIdHelperTest {

    private static final String REQUEST_ID = "6313614f-d3aa-4cae-b3b6-9b276d164cff";

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldPutProvidedRequestIdToMdc() {
        RequestIdHelper.setRequestId(REQUEST_ID);

        assertEquals(REQUEST_ID, MDC.get(ContextHeaders.REQUEST_ID));
    }

    @Test
    void shouldGenerateRequestIdWhenProvidedRequestIdIsNull() {
        RequestIdHelper.setRequestId(null);

        String result = MDC.get(ContextHeaders.REQUEST_ID);

        assertNotNull(result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    void shouldReplaceExistingRequestIdWhenProvidedRequestIdIsNotNull() {
        MDC.put(ContextHeaders.REQUEST_ID, "88796b76-ce99-46cf-adab-c17b51fbc0f1");

        RequestIdHelper.setRequestId(REQUEST_ID);

        assertEquals(REQUEST_ID, MDC.get(ContextHeaders.REQUEST_ID));
    }

    @Test
    void shouldReplaceExistingRequestIdWithGeneratedOneWhenProvidedRequestIdIsNull() {
        MDC.put(ContextHeaders.REQUEST_ID, "88796b76-ce99-46cf-adab-c17b51fbc0f1");

        RequestIdHelper.setRequestId(null);

        String result = MDC.get(ContextHeaders.REQUEST_ID);

        assertNotNull(result);
        assertNotEquals("88796b76-ce99-46cf-adab-c17b51fbc0f1", result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    void shouldNotPropagateExceptionWhenMdcPutFails() {
        RuntimeException exception = new RuntimeException("MDC failed");

        try (MockedStatic<MDC> mdc = Mockito.mockStatic(MDC.class)) {
            mdc.when(() -> MDC.put(ContextHeaders.REQUEST_ID, REQUEST_ID)).thenThrow(exception);

            assertDoesNotThrow(() -> RequestIdHelper.setRequestId(REQUEST_ID));

            mdc.verify(() -> MDC.put(ContextHeaders.REQUEST_ID, REQUEST_ID));
        }
    }
}
