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
import org.apache.camel.CamelContext;
import org.apache.camel.CamelException;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.apache.camel.Route;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.spi.Registry;
import org.apache.camel.tracing.ActiveSpanManager;
import org.apache.camel.tracing.SpanAdapter;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.Payload;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
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
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                .chain(ChainInfo.builder().id("C-1").name("ChainName").build())
                .build();
        ElementInfo elementInfo = ElementInfo.builder().id("ElId").name("ElName").build();

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> dbgUtils = mockStatic(DebuggerUtils.class);
             MockedStatic<ActiveSpanManager> spanMgr = mockStatic(ActiveSpanManager.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance))
                    .thenReturn(Optional.of(businessIdProvider));

            ChainLogger logger = new ChainLogger(tracingService, instance, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SESSION_ID, String.class)).thenReturn("S-1");
            when(exchange.getFromRouteId()).thenReturn("r1");
            when(exchange.getContext()).thenReturn(camelContext);
            when(camelContext.getRoute("r1")).thenReturn(route);
            when(route.getGroup()).thenReturn("group-1");
            when(route.getCamelContext()).thenReturn(camelContext);
            when(camelContext.getRegistry()).thenReturn(registry);
            when(registry.lookupByNameAndType("DeploymentInfo-group-1", DeploymentInfo.class))
                    .thenReturn(deploymentInfo);
            when(registry.lookupByNameAndType("ElementInfo-node-1", ElementInfo.class))
                    .thenReturn(elementInfo);

            dbgUtils.when(() -> DebuggerUtils.getNodeIdFormatted("node-1")).thenReturn("node-1");

            SpanAdapter span = mock(SpanAdapter.class);
            when(span.traceId()).thenReturn("T-1");
            when(span.spanId()).thenReturn("SP-1");
            spanMgr.when(() -> ActiveSpanManager.getSpan(exchange)).thenReturn(span);

            logger.setLoggerContext(exchange, "node-1", true);

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
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                .chain(ChainInfo.builder().id("C-1").name("ChainName").build())
                .build();

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<ActiveSpanManager> spanMgr = mockStatic(ActiveSpanManager.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());

            ChainLogger logger = new ChainLogger(tracingService, instance, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SESSION_ID, String.class)).thenReturn("S-1");
            when(exchange.getFromRouteId()).thenReturn("r1");
            when(exchange.getContext()).thenReturn(camelContext);
            when(camelContext.getRoute("r1")).thenReturn(route);
            when(route.getGroup()).thenReturn("group-1");
            when(route.getCamelContext()).thenReturn(camelContext);
            when(camelContext.getRegistry()).thenReturn(registry);
            when(registry.lookupByNameAndType("DeploymentInfo-group-1", DeploymentInfo.class))
                    .thenReturn(deploymentInfo);

            spanMgr.when(() -> ActiveSpanManager.getSpan(exchange)).thenReturn(null);

            logger.setLoggerContext(exchange, null, false);

            assertNull(MDC.get(ChainLogger.MDC_TRACE_ID));
            assertNull(MDC.get(ChainLogger.MDC_SNAP_ID));
        }
    }

    @Test
    void logRequestShouldCoverAllBranchesNoExceptions() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracingService, instance, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            Exchange exchange = mock(Exchange.class);
            Message msg = mock(Message.class);
            when(exchange.getMessage()).thenReturn(msg);

            ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                    .logLoggingLevel(LogLoggingLevel.INFO)
                    .build();
            Payload payloadMock = mock(Payload.class);
            when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
            when(payloadExtractor.extractPayload(exchange)).thenReturn(payloadMock);

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");
            logger.logRequest(exchange, null, null);

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);
            logger.logRequest(exchange, null, null);

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");
            logger.logRequest(exchange, "svc", "dev");

            when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);
            logger.logRequest(exchange, "svc", "dev");
        }
    }

    @Test
    void logRetryRequestAttemptShouldHitRetryBranchAndNumberFormatBranch() {
        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        ServiceCallInfo serviceCallInfo = ServiceCallInfo.builder()
                .retryCount("3").retryDelay("1000").build();

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<IdentifierUtils> ids = mockStatic(IdentifierUtils.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracingService, instance, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            ids.when(() -> IdentifierUtils.getServiceCallRetryIteratorPropertyName("el")).thenReturn("itKey");
            ids.when(() -> IdentifierUtils.getServiceCallRetryPropertyName("el")).thenReturn("enKey");

            when(camelContext.getRoute("r1")).thenReturn(route);
            when(route.getGroup()).thenReturn("group-1");
            when(route.getCamelContext()).thenReturn(camelContext);
            when(camelContext.getRegistry()).thenReturn(registry);
            when(registry.lookupByNameAndType("ServiceCallInfo-el", ServiceCallInfo.class))
                    .thenReturn(serviceCallInfo);
            when(variablesService.injectVariables("3")).thenReturn("3");
            when(variablesService.injectVariables("1000")).thenReturn("1000");

            Exchange exchange = mock(Exchange.class);
            Map<String, Object> map = new HashMap<>();
            map.put("itKey", 1);
            map.put("enKey", "true");
            when(exchange.getProperties()).thenReturn(map);
            when(exchange.getProperty(eq(ExchangePropertyKey.EXCEPTION_CAUGHT), eq(Throwable.class)))
                    .thenReturn(new RuntimeException("boom"));
            when(exchange.getFromRouteId()).thenReturn("r1");
            when(exchange.getContext()).thenReturn(camelContext);
            when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                    .thenReturn(ChainRuntimeProperties.builder().logLoggingLevel(LogLoggingLevel.WARN).build());

            logger.logRetryRequestAttempt(exchange, "el");

            Exchange exchangeBad = mock(Exchange.class);
            Map<String, Object> mapBad = new HashMap<>();
            mapBad.put("itKey", "oops");
            mapBad.put("enKey", "true");
            when(exchangeBad.getProperties()).thenReturn(mapBad);
            when(chainRuntimePropertiesService.getRuntimeProperties(exchangeBad))
                    .thenReturn(ChainRuntimeProperties.builder().logLoggingLevel(LogLoggingLevel.INFO).build());

            logger.logRequestAttempt(exchangeBad, "el");
        }
    }

    @Test
    void logHTTPExchangeFinishedShouldCoverSuccessAndFailureBranches() {
        ErrorCode anyCode = ErrorCode.values()[0];

        TracingService tracingService = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> instance = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);
        ElementInfo elementInfo = ElementInfo.builder().id("ID").name("N").build();

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class);
             MockedStatic<PayloadExtractor> peMock = mockStatic(PayloadExtractor.class);
             MockedStatic<ErrorCode> ecMock = mockStatic(ErrorCode.class)) {

            injectUtil.when(() -> InjectUtil.injectOptional(instance)).thenReturn(Optional.empty());
            ecMock.when(() -> ErrorCode.match(any(Throwable.class))).thenReturn(anyCode);

            ChainLogger logger = new ChainLogger(tracingService, instance, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            Exchange exchange = mock(Exchange.class);
            when(exchange.getProperty(Properties.SERVLET_REQUEST_URL)).thenReturn("http://req");
            when(exchange.getFromRouteId()).thenReturn("r1");
            when(exchange.getContext()).thenReturn(camelContext);
            when(camelContext.getRoute("r1")).thenReturn(route);
            when(route.getGroup()).thenReturn("group-1");
            when(route.getCamelContext()).thenReturn(camelContext);
            when(camelContext.getRegistry()).thenReturn(registry);
            when(registry.lookupByNameAndType("ElementInfo-n1", ElementInfo.class)).thenReturn(elementInfo);

            Payload payloadMock = mock(Payload.class);
            when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                    .thenReturn(ChainRuntimeProperties.builder().build());
            when(payloadExtractor.extractPayload(exchange)).thenReturn(payloadMock);

            peMock.when(() -> PayloadExtractor.getServletResponseCode(eq(exchange), isNull()))
                    .thenReturn(200);
            logger.logHTTPExchangeFinished(exchange, "n1", 10, null);

            peMock.when(() -> PayloadExtractor.getServletResponseCode(eq(exchange), notNull()))
                    .thenReturn(500);
            logger.logHTTPExchangeFinished(exchange, "n1", 10, new RuntimeException("x"));
        }
    }

    @Test
    void wrappersDebugInfoWarnErrorShouldExecute() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.debug("d {}", 1);
            logger.info("i {}", 1);
            logger.warn("w {}", 1);
            logger.error("e {}", 1);
        }
    }

    @Test
    void logExchangeFinishedShouldCoverBranchWithExecutionStatusProperty() {
        ExecutionStatus base = ExecutionStatus.values()[0];
        ExecutionStatus override = ExecutionStatus.values().length > 1 ? ExecutionStatus.values()[1] : base;

        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange exchange = mock(Exchange.class);
        when(exchange.getProperty(CamelConstants.SYSTEM_PROPERTY_PREFIX + "executionStatus", ExecutionStatus.class))
                .thenReturn(override);

        Payload payloadMock = mock(Payload.class);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(ChainRuntimeProperties.builder().build());
        when(payloadExtractor.extractPayload(exchange)).thenReturn(payloadMock);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.logExchangeFinished(exchange, base, 123);
        }
    }

    @Test
    void logBeforeProcessShouldCoverSchedulerAndHttpSenderBranches() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn("http://x");

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        when(ex.getFromRouteId()).thenReturn("r1");
        when(ex.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(route.getGroup()).thenReturn("group-1");
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-n1", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("SCHEDULER").build());
        when(registry.lookupByNameAndType("ElementInfo-n2", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("HTTP_SENDER").build());

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(LogLoggingLevel.INFO)
                .build();
        Payload payloadMock = mock(Payload.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.logBeforeProcess(ex, runtimeProperties, "n1", payloadMock);
            logger.logBeforeProcess(ex, runtimeProperties, "n2", payloadMock);
        }
    }

    @Test
    void logAfterProcessSuccessShouldCoverResponseCodePathEvenWhenHttpUriNull() {
        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(false);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);

        Map<String, Object> headers = new HashMap<>();
        headers.put(Headers.CAMEL_HTTP_RESPONSE_CODE, 200);
        when(msg.getHeaders()).thenReturn(headers);
        when(msg.getHeader(eq(Headers.HTTP_URI), eq(String.class))).thenReturn(null);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        when(ex.getFromRouteId()).thenReturn("r1");
        when(ex.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(route.getGroup()).thenReturn("group-1");
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-n", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("HTTP_SENDER").build());

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(LogLoggingLevel.INFO)
                .build();
        Payload payloadMock = mock(Payload.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class);
             MockedStatic<PayloadExtractor> pe = mockStatic(PayloadExtractor.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(false);
            pe.when(() -> PayloadExtractor.getResponseCode(headers)).thenReturn(200);

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.logAfterProcess(ex, runtimeProperties, payloadMock, "n", 10);
        }
    }

    @Test
    void logAfterProcessSuccessShouldCoverNoResponseCodeHeaderBranch() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeaders()).thenReturn(new HashMap<>());

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        when(ex.getFromRouteId()).thenReturn("r1");
        when(ex.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(route.getGroup()).thenReturn("group-1");
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("ElementInfo-n", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("SERVICE_CALL").build());

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(LogLoggingLevel.INFO)
                .build();
        Payload payloadMock = mock(Payload.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(false);

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.logAfterProcess(ex, runtimeProperties, payloadMock, "n", 10);
        }
    }

    @Test
    void logAfterProcessFailedNonCamelExceptionShouldCoverLogFailedOperationAndConstructExtendedLogMessage() {
        ErrorCode anyCode = ErrorCode.values()[0];

        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(true);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange ex = mock(Exchange.class);
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(msg.getHeaders()).thenReturn(new HashMap<>());

        when(ex.getException()).thenReturn(new Exception("boom"));
        when(ex.getProperty(Properties.SESSION_ID, String.class)).thenReturn("S-1");

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                .chain(ChainInfo.builder().id("C-1").name("CN").build())
                .build();

        when(ex.getFromRouteId()).thenReturn("r1");
        when(ex.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(route.getGroup()).thenReturn("group-1");
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("DeploymentInfo-group-1", DeploymentInfo.class))
                .thenReturn(deploymentInfo);
        when(registry.lookupByNameAndType("ElementInfo-n", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("HTTP_SENDER").build());
        when(registry.lookupByNameAndType("ElementInfo-nodeFormatted", ElementInfo.class))
                .thenReturn(ElementInfo.builder().id("E-1").name("EN").build());

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(LogLoggingLevel.WARN)
                .build();
        Payload payloadMock = mock(Payload.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> debuggerUtilsMock = mockStatic(DebuggerUtils.class);
             MockedStatic<ErrorCode> ec = mockStatic(ErrorCode.class);
             MockedStatic<ActiveSpanManager> asm = mockStatic(ActiveSpanManager.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            debuggerUtilsMock.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(true);
            debuggerUtilsMock.when(() -> DebuggerUtils.getNodeIdFormatted("n")).thenReturn("nodeFormatted");

            ec.when(() -> ErrorCode.match(any(Throwable.class))).thenReturn(anyCode);

            asm.when(() -> ActiveSpanManager.getSpan(ex)).thenReturn(null);

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);
            logger.logAfterProcess(ex, runtimeProperties, payloadMock, "n", 10);
        }
    }

    @Test
    void logAfterProcessFailedCamelExceptionWithSuppressedHttpShouldCoverLogFailedHttpOperation() {
        ErrorCode anyCode = ErrorCode.values()[0];

        TracingService tracing = mock(TracingService.class);
        when(tracing.isTracingEnabled()).thenReturn(false);

        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

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
        when(ex.getProperty(Properties.SESSION_ID, String.class)).thenReturn("S-1");

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                .chain(ChainInfo.builder().id("C-1").name("CN").build())
                .build();

        when(ex.getFromRouteId()).thenReturn("r1");
        when(ex.getContext()).thenReturn(camelContext);
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(route.getGroup()).thenReturn("group-1");
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.lookupByNameAndType("DeploymentInfo-group-1", DeploymentInfo.class))
                .thenReturn(deploymentInfo);
        when(registry.lookupByNameAndType("ElementInfo-n", ElementInfo.class))
                .thenReturn(ElementInfo.builder().type("SERVICE_CALL").build());
        when(registry.lookupByNameAndType("ElementInfo-nodeFormatted", ElementInfo.class))
                .thenReturn(ElementInfo.builder().id("E-1").name("EN").build());

        ChainRuntimeProperties runtimeProperties = ChainRuntimeProperties.builder()
                .logLoggingLevel(LogLoggingLevel.WARN)
                .build();
        Payload payloadMock = mock(Payload.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<DebuggerUtils> du = mockStatic(DebuggerUtils.class);
             MockedStatic<ErrorCode> ec = mockStatic(ErrorCode.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            du.when(() -> DebuggerUtils.isFailedOperation(ex)).thenReturn(true);
            du.when(() -> DebuggerUtils.getNodeIdFormatted("n")).thenReturn("nodeFormatted");

            ec.when(() -> ErrorCode.match(any(Throwable.class))).thenReturn(anyCode);

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);
            logger.logAfterProcess(ex, runtimeProperties, payloadMock, "n", 77);
        }
    }

    @Test
    void logRetryRequestAttemptShouldCoverNotEnteringRetryBranchVariants() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);

        // null retryCount/retryDelay → Integer.parseInt path uses orElse(0), so count=0 for all exchanges
        ServiceCallInfo serviceCallInfo = ServiceCallInfo.builder().build();

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<IdentifierUtils> ids = mockStatic(IdentifierUtils.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());

            ids.when(() -> IdentifierUtils.getServiceCallRetryIteratorPropertyName("el")).thenReturn("it");
            ids.when(() -> IdentifierUtils.getServiceCallRetryPropertyName("el")).thenReturn("en");

            when(camelContext.getRoute("r1")).thenReturn(route);
            when(route.getGroup()).thenReturn("group-1");
            when(route.getCamelContext()).thenReturn(camelContext);
            when(camelContext.getRegistry()).thenReturn(registry);
            when(registry.lookupByNameAndType("ServiceCallInfo-el", ServiceCallInfo.class))
                    .thenReturn(serviceCallInfo);

            ChainRuntimeProperties warnProperties = ChainRuntimeProperties.builder()
                    .logLoggingLevel(LogLoggingLevel.WARN)
                    .build();

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            Exchange ex1 = mock(Exchange.class);
            when(ex1.getProperties()).thenReturn(new HashMap<>(Map.of("it", 1, "en", "false")));
            when(ex1.getFromRouteId()).thenReturn("r1");
            when(ex1.getContext()).thenReturn(camelContext);
            when(chainRuntimePropertiesService.getRuntimeProperties(ex1)).thenReturn(warnProperties);
            logger.logRetryRequestAttempt(ex1, "el");

            Exchange ex2 = mock(Exchange.class);
            when(ex2.getProperties()).thenReturn(new HashMap<>(Map.of("it", 0, "en", "true")));
            when(ex2.getFromRouteId()).thenReturn("r1");
            when(ex2.getContext()).thenReturn(camelContext);
            when(chainRuntimePropertiesService.getRuntimeProperties(ex2)).thenReturn(warnProperties);
            logger.logRetryRequestAttempt(ex2, "el");

            Exchange ex3 = mock(Exchange.class);
            when(ex3.getProperties()).thenReturn(new HashMap<>(Map.of("it", 1, "en", "true")));
            when(ex3.getFromRouteId()).thenReturn("r1");
            when(ex3.getContext()).thenReturn(camelContext);
            when(chainRuntimePropertiesService.getRuntimeProperties(ex3)).thenReturn(warnProperties);
            logger.logRetryRequestAttempt(ex3, "el");
        }
    }

    @Test
    void logHTTPExchangeFinishedShouldCoverNodeIdNullAndExternalErrorCodeBranch() {
        TracingService tracing = mock(TracingService.class);
        @SuppressWarnings("unchecked")
        Instance<OriginatingBusinessIdProvider> inst = mock(Instance.class);
        PayloadExtractor payloadExtractor = mock(PayloadExtractor.class);
        ChainRuntimePropertiesService chainRuntimePropertiesService = mock(ChainRuntimePropertiesService.class);
        VariablesService variablesService = mock(VariablesService.class);

        Exchange ex = mock(Exchange.class);
        when(ex.getProperty(Properties.SERVLET_REQUEST_URL)).thenReturn("http://req");
        when(ex.getProperty(Properties.HTTP_TRIGGER_EXTERNAL_ERROR_CODE))
                .thenReturn(ErrorCode.values()[0]);

        Payload payloadMock = mock(Payload.class);
        when(chainRuntimePropertiesService.getRuntimeProperties(ex))
                .thenReturn(ChainRuntimeProperties.builder().build());
        when(payloadExtractor.extractPayload(ex)).thenReturn(payloadMock);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class);
             MockedStatic<PayloadExtractor> pe = mockStatic(PayloadExtractor.class)) {

            inject.when(() -> InjectUtil.injectOptional(inst)).thenReturn(Optional.empty());
            pe.when(() -> PayloadExtractor.getServletResponseCode(ex, null)).thenReturn(500);

            ChainLogger logger = new ChainLogger(tracing, inst, payloadExtractor,
                    chainRuntimePropertiesService, variablesService);

            logger.logHTTPExchangeFinished(ex, null, 10, null);
        }
    }
}
