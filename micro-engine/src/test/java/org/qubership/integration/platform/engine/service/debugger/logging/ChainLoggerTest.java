/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.debugger.logging;

import jakarta.enterprise.inject.Instance;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.ElementRetryProperties;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainLoggerTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void updateMDCPropertyShouldPutOrRemove() {
        ChainLogger.updateMDCProperty("k", "v");
        assertEquals("v", MDC.get("k"));

        ChainLogger.updateMDCProperty("k", null);
        assertNull(MDC.get("k"));
    }

    @Test
    void setLoggerContextShouldFillMdcAndTraceSpanWhenTracingEnabled() {
        TracingService tracingService = mock(TracingService.class);
        when(tracingService.isTracingEnabled()).thenReturn(true);

        OriginatingBusinessIdProvider businessIdProvider = mock(OriginatingBusinessIdProvider.class);
        when(businessIdProvider.getOriginatingBusinessId()).thenReturn("BIZ-1");

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> dbgUtils = mockStatic(DebuggerUtils.class);
             MockedStatic<ActiveSpanManager> spanMgr = mockStatic(ActiveSpanManager.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance))
                    .thenReturn(Optional.of(businessIdProvider));

            ChainLogger logger = new ChainLogger(tracingService, instance);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SESSION_ID)).thenReturn("S-1");

            CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
            var depInfo = mock(DeploymentInfo.class);
            when(dbg.getDeploymentInfo()).thenReturn(depInfo);
            when(depInfo.getChainId()).thenReturn("C-1");
            when(depInfo.getChainName()).thenReturn("ChainName");

            dbgUtils.when(() -> DebuggerUtils.getNodeIdFormatted("node-1"))
                    .thenReturn("node-1");

            when(dbg.getElementProperty("node-1")).thenReturn(Map.of(
                    CamelConstants.ChainProperties.ELEMENT_NAME, "ElName",
                    CamelConstants.ChainProperties.ELEMENT_ID, "ElId"
            ));

            SpanAdapter span = mock(SpanAdapter.class);
            when(span.traceId()).thenReturn("T-1");
            when(span.spanId()).thenReturn("SP-1");
            spanMgr.when(() -> ActiveSpanManager.getSpan(exchange)).thenReturn(span);

            logger.setLoggerContext(exchange, dbg, "node-1", true);

            assertEquals("C-1", MDC.get(CamelConstants.ChainProperties.CHAIN_ID));
            assertEquals("ChainName", MDC.get(CamelConstants.ChainProperties.CHAIN_NAME));
            assertEquals("S-1", MDC.get(Properties.SESSION_ID));
            assertEquals("ElId", MDC.get(CamelConstants.ChainProperties.ELEMENT_ID));
            assertEquals("ElName", MDC.get(CamelConstants.ChainProperties.ELEMENT_NAME));
            assertEquals("BIZ-1", MDC.get(Headers.ORIGINATING_BUSINESS_ID));

            assertEquals("T-1", MDC.get(ChainLogger.MDC_TRACE_ID));
            assertEquals("SP-1", MDC.get(ChainLogger.MDC_SNAP_ID));
        }
    }

    @Test
    void setLoggerContextShouldClearTraceFieldsWhenTracingDisabledOrNoSpan() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<ActiveSpanManager> spanMgr = mockStatic(ActiveSpanManager.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());

            ChainLogger logger = new ChainLogger(tracingService, instance);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SESSION_ID)).thenReturn("S-1");

            CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
            var depInfo = mock(DeploymentInfo.class);
            when(dbg.getDeploymentInfo()).thenReturn(depInfo);
            when(depInfo.getChainId()).thenReturn("C-1");
            when(depInfo.getChainName()).thenReturn("ChainName");

            spanMgr.when(() -> ActiveSpanManager.getSpan(exchange)).thenReturn(null);

            logger.setLoggerContext(exchange, dbg, null, false);

            assertNull(MDC.get(ChainLogger.MDC_TRACE_ID));
            assertNull(MDC.get(ChainLogger.MDC_SNAP_ID));
        }
    }

    @Test
    void logRequestShouldCoverAllBranchesNoExceptions() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracingService, instance);

            Exchange exchange = mock(Exchange.class);
            Message msg = mock(Message.class);
            when(exchange.getMessage()).thenReturn(msg);

            Map<String, String> headers = Map.of("h", "v");
            Map<String, SessionElementProperty> props = Map.of();

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");
            logger.logRequest(exchange, "body", headers, props, null, null);

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);
            logger.logRequest(exchange, "body", headers, props, null, null);

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");
            logger.logRequest(exchange, "body", headers, props, "svc", "dev");

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);
            logger.logRequest(exchange, "body", headers, props, "svc", "dev");
        }
    }

    @Test
    void logRetryRequestAttemptShouldHitRetryBranchAndNumberFormatBranch() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<IdentifierUtils> ids = mockStatic(IdentifierUtils.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracingService, instance);

            ElementRetryProperties retryProps = mock(ElementRetryProperties.class);
            when(retryProps.retryCount()).thenReturn(3);
            when(retryProps.retryDelay()).thenReturn(1000);

            ids.when(() -> IdentifierUtils.getServiceCallRetryIteratorPropertyName("el"))
                    .thenReturn("itKey");
            ids.when(() -> IdentifierUtils.getServiceCallRetryPropertyName("el"))
                    .thenReturn("enKey");

            Exchange exchange = mock(Exchange.class);
            Map<String, Object> map = new HashMap<>();
            map.put("itKey", 1);
            map.put("enKey", "true");
            when(exchange.getProperties()).thenReturn(map);
            when(exchange.getProperty(eq(ExchangePropertyKey.EXCEPTION_CAUGHT), eq(Throwable.class)))
                    .thenReturn(new RuntimeException("boom"));

            logger.logRetryRequestAttempt(exchange, retryProps, "el");

            Exchange exchangeBad = mock(Exchange.class);
            Map<String, Object> mapBad = new HashMap<>();
            mapBad.put("itKey", "oops");
            mapBad.put("enKey", "true");
            when(exchangeBad.getProperties()).thenReturn(mapBad);

            logger.logRequestAttempt(exchangeBad, retryProps, "el");
        }
    }

    @Test
    void logHTTPExchangeFinishedShouldCoverSuccessAndFailureBranches() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<PayloadExtractor> payload = mockStatic(PayloadExtractor.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracingService, instance);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SERVLET_REQUEST_URL)).thenReturn("http://req");

            CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
            when(dbg.getElementProperty("n1")).thenReturn(Map.of(
                    CamelConstants.ChainProperties.ELEMENT_NAME, "N",
                    CamelConstants.ChainProperties.ELEMENT_ID, "ID"
            ));

            payload.when(() -> PayloadExtractor.getServletResponseCode(eq(exchange), isNull()))
                    .thenReturn(200);
            logger.logHTTPExchangeFinished(exchange, dbg, "b", "h", "p", "n1", 10, null);

            payload.when(() -> PayloadExtractor.getServletResponseCode(eq(exchange), notNull()))
                    .thenReturn(500);
            logger.logHTTPExchangeFinished(exchange, dbg, "b", "h", "p", "n1", 10, new RuntimeException("x"));
        }
    }

    @Test
    void wrappersDebugInfoWarnErrorShouldExecute() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            ChainLogger logger = new ChainLogger(tracing, inst);

            logger.debug("d {}", 1);
            logger.info("i {}", 1);
            logger.warn("w {}", 1);
            logger.error("e {}", 1);
        }
    }

    @Test
    void logExchangeFinishedShouldCoverBranchWithExecutionStatusProperty() {
        CamelDebuggerProperties debuggerProperties = mock(CamelDebuggerProperties.class);

        ExecutionStatus base = ExecutionStatus.values()[0];
        ExecutionStatus override = ExecutionStatus.values().length > 1 ? ExecutionStatus.values()[1] : base;

        when(debuggerProperties.containsElementProperty(CamelConstants.ChainProperties.EXECUTION_STATUS)).thenReturn(true);
        when(debuggerProperties.getElementProperty(CamelConstants.ChainProperties.EXECUTION_STATUS))
                .thenReturn(Map.of(CamelConstants.ChainProperties.EXECUTION_STATUS, override.name()));

        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracing, inst);

            logger.logExchangeFinished(debuggerProperties, "body", "headers", "props", base, 123);
        }
    }

    @Test
    void logBeforeProcessShouldCoverSchedulerAndHttpSenderBranches() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogLoggingLevel().isInfoLevel()).thenReturn(true);

        Map<String, SessionElementProperty> props = Map.of();
        Map<String, String> headers = Map.of("h", "v");

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            du.when(() -> DebuggerUtils.chooseLogPayload(eq(ex), anyString(), eq(dbg))).thenAnswer(a -> a.getArgument(1));

            ChainLogger logger = new ChainLogger(tracing, inst);

            when(dbg.getElementProperty("n1")).thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "SCHEDULER"));
            logger.logBeforeProcess(ex, dbg, "b", headers, props, "n1");

            when(dbg.getElementProperty("n2")).thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "HTTP_SENDER"));
            logger.logBeforeProcess(ex, dbg, "b", headers, props, "n2");
        }
    }

    @Test
    void logAfterProcessSuccessShouldCoverResponseCodePathEvenWhenHttpUriNull() {
        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(false);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.CAMEL_HTTP_RESPONSE_CODE, 200);
        when(msg.getHeaders()).thenReturn(headers);

        when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogLoggingLevel().isInfoLevel()).thenReturn(true);
        when(dbg.getElementProperty("n")).thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "HTTP_SENDER"));

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class);
             MockedStatic<PayloadExtractor> pe = mockStatic(PayloadExtractor.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(false);
            du.when(() -> DebuggerUtils.chooseLogPayload(eq(ex), anyString(), eq(dbg))).thenAnswer(a -> a.getArgument(1));
            pe.when(() -> PayloadExtractor.getResponseCode(headers)).thenReturn(200);

            ChainLogger logger = new ChainLogger(tracing, inst);

            logger.logAfterProcess(ex, dbg, "body", Map.of("h", "v"), Map.of(), "n", 10);
        }
    }

    @Test
    void logAfterProcessSuccessShouldCoverNoResponseCodeHeaderBranch() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeaders()).thenReturn(new HashMap<>());

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogLoggingLevel().isInfoLevel()).thenReturn(true);
        when(dbg.getElementProperty("n")).thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "SERVICE_CALL"));

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(false);
            du.when(() -> DebuggerUtils.chooseLogPayload(eq(ex), anyString(), eq(dbg))).thenAnswer(a -> a.getArgument(1));

            ChainLogger logger = new ChainLogger(tracing, inst);

            logger.logAfterProcess(ex, dbg, "body", Map.of(), Map.of(), "n", 10);
        }
    }

    @Test
    void logAfterProcessFailedNonCamelExceptionShouldCoverLogFailedOperationAndConstructExtendedLogMessage() {
        ErrorCode anyCode = ErrorCode.values()[0];

        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(true);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeaders()).thenReturn(new HashMap<>());

        when(ex.getException()).thenReturn(new Exception("boom"));
        when(ex.getProperty(Properties.SESSION_ID)).thenReturn("S-1");

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogLoggingLevel().isInfoLevel()).thenReturn(false);
        when(dbg.getElementProperty("n"))
                .thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "HTTP_SENDER"));
        when(dbg.getDeploymentInfo().getChainId()).thenReturn("C-1");
        when(dbg.getDeploymentInfo().getChainName()).thenReturn("CN");
        when(dbg.getElementProperty("nodeFormatted")).thenReturn(Map.of(
                CamelConstants.ChainProperties.ELEMENT_ID, "E-1",
                CamelConstants.ChainProperties.ELEMENT_NAME, "EN"
        ));

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> debuggerUtilsMock = mockStatic(DebuggerUtils.class);
             MockedStatic<ErrorCode> ec = mockStatic(ErrorCode.class);
             MockedStatic<ActiveSpanManager> asm = mockStatic(ActiveSpanManager.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            debuggerUtilsMock.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(true);
            debuggerUtilsMock.when(() -> DebuggerUtils.chooseLogPayload(eq(ex), anyString(), eq(dbg)))
                    .thenAnswer(a -> a.getArgument(1));
            debuggerUtilsMock.when(() -> DebuggerUtils.getNodeIdFormatted("n")).thenReturn("nodeFormatted");

            ec.when(() -> ErrorCode.match(any(Throwable.class))).thenReturn(anyCode);

            asm.when(() -> ActiveSpanManager.getSpan(ex)).thenReturn(null);

            ChainLogger logger = new ChainLogger(tracing, inst);
            logger.logAfterProcess(ex, dbg, "body", Map.of(), Map.of(), "n", 10);
        }
    }

    @Test
    void logAfterProcessFailedCamelExceptionWithSuppressedHttpShouldCoverLogFailedHttpOperation() {
        ErrorCode anyCode = ErrorCode.values()[0];

        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(false);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeaders()).thenReturn(new HashMap<>());

        CamelException camelEx = new CamelException("outer");

        HttpOperationFailedException suppressedHttp = mock(HttpOperationFailedException.class);
        when(suppressedHttp.getStatusCode()).thenReturn(500);
        when(suppressedHttp.getUri()).thenReturn("http://fail");
        camelEx.addSuppressed(suppressedHttp);

        when(ex.getException()).thenReturn(camelEx);
        when(ex.getException(CamelException.class)).thenReturn(camelEx);

        when(ex.getProperty(Properties.SESSION_ID)).thenReturn("S-1");

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogLoggingLevel().isInfoLevel()).thenReturn(false);
        when(dbg.getElementProperty("n"))
                .thenReturn(Map.of(CamelConstants.ChainProperties.ELEMENT_TYPE, "SERVICE_CALL"));
        when(dbg.getDeploymentInfo().getChainId()).thenReturn("C-1");
        when(dbg.getDeploymentInfo().getChainName()).thenReturn("CN");
        when(dbg.getElementProperty("nodeFormatted")).thenReturn(Map.of(
                CamelConstants.ChainProperties.ELEMENT_ID, "E-1",
                CamelConstants.ChainProperties.ELEMENT_NAME, "EN"
        ));

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class);
             MockedStatic<ErrorCode> ec = mockStatic(ErrorCode.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(true);
            du.when(() -> DebuggerUtils.chooseLogPayload(eq(ex), anyString(), eq(dbg)))
                    .thenAnswer(a -> a.getArgument(1));
            du.when(() -> DebuggerUtils.getNodeIdFormatted("n")).thenReturn("nodeFormatted");

            ec.when(() -> ErrorCode.match(any(Throwable.class))).thenReturn(anyCode);

            ChainLogger logger = new ChainLogger(tracing, inst);
            logger.logAfterProcess(ex, dbg, "body", Map.of(), Map.of(), "n", 77);
        }
    }

    @Test
    void logRetryRequestAttemptShouldCoverNotEnteringRetryBranchVariants() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        ElementRetryProperties retryProps = mock(ElementRetryProperties.class);
        when(retryProps.retryCount()).thenReturn(3);
        when(retryProps.retryDelay()).thenReturn(1000);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<IdentifierUtils> ids = mockStatic(IdentifierUtils.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            ids.when(() -> IdentifierUtils.getServiceCallRetryIteratorPropertyName("el")).thenReturn("it");
            ids.when(() -> IdentifierUtils.getServiceCallRetryPropertyName("el")).thenReturn("en");

            ChainLogger logger = new ChainLogger(tracing, inst);

            Exchange ex1 = mock(Exchange.class);
            when(ex1.getProperties()).thenReturn(new HashMap<>(Map.of("it", 1, "en", "false")));
            logger.logRetryRequestAttempt(ex1, retryProps, "el");

            Exchange ex2 = mock(Exchange.class);
            when(ex2.getProperties()).thenReturn(new HashMap<>(Map.of("it", 0, "en", "true")));
            logger.logRetryRequestAttempt(ex2, retryProps, "el");

            ElementRetryProperties retryProps0 = mock(ElementRetryProperties.class);
            when(retryProps0.retryCount()).thenReturn(0);
            when(retryProps0.retryDelay()).thenReturn(1000);

            Exchange ex3 = mock(Exchange.class);
            when(ex3.getProperties()).thenReturn(new HashMap<>(Map.of("it", 1, "en", "true")));
            logger.logRetryRequestAttempt(ex3, retryProps0, "el");
        }
    }

    @Test
    void logHTTPExchangeFinishedShouldCoverNodeIdNullAndExternalErrorCodeBranch() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);

        Exchange ex = mock(Exchange.class);
        when(ex.getProperty(Properties.SERVLET_REQUEST_URL)).thenReturn("http://req");
        when(ex.getProperty(Properties.HTTP_TRIGGER_EXTERNAL_ERROR_CODE))
                .thenReturn(ErrorCode.values()[0]);

        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<PayloadExtractor> pe = mockStatic(PayloadExtractor.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            pe.when(() -> PayloadExtractor.getServletResponseCode(ex, null)).thenReturn(500);

            ChainLogger logger = new ChainLogger(tracing, inst);

            logger.logHTTPExchangeFinished(ex, dbg, "b", "h", "p", null, 10, null);
        }
    }
}
