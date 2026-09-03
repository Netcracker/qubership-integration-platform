package org.qubership.integration.platform.engine.service.debugger;

import jakarta.enterprise.inject.Instance;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.spi.CamelEvent.ExchangeCreatedEvent;
import org.apache.camel.spi.CamelEvent.StepCompletedEvent;
import org.apache.camel.spi.CamelEvent.StepStartedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.MaskedFields;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.Payload;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.ExchangePropertyService;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.logging.AbstractChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.JsonSessionStepCoordinator;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionStepLogContext;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.CheckpointUtils;

import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CamelDebuggerTest {

    private static final String SESSION_ID = "session-1";
    private static final String NODE_ID = "450e8d96-c2f1-48f5-854d-7540122d5d51";
    private static final String ELEMENT_TYPE = ChainElementType.SERVICE_CALL.getText();
    private static final String CUSTOM_STEP_ID = "request--" + NODE_ID;

    @Mock
    private EngineInfo engineInfo;
    @Mock
    private TracingService tracingService;
    @Mock
    private CheckpointSessionService checkpointSessionService;
    @Mock
    private MetricsService metricsService;
    @Mock
    private AbstractChainLogger chainLogger;
    @Mock
    private SessionsService sessionsService;
    @Mock
    private PayloadExtractor payloadExtractor;
    @Mock
    private VariablesService variablesService;
    @Mock
    private ChainRuntimePropertiesService chainRuntimePropertiesService;
    @Mock
    private CamelExchangeContextPropagation exchangeContextPropagation;
    @Mock
    private ExchangePropertyService exchangePropertyService;
    @Mock
    private Instance sessionsKafkaInstance;

    private JsonSessionStepCoordinator jsonSessionStepCoordinator;

    private CamelDebugger debugger;
    private ChainRuntimeProperties runtimeProperties;
    private Payload payload;

    @BeforeEach
    void setUp() {
        // Instance mock for SessionsKafkaReportingService -> empty
        lenient().when(sessionsKafkaInstance.isAmbiguous()).thenReturn(false);
        lenient().when(sessionsKafkaInstance.stream()).thenReturn(Stream.empty());

        runtimeProperties = mock(ChainRuntimeProperties.class);
        payload = mock(Payload.class);
        lenient().when(payload.getBody()).thenReturn("body");
        lenient().when(payload.getHeaders()).thenReturn(java.util.Collections.emptyMap());
        lenient().when(payload.getProperties()).thenReturn(java.util.Collections.emptyMap());
        lenient().when(payload.getContext()).thenReturn(java.util.Collections.emptyMap());

        jsonSessionStepCoordinator = new JsonSessionStepCoordinator(sessionsService, payloadExtractor,
                engineInfo, chainRuntimePropertiesService);
        debugger = new CamelDebugger(
                engineInfo,
                tracingService,
                checkpointSessionService,
                metricsService,
                chainLogger,
                sessionsKafkaInstance,
                sessionsService,
                payloadExtractor,
                variablesService,
                chainRuntimePropertiesService,
                exchangeContextPropagation,
                exchangePropertyService,
                jsonSessionStepCoordinator);

        lenient().when(chainRuntimePropertiesService.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        lenient().when(chainRuntimePropertiesService.getRuntimeProperties(any(Exchange.class))).thenReturn(runtimeProperties);
        lenient().when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        lenient().when(runtimeProperties.isMaskingEnabled()).thenReturn(false);
        lenient().when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);
        lenient().when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        lenient().when(tracingService.isTracingEnabled()).thenReturn(false);
        lenient().when(engineInfo.getDomain()).thenReturn("domain");
        lenient().when(engineInfo.getHost()).thenReturn("host");

        lenient().when(payloadExtractor.extractPayload(any())).thenReturn(payload);
        lenient().when(payloadExtractor.extractHeadersForLogging(any(), any(), anyBoolean()))
                .thenReturn(java.util.Collections.emptyMap());
        lenient().when(payloadExtractor.extractBodyForLogging(any(), any(), anyBoolean()))
                .thenReturn("body");
        lenient().when(payloadExtractor.extractExchangePropertiesForLogging(any(), any(), anyBoolean()))
                .thenReturn(java.util.Collections.emptyMap());
        lenient().when(payloadExtractor.extractContextForLogging(any(), anyBoolean()))
                .thenReturn(java.util.Collections.emptyMap());
    }

    @Test
    void getRuntimePropertiesShouldDelegateViaService() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        lenient().when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        // trigger any method that delegates to service, e.g. beforeProcess with valid uuid will call getRuntimeProperties
        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(MaskedFields.class))).thenReturn(new MaskedFields());

            Processor processor = mock(Processor.class);
            NamedNode definition = mock(NamedNode.class);
            when(definition.getId()).thenReturn(NODE_ID);
            debugger.beforeProcess(exchange, processor, definition);
            verify(chainRuntimePropertiesService).getRuntimeProperties(any(Exchange.class));
        }
    }

    @Test
    void onEventExchangeCreatedShouldInitializeSession() {
        Exchange exchange = createExchange();
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(SESSION_ID);
        when(session.getStarted()).thenReturn(java.time.LocalDateTime.now().toString());
        lenient().when(sessionsService.startSession(any(), any(), any(), any())).thenReturn(session);
        lenient().when(sessionsService.sessionShouldBeLogged()).thenReturn(true);
        try (var checkpointUtilsMock = mockStatic(CheckpointUtils.class);
             var metadataMock = mockStatic(MetadataUtil.class)) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(any()))
                    .thenReturn(null);
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(MaskedFields.class))).thenReturn(new MaskedFields());
            metadataMock.when(() -> MetadataUtil.getElementsInfo(any(Exchange.class))).thenReturn(Stream.empty());

            ExchangeCreatedEvent event = mock(ExchangeCreatedEvent.class);
            debugger.onEvent(exchange, event);

            assertNotNull(exchange.getProperty(Properties.SESSION_ID));
            verify(sessionsService).startSession(any(), anyString(), any(), anyString());
        }
    }

    @Test
    void afterProcessShouldSetTimeoutExceptionWhenChainTimesOut() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        exchange.setProperty(Properties.START_TIME_MS, System.currentTimeMillis() - 5000L);
        exchange.setProperty(Properties.CHAIN_TIME_OUT_AFTER, 1000L);
        exchange.setProperty(Properties.CHAIN_TIMED_OUT, false);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn("non-uuid-node");

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            debugger.afterProcess(exchange, processor, definition, 0L);
        }

        assertInstanceOf(ChainExecutionTimeoutException.class, exchange.getException());
    }

    @Test
    void afterProcessShouldLogSessionElementAfterOnDebugLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);

            verify(sessionsService).logSessionElementAfter(any(), any(), eq(SESSION_ID), any(), any(Payload.class));
            verify(chainLogger).logAfterProcess(any(), any(), any(Payload.class), eq(NODE_ID), eq(10L));
        }
    }

    @Test
    void afterProcessShouldNotLogSessionElementAfterOnErrorLevelWhenNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);

            verify(sessionsService, never()).logSessionElementAfter(any(), any(), any(), any(), any(Payload.class));
        }
    }

    @Test
    void beforeProcessShouldLogSessionElementBeforeOnDebugLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(MaskedFields.class))).thenReturn(new MaskedFields());

            debugger.beforeProcess(exchange, processor, definition);

            verify(sessionsService).logSessionElementBefore(any(), eq(SESSION_ID), any(), eq(NODE_ID), any(Payload.class));
            verify(chainLogger).logBeforeProcess(any(), any(), eq(NODE_ID), any(Payload.class));
        }
    }

    @Test
    void beforeProcessShouldPutElementToSingleCacheOnErrorLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(MaskedFields.class))).thenReturn(new MaskedFields());

            debugger.beforeProcess(exchange, processor, definition);

            verify(sessionsService).putElementToSingleElCache(any(), eq(SESSION_ID), any(), eq(NODE_ID), any(Payload.class));
            verify(sessionsService, never()).logSessionElementBefore(any(), any(), any(), any(), any(Payload.class));
        }
    }

    @Test
    void finishCheckpointSessionShouldSaveAndWarnWhenCompletedWithErrors() {
        SessionInfo sessionInfo = mock(SessionInfo.class);
        when(checkpointSessionService.findSession(SESSION_ID)).thenReturn(sessionInfo);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            debugger.finishCheckpointSession(createExchange(), SESSION_ID,
                    ExecutionStatus.COMPLETED_WITH_ERRORS, 100L);
        }

        verify(sessionInfo).setExecutionStatus(ExecutionStatus.COMPLETED_WITH_ERRORS);
        verify(sessionInfo).setFinished(any());
        verify(sessionInfo).setDuration(100L);
        verify(checkpointSessionService).saveSession(sessionInfo);
        verify(chainLogger).warn(contains("checkpoint"));
    }

    @Test
    void finishCheckpointSessionShouldRemoveCheckpointsWhenCompletedNormally() {
        SessionInfo sessionInfo = mock(SessionInfo.class);
        when(sessionInfo.getId()).thenReturn("sid");
        when(checkpointSessionService.findSession(SESSION_ID)).thenReturn(sessionInfo);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            debugger.finishCheckpointSession(createExchange(), SESSION_ID,
                    ExecutionStatus.COMPLETED_NORMALLY, 100L);
        }

        verify(checkpointSessionService).removeAllRelatedCheckpoints("sid", true);
    }

    @Test
    void logAfterStepFinishedShouldLogRetryAttemptForServiceCallOnWarnLevel() {
        Exchange exchange = createExchange();
        lenient().when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);
        ElementInfo elementInfo = ElementInfo.builder().id("elem-1").type(ChainElementType.SERVICE_CALL.getText()).name("elem").build();
        ChainExecutionContext ctx = ChainExecutionContext.builder()
                .elementInfo(elementInfo)
                .stepName(CamelNames.REQUEST_ATTEMPT_STEP_PREFIX)
                .stepId(CamelNames.REQUEST_ATTEMPT_STEP_PREFIX)
                .chainRuntimeProperties(runtimeProperties)
                .deploymentInfo(DeploymentInfo.builder().chain(ChainInfo.builder().id("c").name("n").build()).build())
                .build();

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            org.qubership.integration.platform.engine.metadata.ServiceCallInfo scInfo = org.qubership.integration.platform.engine.metadata.ServiceCallInfo.builder().build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq("elem-1"), eq(org.qubership.integration.platform.engine.metadata.ServiceCallInfo.class))).thenReturn(scInfo);
            debugger.logAfterStepFinished(exchange, ctx);
        }

        verify(chainLogger).logRetryRequestAttempt(any(), eq("elem-1"));
    }

    @Test
    void logAfterStepFinishedShouldNotLogRetryAttemptForNonServiceCall() {
        Exchange exchange = createExchange();
        lenient().when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);
        ElementInfo elementInfo = ElementInfo.builder().id("elem-1").type(ChainElementType.HTTP_SENDER.getText()).name("elem").build();
        ChainExecutionContext ctx = ChainExecutionContext.builder()
                .elementInfo(elementInfo)
                .stepName(CamelNames.REQUEST_ATTEMPT_STEP_PREFIX)
                .stepId(CamelNames.REQUEST_ATTEMPT_STEP_PREFIX)
                .chainRuntimeProperties(runtimeProperties)
                .build();

        debugger.logAfterStepFinished(exchange, ctx);

        verify(chainLogger, never()).logRetryRequestAttempt(any(), any());
    }

    @Test
    void afterProcessShouldRecordStepAfterWhenSessionLogDetailsFull() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(NODE_ID, "sess-el-1");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        ArgumentCaptor<SessionStepLogContext> captor = ArgumentCaptor.forClass(SessionStepLogContext.class);
        verify(sessionsService).recordStepAfter(captor.capture());
        assertEquals(SESSION_ID, captor.getValue().sessionId());
        assertEquals(NODE_ID, captor.getValue().nodeId());
        assertEquals("sess-el-1", captor.getValue().sessionElementId());
        assertEquals("domain", captor.getValue().domain());
    }

    @Test
    void afterProcessShouldRecordStepAfterWhenSessionLogDetailsSenders() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.OFF);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.SENDERS);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 5L);
        }

        verify(sessionsService).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldNotRecordStepAfterWhenSessionLogDetailsOff() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService, never()).recordStepAfterForStep(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldNotRecordStepAfterForCustomStepIdPattern() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(CUSTOM_STEP_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldNotRecordStepAfterWhenIsStepNode() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        Set<String> stepIds = ConcurrentHashMap.newKeySet();
        stepIds.add(NODE_ID);
        exchange.setProperty(Properties.SESSION_STEP_IDS, stepIds);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldExtractPayloadWhenDetailsFullEvenIfNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        verify(payloadExtractor).extractPayload(any());
    }

    @Test
    void afterProcessShouldNotExtractPayloadWhenDetailsOffAndNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        // Need to make shouldExtract false: sessionShouldBeLogged false, log level WARN (not info), no failed operation, details OFF => no extract
        // Ensure log level is WARN (default) so not info

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        // payloadExtractor will not be called for logging because shouldExtract false -> returns empty payload without calling extractPayload?
        // Actually shouldExtract false returns empty payload without calling extractPayload, so verify never extractPayload
        verify(payloadExtractor, never()).extractPayload(any());
    }

    @Test
    void afterProcessShouldResolveSessionElementIdViaSplitIdChain() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        exchange.setProperty(Properties.SPLIT_ID_CHAIN, ":split");
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(NODE_ID + ":split", "sess-split");
        executionMap.put(NODE_ID, "sess-fallback");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        ArgumentCaptor<SessionStepLogContext> captor = ArgumentCaptor.forClass(SessionStepLogContext.class);
        verify(sessionsService).recordStepAfter(captor.capture());
        assertEquals("sess-split", captor.getValue().sessionElementId());
    }

    @Test
    void afterProcessShouldHandleFormattedCustomStepNodeId() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(CUSTOM_STEP_ID, "sess-custom");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(CUSTOM_STEP_ID);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            // CUSTOM_STEP_ID formatted -> NODE_ID, so mock for NODE_ID
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            debugger.afterProcess(exchange, processor, definition, 10L);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService).logSessionElementAfter(any(), any(), eq(SESSION_ID), eq("sess-custom"), any(Payload.class));
    }

    @Test
    void onEventStepStartedShouldRegisterStepNodeIds() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            StepStartedEvent event = mock(StepStartedEvent.class);
            when(event.getStepId()).thenReturn(CUSTOM_STEP_ID);

            debugger.onEvent(exchange, event);

            @SuppressWarnings("unchecked")
            Map<String, String> nodeIds = (Map<String, String>) exchange.getProperty(Properties.SESSION_STEP_NODE_IDS);
            assertNotNull(nodeIds);
            // micro stores formatted stepName "request" as value, not NODE_ID
            assertTrue(nodeIds.containsValue("request"));

            @SuppressWarnings("unchecked")
            Set<String> stepIds = (Set<String>) exchange.getProperty(Properties.SESSION_STEP_IDS);
            assertNotNull(stepIds);
            assertTrue(stepIds.contains(NODE_ID));
        }
    }

    @Test
    void stepFinishedShouldRecordStepAfterForRegularStep() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        String sessElId = "step-sess-1";
        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push(sessElId);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStepId()).thenReturn(NODE_ID);

            debugger.onEvent(exchange, event);
        }

        verify(sessionsService).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService, never()).recordStepAfterForStep(any(SessionStepLogContext.class));
        assertTrue(steps.isEmpty());
    }

    @Test
    void stepFinishedShouldRecordStepAfterForStepForCustomPattern() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        String sessElId = "step-sess-2";
        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push(sessElId);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStepId()).thenReturn(CUSTOM_STEP_ID);

            debugger.onEvent(exchange, event);
        }

        ArgumentCaptor<SessionStepLogContext> captor = ArgumentCaptor.forClass(SessionStepLogContext.class);
        verify(sessionsService).recordStepAfterForStep(captor.capture());
        assertEquals(sessElId, captor.getValue().sessionElementId());
        assertEquals("request", captor.getValue().stepName());
        assertEquals(NODE_ID, captor.getValue().stepChainElementId());
        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void stepFinishedShouldNotRecordWhenDetailsOff() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);

        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push("step-sess-3");

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStepId()).thenReturn(NODE_ID);

            debugger.onEvent(exchange, event);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService, never()).recordStepAfterForStep(any(SessionStepLogContext.class));
    }

    @Test
    void stepFinishedShouldNotRecordWhenNoStepContext() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        try (var metadataMock = mockStatic(MetadataUtil.class)) {
            ElementInfo elementInfo = ElementInfo.builder().id(NODE_ID).type(ELEMENT_TYPE).name("elem").build();
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBeanForElement(any(Exchange.class), eq(NODE_ID), eq(ElementInfo.class))).thenReturn(elementInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStepId()).thenReturn(NODE_ID);

            debugger.onEvent(exchange, event);
        }

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService, never()).recordStepAfterForStep(any(SessionStepLogContext.class));
    }

    @Test
    void exchangeCreatedShouldSetLoggerContextWithTracingFlag() {
        Exchange exchange = createExchange();
        lenient().when(tracingService.isTracingEnabled()).thenReturn(true);
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(SESSION_ID);
        when(session.getStarted()).thenReturn(java.time.LocalDateTime.now().toString());
        lenient().when(sessionsService.startSession(any(), any(), any(), any())).thenReturn(session);
        lenient().when(sessionsService.sessionShouldBeLogged()).thenReturn(true);
        try (var checkpointUtilsMock = mockStatic(CheckpointUtils.class);
             var metadataMock = mockStatic(MetadataUtil.class)) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(any()))
                    .thenReturn(null);
            DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                    .chain(ChainInfo.builder().id("chain-1").name("chain").build())
                    .snapshot(SnapshotInfo.builder().id("snap-1").name("snap").build())
                    .build();
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(DeploymentInfo.class))).thenReturn(deploymentInfo);
            metadataMock.when(() -> MetadataUtil.getBean(any(Exchange.class), eq(MaskedFields.class))).thenReturn(new MaskedFields());
            metadataMock.when(() -> MetadataUtil.getElementsInfo(any(Exchange.class))).thenReturn(Stream.empty());

            ExchangeCreatedEvent event = mock(ExchangeCreatedEvent.class);
            debugger.onEvent(exchange, event);

            assertNotNull(exchange.getProperty(Properties.SESSION_ID));
            verify(tracingService, times(2)).isTracingEnabled();
            verify(tracingService).addChainTracingTags(any(Exchange.class));
        }
    }

    private static Exchange createExchange() {
        Exchange ex = mock(Exchange.class, org.mockito.Mockito.withSettings().lenient());
        ConcurrentHashMap<String, Object> props = new ConcurrentHashMap<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();

        lenient().doAnswer(inv -> {
            props.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ex).setProperty(anyString(), any());

        lenient().doAnswer(inv -> {
            props.put(inv.<ExchangePropertyKey>getArgument(0).getName(), inv.getArgument(1));
            return null;
        }).when(ex).setProperty(any(ExchangePropertyKey.class), any());

        lenient().when(ex.getProperties()).thenReturn(props);
        lenient().when(ex.getProperty(anyString())).thenAnswer(inv -> props.get(inv.getArgument(0)));
        lenient().when(ex.getProperty(anyString(), any(Class.class))).thenAnswer(inv -> {
            Object value = props.get(inv.getArgument(0));
            return value == null ? null : inv.<Class<?>>getArgument(1).cast(value);
        });
        lenient().when(ex.getProperty(anyString(), any(), any(Class.class))).thenAnswer(inv -> {
            Object value = props.get(inv.getArgument(0));
            return value == null ? inv.getArgument(1) : inv.<Class<?>>getArgument(2).cast(value);
        });
        lenient().when(ex.getProperty(any(ExchangePropertyKey.class), any(Class.class))).thenAnswer(inv -> {
            ExchangePropertyKey key = inv.getArgument(0);
            Object value = props.get(key.getName());
            return value == null ? null : inv.<Class<?>>getArgument(1).cast(value);
        });
        lenient().when(ex.getProperty(any(ExchangePropertyKey.class), any(), any(Class.class))).thenAnswer(inv -> {
            ExchangePropertyKey key = inv.getArgument(0);
            Object def = inv.getArgument(1);
            Class<?> type = inv.getArgument(2);
            Object value = props.get(key.getName());
            return value == null ? def : type.cast(value);
        });

        lenient().doAnswer(inv -> {
            exceptionRef.set(inv.getArgument(0));
            return null;
        }).when(ex).setException(any());
        lenient().when(ex.getException()).thenAnswer(inv -> exceptionRef.get());
        lenient().when(ex.getException(any(Class.class))).thenAnswer(inv -> {
            Throwable t = exceptionRef.get();
            return t == null ? null : inv.<Class<?>>getArgument(0).cast(t);
        });

        Message msg = mock(Message.class, org.mockito.Mockito.withSettings().lenient());
        ConcurrentHashMap<String, Object> headers = new ConcurrentHashMap<>();
        lenient().when(msg.getHeaders()).thenReturn(headers);
        lenient().doAnswer(inv -> {
            headers.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(msg).setHeader(anyString(), any());
        lenient().when(msg.getHeader(anyString())).thenAnswer(inv -> headers.get(inv.getArgument(0)));
        lenient().when(msg.getHeader(anyString(), any(Class.class))).thenAnswer(inv -> {
            Object value = headers.get(inv.getArgument(0));
            return value == null ? inv.getArgument(1) : inv.<Class<?>>getArgument(1).cast(value);
        });
        lenient().when(msg.getHeader(anyString(), any(), any(Class.class))).thenAnswer(inv -> {
            Object value = headers.get(inv.getArgument(0));
            return value == null ? inv.getArgument(1) : inv.<Class<?>>getArgument(2).cast(value);
        });
        lenient().when(ex.getMessage()).thenReturn(msg);
        lenient().when(ex.getIn()).thenReturn(msg);
        lenient().when(ex.getExchangeId()).thenReturn("exchange-1");
        lenient().when(msg.getExchange()).thenReturn(ex);
        lenient().when(ex.getFromRouteId()).thenReturn("route-1");

        props.put(Properties.ELEMENT_EXECUTION_MAP, new ConcurrentHashMap<String, String>());
        props.put(Properties.STEPS, new ConcurrentLinkedDeque<String>());
        props.put(Properties.START_TIME_MS, 0L);
        props.put(Properties.CHAIN_TIME_OUT_AFTER, 0L);
        headers.put(org.apache.camel.Exchange.HTTP_PATH, "");
        return ex;
    }
}
