package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogRecordProcessorTest {

    private static final String DEPLOYMENT_ID = "deployment-id";
    private static final String PROPERTY_PREFIX = CamelConstants.INTERNAL_PROPERTY_PREFIX + "logRecord_";
    private static final String MESSAGE = "Customer profile synchronized";

    @Mock
    private ChainLogger chainLogger;
    @Mock
    private SimpleLanguage simpleInterpreter;
    @Mock
    private CamelDebuggerPropertiesService propertiesService;
    @Mock
    private CamelDebuggerProperties debuggerProperties;
    @Mock
    private CamelDebugger camelDebugger;

    @ParameterizedTest
    @CsvSource({
            "Warning, INFO, true",
            "Warning, ERROR, false",
            "Info, INFO, true",
            "Info, ERROR, false"
    })
    void logsRecordOnlyWhenChainLogLevelEnablesIt(String recordLevel, LogLoggingLevel chainLogLevel, boolean logged)
            throws Exception {
        process(recordLevel, chainLogLevel);

        if (logged && "Warning".equals(recordLevel)) {
            verify(chainLogger).warn(MESSAGE);
        } else if (logged) {
            verify(chainLogger).info(MESSAGE);
        }
        verifyNoMoreInteractions(chainLogger);
    }

    @Test
    void logsErrorRecordWhenChainLogLevelIsError() throws Exception {
        process("Error", LogLoggingLevel.ERROR);

        verify(chainLogger).error(MESSAGE);
    }

    private void process(String recordLevel, LogLoggingLevel chainLogLevel) throws Exception {
        DefaultCamelContext camelContext = new DefaultCamelContext();
        camelContext.setDebugger(camelDebugger);
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROPERTY_PREFIX + "logLevel", recordLevel);
        exchange.setProperty(PROPERTY_PREFIX + "message", MESSAGE);

        when(camelDebugger.getDeploymentId()).thenReturn(DEPLOYMENT_ID);
        when(propertiesService.getProperties(exchange, DEPLOYMENT_ID)).thenReturn(debuggerProperties);
        when(debuggerProperties.getRuntimeProperties(exchange))
                .thenReturn(DeploymentRuntimeProperties.builder().logLoggingLevel(chainLogLevel).build());

        new LogRecordProcessor(chainLogger, simpleInterpreter, propertiesService).process(exchange);
    }
}
