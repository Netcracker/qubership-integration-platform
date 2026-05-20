package org.qubership.integration.platform.engine.camel.processors.session;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SessionWarningStatusProcessorTest {

    private final SessionWarningStatusProcessor processor = new SessionWarningStatusProcessor();

    @Test
    void shouldSetOverallWarningToTrue() throws Exception {
        Exchange exchange = MockExchanges.basic();

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            processor.process(exchange);

            debuggerUtils.verify(() -> DebuggerUtils.setOverallWarning(exchange, true));
        }
    }
}
