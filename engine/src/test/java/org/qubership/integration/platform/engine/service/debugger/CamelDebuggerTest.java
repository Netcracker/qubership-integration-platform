package org.qubership.integration.platform.engine.service.debugger;

import com.netcracker.cloud.bluegreen.api.model.State;
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
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CamelDebuggerTest {

    private static final String DEPLOYMENT_ID = "deployment-1";
    private static final String SESSION_ID = "session-1";
    private static final String NODE_ID = "450e8d96-c2f1-48f5-854d-7540122d5d51";
    private static final String ELEMENT_TYPE = ChainElementType.SERVICE_CALL.getText();
    private static final String CUSTOM_STEP_ID = "request--" + NODE_ID;

    @Mock
    private ServerConfiguration serverConfiguration;
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
    private CamelDebuggerPropertiesService propertiesService;
    @Mock
    private CamelExchangeContextPropagation exchangeContextPropagation;
    @Mock
    private BlueGreenStateService blueGreenStateService;
    @Mock
    private State blueGreenState;

    private JsonSessionStepCoordinator jsonSessionStepCoordinator;

    private CamelDebugger debugger;
    private CamelDebuggerProperties dbgProperties;
    private DeploymentRuntimeProperties runtimeProperties;

    @BeforeEach
    void setUp() {
        jsonSessionStepCoordinator = new JsonSessionStepCoordinator(sessionsService, payloadExtractor,
                serverConfiguration);
        debugger = new CamelDebugger(
                serverConfiguration,
                tracingService,
                checkpointSessionService,
                metricsService,
                chainLogger,
                Optional.empty(),
                sessionsService,
                payloadExtractor,
                variablesService,
                propertiesService,
                exchangeContextPropagation,
                blueGreenStateService,
                jsonSessionStepCoordinator);
        debugger.setDeploymentId(DEPLOYMENT_ID);

        dbgProperties = mock(CamelDebuggerProperties.class);
        runtimeProperties = mock(DeploymentRuntimeProperties.class);

        lenient().when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        lenient().when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        lenient().when(dbgProperties.getMaskedFields()).thenReturn(java.util.Collections.emptySet());
        lenient().when(dbgProperties.getDeploymentInfo()).thenReturn(
                DeploymentInfo.builder().deploymentId(DEPLOYMENT_ID).chainId("chain-1")
                        .chainName("chain").containsCheckpointElements(false).build());
        lenient().when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        lenient().when(runtimeProperties.isMaskingEnabled()).thenReturn(false);
        lenient().when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);
        lenient().when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        lenient().when(tracingService.isTracingEnabled()).thenReturn(false);
        lenient().when(serverConfiguration.getDomain()).thenReturn("domain");
        lenient().when(serverConfiguration.getHost()).thenReturn("host");
        lenient().when(blueGreenStateService.getBlueGreenStateValue()).thenReturn(blueGreenState);
        lenient().when(blueGreenState.getName()).thenReturn("active");

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
    void getRelatedPropertiesShouldDelegateToService() {
        CamelDebuggerProperties result = debugger.getRelatedProperties(createExchange());

        assertEquals(dbgProperties, result);
        verify(propertiesService).getProperties(any(), eq(DEPLOYMENT_ID));
    }

    @Test
    void onEventExchangeCreatedShouldInitializeSession() {
        Exchange exchange = createExchange();
        Session session = mock(Session.class);
        lenient().when(sessionsService.startSession(any(), any(), anyString(), any(), anyString(), anyString(),
                anyString())).thenReturn(session);
        try (var checkpointUtilsMock = mockStatic(CheckpointUtils.class)) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(any()))
                    .thenReturn(null);

            ExchangeCreatedEvent event = mock(ExchangeCreatedEvent.class);
            debugger.onEvent(exchange, event);

            assertNotNull(exchange.getProperty(Properties.SESSION_ID));
            verify(sessionsService).startSession(any(), any(), anyString(), any(), anyString(), anyString(),
                    anyString());
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
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn("non-uuid-node");

        debugger.afterProcess(exchange, processor, definition, 0L);

        assertInstanceOf(ChainExecutionTimeoutException.class, exchange.getException());
    }

    @Test
    void afterProcessShouldLogSessionElementAfterOnDebugLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(sessionsService).logSessionElementAfter(any(), any(), eq(SESSION_ID), any(), any(), any(), any(), any());
        verify(chainLogger).logAfterProcess(any(), any(), any(), any(), any(), eq(NODE_ID), eq(10L));
    }

    @Test
    void afterProcessShouldNotLogSessionElementAfterOnErrorLevelWhenNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(sessionsService, never()).logSessionElementAfter(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void beforeProcessShouldLogSessionElementBeforeOnDebugLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.beforeProcess(exchange, processor, definition);

        verify(sessionsService).logSessionElementBefore(any(), any(), eq(SESSION_ID), any(), eq(NODE_ID), any(), any(),
                any(), any());
        verify(chainLogger).logBeforeProcess(any(), any(), any(), any(), any(), eq(NODE_ID));
    }

    @Test
    void beforeProcessShouldPutElementToSingleCacheOnErrorLevel() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.beforeProcess(exchange, processor, definition);

        verify(sessionsService).putElementToSingleElCache(any(), any(), eq(SESSION_ID), any(), eq(NODE_ID), any(),
                any(), any(), any());
        verify(sessionsService, never()).logSessionElementBefore(any(), any(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void finishCheckpointSessionShouldSaveAndWarnWhenCompletedWithErrors() {
        SessionInfo sessionInfo = mock(SessionInfo.class);
        when(checkpointSessionService.findSession(SESSION_ID)).thenReturn(sessionInfo);

        debugger.finishCheckpointSession(createExchange(), dbgProperties, SESSION_ID,
                ExecutionStatus.COMPLETED_WITH_ERRORS, 100L);

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

        debugger.finishCheckpointSession(createExchange(), dbgProperties, SESSION_ID,
                ExecutionStatus.COMPLETED_NORMALLY, 100L);

        verify(checkpointSessionService).removeAllRelatedCheckpoints("sid", true);
    }

    @Test
    void logAfterStepFinishedShouldLogRetryAttemptForServiceCallOnWarnLevel() {
        Exchange exchange = createExchange();
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);

        debugger.logAfterStepFinished(exchange, dbgProperties, CamelNames.REQUEST_ATTEMPT_STEP_PREFIX, "elem-1",
                ChainElementType.SERVICE_CALL);

        verify(chainLogger).logRetryRequestAttempt(any(), any(), eq("elem-1"));
    }

    @Test
    void logAfterStepFinishedShouldNotLogRetryAttemptForNonServiceCall() {
        Exchange exchange = createExchange();
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.WARN);

        debugger.logAfterStepFinished(exchange, dbgProperties, CamelNames.REQUEST_ATTEMPT_STEP_PREFIX, "elem-1",
                ChainElementType.HTTP_SENDER);

        verify(chainLogger, never()).logRetryRequestAttempt(any(), any(), any());
    }

    @Test
    void afterProcessShouldRecordStepAfterWhenSessionLogDetailsFull() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(NODE_ID, "sess-el-1");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 5L);

        verify(sessionsService).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldNotRecordStepAfterWhenSessionLogDetailsOff() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(CUSTOM_STEP_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldNotRecordStepAfterWhenIsStepNode() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));
        Set<String> stepIds = ConcurrentHashMap.newKeySet();
        stepIds.add(NODE_ID);
        exchange.setProperty(Properties.SESSION_STEP_IDS, stepIds);

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
    }

    @Test
    void afterProcessShouldExtractPayloadWhenDetailsFullEvenIfNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(payloadExtractor).extractBodyForLogging(any(), any(), anyBoolean());
        verify(payloadExtractor).extractHeadersForLogging(any(), any(), anyBoolean());
    }

    @Test
    void afterProcessShouldNotExtractPayloadWhenDetailsOffAndNotLogged() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(payloadExtractor, never()).extractBodyForLogging(any(), any(), anyBoolean());
    }

    @Test
    void afterProcessShouldResolveSessionElementIdViaSplitIdChain() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        exchange.setProperty(Properties.SPLIT_ID_CHAIN, ":split");
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(NODE_ID + ":split", "sess-split");
        executionMap.put(NODE_ID, "sess-fallback");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(NODE_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));
        @SuppressWarnings("unchecked")
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        executionMap.put(CUSTOM_STEP_ID, "sess-custom");

        Processor processor = mock(Processor.class);
        NamedNode definition = mock(NamedNode.class);
        when(definition.getId()).thenReturn(CUSTOM_STEP_ID);

        debugger.afterProcess(exchange, processor, definition, 10L);

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService).logSessionElementAfter(any(), any(), eq(SESSION_ID), eq("sess-custom"), any(), any(), any(), any());
    }

    @Test
    void onEventStepStartedShouldRegisterStepNodeIds() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, true);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));
        lenient().when(dbgProperties.getElementProperty(CUSTOM_STEP_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        StepStartedEvent event = mock(StepStartedEvent.class);
        when(event.getStepId()).thenReturn(CUSTOM_STEP_ID);

        debugger.onEvent(exchange, event);

        @SuppressWarnings("unchecked")
        Map<String, String> nodeIds = (Map<String, String>) exchange.getProperty(Properties.SESSION_STEP_NODE_IDS);
        assertNotNull(nodeIds);
        assertTrue(nodeIds.containsValue(NODE_ID));

        @SuppressWarnings("unchecked")
        Set<String> stepIds = (Set<String>) exchange.getProperty(Properties.SESSION_STEP_IDS);
        assertNotNull(stepIds);
        assertTrue(stepIds.contains(NODE_ID));
    }

    @Test
    void stepFinishedShouldRecordStepAfterForRegularStep() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.SESSION_SHOULD_BE_LOGGED, false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.DEBUG);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        String sessElId = "step-sess-1";
        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push(sessElId);

        StepCompletedEvent event = mock(StepCompletedEvent.class);
        when(event.getStepId()).thenReturn(NODE_ID);

        debugger.onEvent(exchange, event);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        String sessElId = "step-sess-2";
        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push(sessElId);

        StepCompletedEvent event = mock(StepCompletedEvent.class);
        when(event.getStepId()).thenReturn(CUSTOM_STEP_ID);

        debugger.onEvent(exchange, event);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        @SuppressWarnings("unchecked")
        Deque<String> steps = (Deque<String>) exchange.getProperty(Properties.STEPS);
        steps.push("step-sess-3");

        StepCompletedEvent event = mock(StepCompletedEvent.class);
        when(event.getStepId()).thenReturn(NODE_ID);

        debugger.onEvent(exchange, event);

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
        when(dbgProperties.getElementProperty(NODE_ID)).thenReturn(
                Map.of(ChainProperties.ELEMENT_TYPE, ELEMENT_TYPE));

        StepCompletedEvent event = mock(StepCompletedEvent.class);
        when(event.getStepId()).thenReturn(NODE_ID);

        debugger.onEvent(exchange, event);

        verify(sessionsService, never()).recordStepAfter(any(SessionStepLogContext.class));
        verify(sessionsService, never()).recordStepAfterForStep(any(SessionStepLogContext.class));
    }

    @Test
    void exchangeCreatedShouldSetLoggerContextWithTracingFlag() {
        Exchange exchange = createExchange();
        when(tracingService.isTracingEnabled()).thenReturn(true);
        Session session = mock(Session.class);
        lenient().when(sessionsService.startSession(any(), any(), anyString(), any(), anyString(), anyString(),
                anyString())).thenReturn(session);
        try (var checkpointUtilsMock = mockStatic(CheckpointUtils.class)) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(any()))
                    .thenReturn(null);

            ExchangeCreatedEvent event = mock(ExchangeCreatedEvent.class);
            debugger.onEvent(exchange, event);

            verify(chainLogger).setLoggerContext(exchange, dbgProperties, null, true);
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

        lenient().doAnswer(inv -> {
            exceptionRef.set(inv.getArgument(0));
            return null;
        }).when(ex).setException(any());
        lenient().when(ex.getException()).thenAnswer(inv -> exceptionRef.get());

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

        props.put(Properties.ELEMENT_EXECUTION_MAP, new ConcurrentHashMap<String, String>());
        props.put(Properties.STEPS, new ConcurrentLinkedDeque<String>());
        props.put(Properties.START_TIME_MS, 0L);
        props.put(Properties.CHAIN_TIME_OUT_AFTER, 0L);
        headers.put(org.apache.camel.Exchange.HTTP_PATH, "");
        return ex;
    }
}
