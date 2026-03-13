package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.MDCUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CorrelationIdSetterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDoNothingWhenCorrelationIdSettingsAreMissing() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();

        try (MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)) {
            setter.setCorrelationId(exchange);

            assertNull(exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldSetCorrelationIdFromHeaderAndPutItToMdc() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.HEADER);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "X-Correlation-Id");
        exchange.getMessage().setHeader("X-Correlation-Id", "corr-123");

        try (MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)) {
            setter.setCorrelationId(exchange);

            assertEquals("corr-123", exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verify(() -> MDCUtil.setCorrelationId("corr-123"));
        }
    }

    @Test
    void shouldSetNullCorrelationIdWhenHeaderMissing() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.HEADER);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "X-Correlation-Id");

        try (MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)) {
            setter.setCorrelationId(exchange);

            assertNull(exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldNotPutCorrelationIdToMdcWhenHeaderValueIsBlank() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.HEADER);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "X-Correlation-Id");
        exchange.getMessage().setHeader("X-Correlation-Id", "   ");

        try (MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)) {
            setter.setCorrelationId(exchange);

            assertEquals("   ", exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldSetCorrelationIdFromBodyAndPutItToMdc() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.BODY);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "correlationId");

        try (
                MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class);
                MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)
        ) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("{\"correlationId\":\"body-corr-123\"}");

            setter.setCorrelationId(exchange);

            assertEquals("body-corr-123", exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verify(() -> MDCUtil.setCorrelationId("body-corr-123"));
        }
    }

    @Test
    void shouldNotSetCorrelationIdWhenBodyDoesNotContainConfiguredField() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.BODY);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "correlationId");

        try (
                MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class);
                MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)
        ) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("{\"anotherField\":\"value\"}");

            setter.setCorrelationId(exchange);

            assertNull(exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldNotPutCorrelationIdToMdcWhenBodyValueIsBlank() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.BODY);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "correlationId");

        try (
                MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class);
                MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)
        ) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("{\"correlationId\":\"   \"}");

            setter.setCorrelationId(exchange);

            assertEquals("   ", exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldIgnoreBodyParsingErrors() {
        CorrelationIdSetter setter = new CorrelationIdSetter(objectMapper);
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_POSITION, CorrelationIdSetter.BODY);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID_NAME, "correlationId");

        try (
                MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class);
                MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)
        ) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("{invalid-json}");

            setter.setCorrelationId(exchange);

            assertNull(exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }
}
