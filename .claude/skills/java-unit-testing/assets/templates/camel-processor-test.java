package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SplitAsyncProcessorTest {

    private final SplitAsyncProcessor processor = new SplitAsyncProcessor();

    @Test
    void shouldMarkExchangeAsNotMainAndRemoveChainTimeoutAfter() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, true);
        exchange.setProperty(Properties.CHAIN_TIME_OUT_AFTER, 30000L);

        processor.process(exchange);

        assertEquals(false, exchange.getProperty(Properties.IS_MAIN_EXCHANGE, Boolean.class));
        assertNull(exchange.getProperty(Properties.CHAIN_TIME_OUT_AFTER));
    }
}
