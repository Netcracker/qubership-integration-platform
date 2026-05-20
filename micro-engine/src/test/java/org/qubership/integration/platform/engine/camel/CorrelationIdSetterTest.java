package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.util.MDCUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CorrelationIdSetterTest {

    CorrelationIdSetter setter;

    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        setter = new CorrelationIdSetter(objectMapper);
        exchange = MockExchanges.defaultExchange();
    }


    @Test
    void shouldDoNothingWhenCorrelationIdSettingsAreMissing() {
        try (MockedStatic<MDCUtil> mdcUtilMock = mockStatic(MDCUtil.class)) {
            setter.setCorrelationId(exchange);

            assertNull(exchange.getProperty(CorrelationIdSetter.CORRELATION_ID));
            mdcUtilMock.verifyNoInteractions();
        }
    }

    @Test
    void shouldSetCorrelationIdFromHeaderAndPutItToMdc() {
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
