package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.language.simple.SimpleLanguage;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.MDCUtil;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LogRecordProcessorTest {

    private static final String PROPERTY_LOG_LEVEL = getStaticString("PROPERTY_LOG_LEVEL");
    private static final String PROPERTY_SENDER = getStaticString("PROPERTY_SENDER");
    private static final String PROPERTY_RECEIVER = getStaticString("PROPERTY_RECEIVER");
    private static final String PROPERTY_BUSINESS_IDENTIFIERS = getStaticString("PROPERTY_BUSINESS_IDENTIFIERS");
    private static final String PROPERTY_MESSAGE = getStaticString("PROPERTY_MESSAGE");

    private final ChainLogger chainLogger = mock(ChainLogger.class);
    private final SimpleLanguage simpleInterpreter = mock(SimpleLanguage.class);
    private final LogRecordProcessor processor = new LogRecordProcessor(chainLogger, simpleInterpreter);

    @Test
    void shouldLogErrorRecordWithSenderReceiverAndEvaluatedBusinessIdentifiers() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Expression customerExpression = mock(Expression.class);
        Expression orderExpression = mock(Expression.class);

        Map<Object, Object> businessIdentifiers = new LinkedHashMap<>();
        businessIdentifiers.put("customerId", "${exchangeProperty.customerId}");
        businessIdentifiers.put("orderId", "${exchangeProperty.orderId}");

        exchange.setProperty(PROPERTY_LOG_LEVEL, "ERROR");
        exchange.setProperty(PROPERTY_SENDER, "Customer API");
        exchange.setProperty(PROPERTY_RECEIVER, "Billing");
        exchange.setProperty(PROPERTY_MESSAGE, "Customer billing synchronization failed");
        exchange.setProperty(PROPERTY_BUSINESS_IDENTIFIERS, businessIdentifiers);

        when(simpleInterpreter.createExpression("${exchangeProperty.customerId}")).thenReturn(customerExpression);
        when(simpleInterpreter.createExpression("${exchangeProperty.orderId}")).thenReturn(orderExpression);
        when(customerExpression.evaluate(exchange, String.class)).thenReturn("C-100500");
        when(orderExpression.evaluate(exchange, String.class)).thenReturn("O-456");

        try (MockedStatic<MDCUtil> mdcUtil = mockStatic(MDCUtil.class)) {
            processor.process(exchange);

            Map<Object, Object> expectedBusinessIdentifiers = new LinkedHashMap<>();
            expectedBusinessIdentifiers.put("customerId", "C-100500");
            expectedBusinessIdentifiers.put("orderId", "O-456");

            mdcUtil.verify(() -> MDCUtil.setBusinessIds(expectedBusinessIdentifiers));
        }

        verify(chainLogger).error(
                "[sender=Customer API    ] [receiver=Billing         ] Customer billing synchronization failed"
        );
    }

    @Test
    void shouldLogWarningRecordWhenDefaultLogLevelAllowsWarn() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(PROPERTY_LOG_LEVEL, "WARNING");
        exchange.setProperty(PROPERTY_SENDER, "Customer API");
        exchange.setProperty(PROPERTY_RECEIVER, "Billing");
        exchange.setProperty(PROPERTY_MESSAGE, "Customer profile is missing optional fields");

        processor.process(exchange);

        verify(chainLogger).warn(
                "[sender=Customer API    ] [receiver=Billing         ] Customer profile is missing optional fields"
        );
        verifyNoInteractions(simpleInterpreter);
    }

    @Test
    void shouldNotLogInfoRecordWhenDefaultLogLevelDoesNotAllowInfo() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(PROPERTY_LOG_LEVEL, "INFO");
        exchange.setProperty(PROPERTY_SENDER, "Customer API");
        exchange.setProperty(PROPERTY_RECEIVER, "Billing");
        exchange.setProperty(PROPERTY_MESSAGE, "Customer profile synchronized");

        processor.process(exchange);

        verifyNoInteractions(chainLogger, simpleInterpreter);
    }

    @Test
    void shouldLogMessageOnlyWhenSenderAndReceiverMissing() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(PROPERTY_LOG_LEVEL, "ERROR");
        exchange.setProperty(PROPERTY_MESSAGE, "Standalone error record");

        processor.process(exchange);

        verify(chainLogger).error("Standalone error record");
        verifyNoInteractions(simpleInterpreter);
    }

    @Test
    void shouldNotSetBusinessIdentifiersWhenIdentifiersMapEmpty() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(PROPERTY_LOG_LEVEL, "ERROR");
        exchange.setProperty(PROPERTY_MESSAGE, "No business ids available");
        exchange.setProperty(PROPERTY_BUSINESS_IDENTIFIERS, new LinkedHashMap<>());

        try (MockedStatic<MDCUtil> mdcUtil = mockStatic(MDCUtil.class)) {
            processor.process(exchange);

            mdcUtil.verifyNoInteractions();
        }

        verify(chainLogger).error("No business ids available");
        verifyNoInteractions(simpleInterpreter);
    }

    private static String getStaticString(String fieldName) {
        try {
            Field field = LogRecordProcessor.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access field: " + fieldName, e);
        }
    }
}
