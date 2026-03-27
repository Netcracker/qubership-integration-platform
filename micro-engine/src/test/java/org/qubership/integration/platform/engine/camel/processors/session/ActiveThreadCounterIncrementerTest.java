package org.qubership.integration.platform.engine.camel.processors.session;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ActiveThreadCounterIncrementerTest {

    private final ActiveThreadCounterIncrementer processor = new ActiveThreadCounterIncrementer();

    @Test
    void shouldIncrementActiveThreadCounterWhenPropertyExists() throws Exception {
        Exchange exchange = MockExchanges.basic();
        AtomicInteger activeThreadCounter = new AtomicInteger(2);

        when(exchange.getProperty(
                Properties.SESSION_ACTIVE_THREAD_COUNTER,
                null,
                AtomicInteger.class
        )).thenReturn(activeThreadCounter);

        processor.process(exchange);

        assertEquals(3, activeThreadCounter.get());
    }
}
