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
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpTriggerFinishProcessorTest {

    private static final String HTTP_TRIGGER_NODE_ID = "8d4f5d1e-9a6b-4f39-91d8-3d2f6fbb2101";

    private HttpTriggerFinishProcessor processor;

    @Mock
    CamelDebuggerPropertiesService propertiesService;
    @Mock
    PayloadExtractor payloadExtractor;
    @Mock
    ChainLogger chainLogger;
    @Mock
    MetricsService metricsService;
    @Mock
    CamelDebuggerProperties dbgProperties;
    @Mock
    DeploymentRuntimeProperties runtimeProperties;
    @Mock
    Exchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        processor = new HttpTriggerFinishProcessor(
                propertiesService,
                payloadExtractor,
                chainLogger,
                metricsService
        );
    }

    @Test
    void shouldSkipHttpTriggerRequestLoggingWhenLogLevelNotInfoAndSessionNotFailed() throws Exception {
        exchange.setProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false);

        mockDebuggerProperties(exchange, LogLoggingLevel.WARN);

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);

            processor.process(exchange);
        }

        verifyNoInteractions(chainLogger, payloadExtractor);
        verify(metricsService).processHttpResponseCode(dbgProperties, "200");
        verify(metricsService).processHttpTriggerPayloadSize(exchange, dbgProperties);
    }

    @Test
    void shouldLogHttpTriggerRequestFinishedWithSelectedLogPayloadAndNodeIdWhenInfoLevel() throws Exception {
        MessageHistory messageHistory = mock(MessageHistory.class);
        NamedNode triggerNode = mock(NamedNode.class);

        exchange.setProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME_MS, System.currentTimeMillis() - 1000);
        exchange.setProperty(Exchange.MESSAGE_HISTORY, List.of(messageHistory));

        when(messageHistory.getNode()).thenReturn(triggerNode);
        when(triggerNode.getLabel()).thenReturn("ref:httpTriggerProcessor");
        when(triggerNode.getId()).thenReturn(HTTP_TRIGGER_NODE_ID);

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);
        mockMaskingEnabled(exchange, false);

        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY));
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());
        when(payloadExtractor.extractBodyForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn("response-body");

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(dbgProperties),
                eq("response-body"),
                eq("<headers not logged>"),
                eq("<properties not logged>"),
                eq(HTTP_TRIGGER_NODE_ID),
                anyLong(),
                eq(null)
        );
        verify(metricsService).processHttpResponseCode(dbgProperties, "200");
        verify(metricsService).processHttpTriggerPayloadSize(exchange, dbgProperties);
    }

    @Test
    void shouldLogHttpTriggerRequestFinishedWithLegacyPayloadWhenSessionFailedAndLogLevelNotInfo() throws Exception {
        Exception exception = new IllegalStateException("HTTP trigger failed");

        exchange.setProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, true);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, exception);
        exchange.setProperty(CamelConstants.Properties.START_TIME_MS, System.currentTimeMillis() - 1000);

        mockDebuggerProperties(exchange, LogLoggingLevel.WARN);
        mockMaskingEnabled(exchange, false);

        when(runtimeProperties.isLogPayloadEnabled()).thenReturn(true);
        when(runtimeProperties.getLogPayload()).thenReturn(null);
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());
        when(payloadExtractor.extractBodyForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn("legacy-body");

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, exception))
                    .thenReturn(500);

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(dbgProperties),
                eq("legacy-body"),
                eq("{header=value}"),
                eq("{}"),
                eq(null),
                anyLong(),
                eq(exception)
        );
        verify(metricsService).processHttpResponseCode(dbgProperties, "500");
        verify(metricsService).processHttpTriggerPayloadSize(exchange, dbgProperties);
    }

    @Test
    void shouldUseNullNodeIdWhenHttpTriggerProcessorNotFoundInMessageHistory() throws Exception {
        MessageHistory messageHistory = mock(MessageHistory.class);
        NamedNode anotherNode = mock(NamedNode.class);

        exchange.setProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME_MS, System.currentTimeMillis() - 1000);
        exchange.setProperty(Exchange.MESSAGE_HISTORY, List.of(messageHistory));

        when(messageHistory.getNode()).thenReturn(anotherNode);
        when(anotherNode.getLabel()).thenReturn("ref:anotherProcessor");

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);
        mockMaskingEnabled(exchange, false);

        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY));
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());
        when(payloadExtractor.extractBodyForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn("response-body");

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(200);

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(dbgProperties),
                eq("response-body"),
                eq("<headers not logged>"),
                eq("<properties not logged>"),
                eq(null),
                anyLong(),
                eq(null)
        );
    }

    @Test
    void shouldContinueWhenMetricsProcessingFails() throws Exception {
        exchange.setProperty(CamelConstants.Properties.HTTP_TRIGGER_CHAIN_FAILED, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME_MS, System.currentTimeMillis() - 1000);

        mockDebuggerProperties(exchange, LogLoggingLevel.INFO);
        mockMaskingEnabled(exchange, false);

        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.BODY));
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());
        when(payloadExtractor.extractBodyForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn("response-body");

        doThrow(new RuntimeException("metrics failed"))
                .when(metricsService)
                .processHttpResponseCode(dbgProperties, "500");

        try (MockedStatic<PayloadExtractor> payloadExtractorStatic = mockStatic(PayloadExtractor.class)) {
            payloadExtractorStatic.when(() -> PayloadExtractor.getServletResponseCode(exchange, null))
                    .thenReturn(500);

            processor.process(exchange);
        }

        verify(chainLogger).logHTTPExchangeFinished(
                eq(exchange),
                eq(dbgProperties),
                eq("response-body"),
                eq("<headers not logged>"),
                eq("<properties not logged>"),
                eq(null),
                anyLong(),
                eq(null)
        );
        verify(metricsService).processHttpResponseCode(dbgProperties, "500");
        verify(metricsService, never()).processHttpTriggerPayloadSize(exchange, dbgProperties);
    }

    private void mockDebuggerProperties(Exchange exchange, LogLoggingLevel logLevel) {
        when(propertiesService.getProperties(exchange)).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(logLevel);
    }

    private void mockMaskingEnabled(Exchange exchange, boolean maskingEnabled) {
        when(runtimeProperties.isMaskingEnabled()).thenReturn(maskingEnabled);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
    }
}
