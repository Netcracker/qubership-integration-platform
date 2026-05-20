package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.NamedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpTriggerFinishProcessorTest {

    private static final String HTTP_TRIGGER_NODE_ID = "8d4f5d1e-9a6b-4f39-91d8-3d2f6fbb2101";

    private HttpTriggerFinishProcessor processor;

    private final ChainInfo chainInfo = ChainInfo.builder()
            .id("test-chain-id")
            .name("Test chain")
            .build();

    @Mock
    ChainRuntimePropertiesService propertiesService;
    @Mock
    ChainLogger chainLogger;
    @Mock
    MetricsService metricsService;
    @Mock
    ChainRuntimeProperties runtimeProperties;

    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = mock(Exchange.class);
        processor = new HttpTriggerFinishProcessor(
                propertiesService,
                chainLogger,
                metricsService
        );
    }

    @Test
    void shouldSkipHttpTriggerRequestLoggingWhenLogLevelNotInfoAndSessionNotFailed() throws Exception {
        when(exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class))
                .thenReturn(false);

        mockDebuggerProperties(exchange, LogLoggingLevel.WARN);

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class);
             MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(DeploymentInfo.builder().chain(chainInfo).build());

            processor.process(exchange);
        }

        verifyNoInteractions(chainLogger);
        verify(metricsService).processHttpResponseCode(chainInfo, "200");
        verify(metricsService).processHttpTriggerPayloadSize(exchange);
    }

    @Test
    void shouldLogHttpTriggerRequestFinishedWithSelectedLogPayloadAndNodeIdWhenInfoLevel() throws Exception {
        MessageHistory messageHistory = mock(MessageHistory.class);
        NamedNode triggerNode = mock(NamedNode.class);

        when(exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class))
                .thenReturn(false);
        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class))
                .thenReturn(System.currentTimeMillis() - 1000);
        when(exchange.getAllProperties())
                .thenReturn(Map.of(Exchange.MESSAGE_HISTORY, List.of(messageHistory)));

        when(messageHistory.getNode()).thenReturn(triggerNode);
        when(triggerNode.getLabel()).thenReturn("ref:httpTriggerProcessor");
        when(triggerNode.getId()).thenReturn(HTTP_TRIGGER_NODE_ID);

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class);
             MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(DeploymentInfo.builder().chain(chainInfo).build());

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(HTTP_TRIGGER_NODE_ID),
                anyLong(),
                eq(null)
        );
        verify(metricsService).processHttpResponseCode(chainInfo, "200");
        verify(metricsService).processHttpTriggerPayloadSize(exchange);
    }

    @Test
    void shouldLogHttpTriggerRequestFinishedWithLegacyPayloadWhenSessionFailedAndLogLevelNotInfo() throws Exception {
        Exception exception = new IllegalStateException("HTTP trigger failed");

        when(exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class))
                .thenReturn(true);
        when(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class))
                .thenReturn(exception);
        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class))
                .thenReturn(System.currentTimeMillis() - 1000);
        when(exchange.getAllProperties()).thenReturn(Collections.emptyMap());

        mockDebuggerProperties(exchange, LogLoggingLevel.WARN);

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class);
             MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, exception))
                    .thenReturn(500);
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(DeploymentInfo.builder().chain(chainInfo).build());

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(null),
                anyLong(),
                eq(exception)
        );
        verify(metricsService).processHttpResponseCode(chainInfo, "500");
        verify(metricsService).processHttpTriggerPayloadSize(exchange);
    }

    @Test
    void shouldUseNullNodeIdWhenHttpTriggerProcessorNotFoundInMessageHistory() throws Exception {
        MessageHistory messageHistory = mock(MessageHistory.class);
        NamedNode anotherNode = mock(NamedNode.class);

        when(exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class))
                .thenReturn(false);
        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class))
                .thenReturn(System.currentTimeMillis() - 1000);
        when(exchange.getAllProperties())
                .thenReturn(Map.of(Exchange.MESSAGE_HISTORY, List.of(messageHistory)));

        when(messageHistory.getNode()).thenReturn(anotherNode);
        when(anotherNode.getLabel()).thenReturn("ref:anotherProcessor");

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class);
             MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(DeploymentInfo.builder().chain(chainInfo).build());

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(null),
                anyLong(),
                eq(null)
        );
    }

    @Test
    void shouldContinueWhenMetricsProcessingFails() throws Exception {
        when(exchange.getProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false, Boolean.class))
                .thenReturn(false);
        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class))
                .thenReturn(System.currentTimeMillis() - 1000);
        when(exchange.getAllProperties()).thenReturn(Collections.emptyMap());

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);

        doThrow(new RuntimeException("metrics failed"))
                .when(metricsService)
                .processHttpResponseCode(chainInfo, "500");

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class);
             MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(500);
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(DeploymentInfo.builder().chain(chainInfo).build());

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(null),
                anyLong(),
                eq(null)
        );
        verify(metricsService).processHttpResponseCode(chainInfo, "500");
        verify(metricsService, never()).processHttpTriggerPayloadSize(exchange);
    }

    private void mockDebuggerProperties(Exchange exchange, LogLoggingLevel logLevel) {
        when(propertiesService.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(logLevel);
    }
}
