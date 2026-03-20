package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ThrowCaughtExceptionProcessorTest {

    private final ThrowCaughtExceptionProcessor processor = new ThrowCaughtExceptionProcessor();

    @Test
    void shouldThrowCaughtExceptionWhenExceptionPresent() {
        Exchange exchange = MockExchanges.defaultExchange();
        Exception exception = new IllegalStateException("boom");

        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, exception);

        Exception thrown = assertThrows(
                Exception.class,
                () -> processor.process(exchange)
        );

        assertSame(exception, thrown);
    }

    @Test
    void shouldDoNothingWhenCaughtExceptionAbsent() {
        Exchange exchange = MockExchanges.defaultExchange();

        assertDoesNotThrow(() -> processor.process(exchange));
    }
}
