package org.qubership.integration.platform.engine.camel.processors.session;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainStartProcessorTest {

    private final ChainStartProcessor processor = new ChainStartProcessor();

    @Test
    void shouldInitializeSessionPropertiesWhenTheyAreAbsent() throws Exception {
        Exchange exchange = MockExchanges.basic();

        processor.process(exchange);

        AtomicInteger activeThreadCounter = exchange.getProperty(
                Properties.SESSION_ACTIVE_THREAD_COUNTER,
                AtomicInteger.class
        );
        AtomicBoolean overallStatusWarning = exchange.getProperty(
                Properties.OVERALL_STATUS_WARNING,
                AtomicBoolean.class
        );

        assertEquals(1, activeThreadCounter.get());
        assertFalse(overallStatusWarning.get());
    }

    @Test
    void shouldNotOverrideSessionPropertiesWhenTheyAlreadyExist() throws Exception {
        Exchange exchange = MockExchanges.basic();
        AtomicInteger activeThreadCounter = new AtomicInteger(5);
        AtomicBoolean overallStatusWarning = new AtomicBoolean(true);

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, activeThreadCounter);
        exchange.setProperty(Properties.OVERALL_STATUS_WARNING, overallStatusWarning);

        processor.process(exchange);

        assertSame(
                activeThreadCounter,
                exchange.getProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, AtomicInteger.class)
        );
        assertSame(
                overallStatusWarning,
                exchange.getProperty(Properties.OVERALL_STATUS_WARNING, AtomicBoolean.class)
        );
        assertEquals(5, activeThreadCounter.get());
        assertTrue(overallStatusWarning.get());
    }
}
