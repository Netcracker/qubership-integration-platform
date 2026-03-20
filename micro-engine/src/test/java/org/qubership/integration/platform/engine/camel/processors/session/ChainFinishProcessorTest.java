package org.qubership.integration.platform.engine.camel.processors.session;

import jakarta.enterprise.inject.Instance;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainFinishProcessorTest {

    private static final String SESSION_ID_1 = "8d0a4c4f-6f49-4c8e-b0fd-54d8d9f2c1a1";
    private static final String SESSION_ID_2 = "1f4e2a7d-9b42-4d1f-8a4f-6c0c7e12b202";
    private static final String SESSION_ID_3 = "0bb6f7f0-2d31-44f7-ae2b-98a5f8a4c303";
    private static final String SESSION_ID_4 = "7a9dd8ae-1d55-4dc4-b665-f4c02c18d404";
    private static final String SESSION_ID_5 = "d96b5a0e-8f02-48d4-92d0-0e9b3b77e505";
    private static final String SESSION_ID_6 = "c8ad2a61-34b5-4f54-88de-53346a3af606";
    private static final String SESSION_ID_7 = "4e95e73b-56b6-47f9-a42f-f6d4d06ce707";
    private static final String SESSION_ID_8 = "a41bc0f3-1f4b-4a2a-a5e7-2c95b1896808";
    private static final String SESSION_ID_9 = "f4c0a9de-96d8-4a24-b54e-14d60d5a9909";
    private static final String SESSION_ID_10 = "6bc1c7c9-b5f4-45aa-94f2-2213c8b7a010";
    private static final String SESSION_ID_11 = "9db9f466-0f18-4b42-87f6-e2ff30a61b11";
    private static final String SESSION_ID_12 = "5f0f2e84-cc91-4ff4-8e47-476f85f68d12";
    private static final String SESSION_ID_13 = "2e0fdc5b-f334-4a8a-a3d3-10dbb5cf3e13";
    private static final String SESSION_ID_14 = "be8668dd-5c24-4f14-bdd0-9e4ee6462f14";
    private static final String SESSION_ID_15 = "3cd1b9fb-7d8c-4df3-bdc4-d93d93072d15";

    private static final String SESSION_ELEMENT_ID_1 = "f0b4dc0e-1f6b-4375-8eb5-e8b3f0f93116";
    private static final String JOB_EXECUTION_ID_1 = "34d2d7cb-6f37-44c1-9f90-91f9c8f5a117";
    private static final String JOB_EXECUTION_ID_2 = "88c6b2de-3e4a-4dc2-95ae-5c61d1c41218";
    private static final String JOB_EXECUTION_ID_3 = "71f65f3e-0c8c-44a2-bc0c-7f018b8c9a19";

    private static final String PARENT_SESSION_ID_1 = "be4c8702-ff24-4c83-bf89-81de1d944a20";
    private static final String ORIGINAL_SESSION_ID_1 = "f69d4d34-f6bb-4db2-a2e3-65d24b767421";
    private static final String PARENT_SESSION_ID_2 = "aefb2fc2-47df-4a91-92a8-d646d6f6f522";
    private static final String ORIGINAL_SESSION_ID_2 = "c1f6df1e-9a4a-4530-9cf4-486c0f49ec23";

    @Mock
    private MetricsService metricsService;
    @Mock
    private CamelDebuggerPropertiesService propertiesService;
    @Mock
    private SessionsService sessionsService;
    @Mock
    private SessionsKafkaReportingService sessionsKafkaReportingService;
    @Mock
    private SdsService sdsService;
    @Mock
    private ChainLogger chainLogger;
    @Mock
    private PayloadExtractor payloadExtractor;
    @Mock
    private CamelDebugger camelDebugger;
    @Mock
    private CamelDebuggerProperties dbgProperties;
    @Mock
    private DeploymentRuntimeProperties runtimeProperties;

    private final DeploymentInfo deploymentInfo = DeploymentInfo.builder()
            .deploymentId("a2f7c1d4-5b74-4d8d-8d3a-4831e4cb9924")
            .chainId("c4f2ab78-9d93-4f0f-8f79-2d55d9d4e125")
            .chainName("Test chain")
            .snapshotId("d98e98f7-f6d2-4ef8-bf66-6d6f1b6f0d26")
            .snapshotName("Test snapshot")
            .containsCheckpointElements(false)
            .containsSchedulerElements(false)
            .build();

    @Test
    void shouldUpdateThreadStatusAndAccumulateSyncDurationWhenActiveThreadsRemain() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.of(sessionsService),
                Optional.of(sessionsKafkaReportingService),
                Optional.of(sdsService)
        );
        Exchange exchange = createExchange();

        AtomicInteger activeThreadCounter = new AtomicInteger(2);
        Map<Long, ExecutionStatus> threadStatuses = new HashMap<>();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, activeThreadCounter);
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, threadStatuses);
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_1);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, true);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        assertEquals(1, activeThreadCounter.get());
        assertEquals(
                ExecutionStatus.COMPLETED_NORMALLY,
                threadStatuses.get(Thread.currentThread().threadId())
        );
        assertTrue(getSyncDurationMap(processor).containsKey(SESSION_ID_1));
        assertTrue(getSyncDurationMap(processor).get(SESSION_ID_1) > 0);

        verifyNoInteractions(metricsService, propertiesService, chainLogger, payloadExtractor);
    }

    @Test
    void shouldFinishSessionWhenLastThreadCompletedNormally() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.of(sessionsService),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_2);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        getSyncDurationMap(processor).put(SESSION_ID_2, 123L);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(camelDebugger).finishCheckpointSession(
                eq(exchange),
                eq(dbgProperties),
                eq(SESSION_ID_2),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyLong()
        );
        verify(sessionsService).finishSession(
                eq(exchange),
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyString(),
                anyLong(),
                eq(123L)
        );
        verify(metricsService).processSessionFinish(
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_NORMALLY.toString()),
                anyLong()
        );
        assertFalse(getSyncDurationMap(processor).containsKey(SESSION_ID_2));
    }

    @Test
    void shouldLogSingleElementAndProcessFailureMetricsWhenCompletedWithErrors() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.of(sessionsService),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        Exception lastException = new IllegalStateException("boom");

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_3);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(Properties.LAST_EXCEPTION, lastException);
        exchange.setProperty(Properties.LAST_EXCEPTION_ERROR_CODE, ErrorCode.SERVICE_RETURNED_ERROR);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockSessionLevel(exchange, SessionsLoggingLevel.ERROR);
        mockMaskingEnabled(true);
        mockDeploymentInfo();

        when(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID_3)).thenReturn(SESSION_ELEMENT_ID_1);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_WITH_ERRORS);

            processor.process(exchange);
        }

        verify(sessionsService).logSessionElementAfter(
                eq(exchange),
                same(lastException),
                eq(SESSION_ID_3),
                eq(SESSION_ELEMENT_ID_1),
                anySet(),
                eq(true)
        );
        verify(metricsService).processChainFailure(
                eq(deploymentInfo),
                eq(ErrorCode.SERVICE_RETURNED_ERROR)
        );
        verify(camelDebugger).finishCheckpointSession(
                eq(exchange),
                eq(dbgProperties),
                eq(SESSION_ID_3),
                eq(ExecutionStatus.COMPLETED_WITH_ERRORS),
                anyLong()
        );
    }

    @Test
    void shouldNotLogSingleElementAfterWhenMovedSingleElementIdEmpty() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.of(sessionsService),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_4);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockSessionLevel(exchange, SessionsLoggingLevel.ERROR);
        mockDeploymentInfo();

        when(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID_4)).thenReturn("");

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_WITH_ERRORS);

            processor.process(exchange);
        }

        verify(sessionsService, never()).logSessionElementAfter(
                eq(exchange),
                same(null),
                eq(SESSION_ID_4),
                anyString(),
                anySet(),
                eq(true)
        );
        verify(metricsService).processChainFailure(
                eq(deploymentInfo),
                eq(ErrorCode.UNEXPECTED_BUSINESS_ERROR)
        );
    }

    @Test
    void shouldLogExchangeFinishedWithFilteredPayloadWhenInfoLoggingEnabled() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_5);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.INFO);
        mockMaskingEnabled(false);

        when(runtimeProperties.getLogPayload()).thenReturn(Set.of(LogPayload.HEADERS));
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(chainLogger).logExchangeFinished(
                eq(dbgProperties),
                eq("<body not logged>"),
                eq("{header=value}"),
                eq("<properties not logged>"),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyLong()
        );
    }

    @Test
    void shouldLogBodyWhenDeprecatedLogPayloadEnabledAndLogPayloadSettingsAbsent() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_6);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.INFO);
        mockMaskingEnabled(false);

        when(runtimeProperties.isLogPayloadEnabled()).thenReturn(true);
        when(runtimeProperties.getLogPayload()).thenReturn(null);
        when(payloadExtractor.extractHeadersForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Map.of("header", "value"));
        when(payloadExtractor.extractExchangePropertiesForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn(Collections.emptyMap());
        when(payloadExtractor.extractBodyForLogging(eq(exchange), anySet(), eq(false)))
                .thenReturn("body");

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(chainLogger).logExchangeFinished(
                eq(dbgProperties),
                eq("body"),
                eq("{header=value}"),
                eq("{}"),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyLong()
        );
    }

    @Test
    void shouldSendFinishedEventWhenDptEventsEnabled() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.of(sessionsKafkaReportingService),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_7);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID, PARENT_SESSION_ID_1);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID, ORIGINAL_SESSION_ID_1);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockDptEventsEnabled(true);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(sessionsKafkaReportingService).sendFinishedEvent(
                eq(exchange),
                eq(dbgProperties),
                eq(SESSION_ID_7),
                eq(ORIGINAL_SESSION_ID_1),
                eq(PARENT_SESSION_ID_1),
                eq(ExecutionStatus.COMPLETED_NORMALLY)
        );
    }

    @Test
    void shouldContinueWhenSendFinishedEventThrowsException() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.of(sessionsKafkaReportingService),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_8);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID, PARENT_SESSION_ID_2);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID, ORIGINAL_SESSION_ID_2);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockDptEventsEnabled(true);

        doThrow(new RuntimeException("kafka failed")).when(sessionsKafkaReportingService)
                .sendFinishedEvent(
                        eq(exchange),
                        eq(dbgProperties),
                        eq(SESSION_ID_8),
                        eq(ORIGINAL_SESSION_ID_2),
                        eq(PARENT_SESSION_ID_2),
                        eq(ExecutionStatus.COMPLETED_NORMALLY)
                );

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(metricsService).processSessionFinish(
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_NORMALLY.toString()),
                anyLong()
        );
    }

    @Test
    void shouldSetSdsJobInstanceFailedWhenExecutionCompletedWithErrors() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.of(sdsService)
        );
        Exchange exchange = createExchange();

        RuntimeException exception = new RuntimeException("sds failure");

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_9);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(CamelConstants.Properties.SDS_EXECUTION_ID_PROP, JOB_EXECUTION_ID_1);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockDeploymentInfo();

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_WITH_ERRORS);
            debuggerUtils.when(() -> DebuggerUtils.getExceptionFromExchange(exchange))
                    .thenReturn(exception);

            processor.process(exchange);
        }

        verify(metricsService).processChainFailure(
                eq(deploymentInfo),
                eq(ErrorCode.UNEXPECTED_BUSINESS_ERROR)
        );
        verify(sdsService).setJobInstanceFailed(JOB_EXECUTION_ID_1, exception);
    }

    @Test
    void shouldSetSdsJobInstanceFinishedWhenExecutionCompletedNormally() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.of(sdsService)
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_10);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(CamelConstants.Properties.SDS_EXECUTION_ID_PROP, JOB_EXECUTION_ID_2);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(sdsService).setJobInstanceFinished(JOB_EXECUTION_ID_2);
    }

    @Test
    void shouldContinueWhenProcessChainFailureThrowsException() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_11);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockDeploymentInfo();

        doThrow(new RuntimeException("failure metrics failed")).when(metricsService)
                .processChainFailure(eq(deploymentInfo), eq(ErrorCode.UNEXPECTED_BUSINESS_ERROR));

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_WITH_WARNINGS);

            processor.process(exchange);
        }

        verify(metricsService).processSessionFinish(
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_WITH_WARNINGS.toString()),
                anyLong()
        );
    }

    @Test
    void shouldContinueWhenProcessSessionFinishThrowsException() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.of(sdsService)
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_12);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(CamelConstants.Properties.SDS_EXECUTION_ID_PROP, JOB_EXECUTION_ID_3);

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);

        doThrow(new RuntimeException("session metrics failed")).when(metricsService)
                .processSessionFinish(
                        eq(dbgProperties),
                        eq(ExecutionStatus.COMPLETED_NORMALLY.toString()),
                        anyLong()
                );

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(sdsService).setJobInstanceFinished(JOB_EXECUTION_ID_3);
    }

    @Test
    void shouldCalculateHigherPriorityExecutionStatusAcrossThreads() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        Map<Long, ExecutionStatus> threadStatuses = new HashMap<>();
        threadStatuses.put(999L, ExecutionStatus.COMPLETED_WITH_ERRORS);

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, threadStatuses);
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_13);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);
        mockDeploymentInfo();

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(camelDebugger).finishCheckpointSession(
                eq(exchange),
                eq(dbgProperties),
                eq(SESSION_ID_13),
                eq(ExecutionStatus.COMPLETED_WITH_ERRORS),
                anyLong()
        );
        verify(metricsService).processChainFailure(
                eq(deploymentInfo),
                eq(ErrorCode.UNEXPECTED_BUSINESS_ERROR)
        );
        verify(metricsService).processSessionFinish(
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_WITH_ERRORS.toString()),
                anyLong()
        );
    }

    @Test
    void shouldRemoveSyncDurationEntryAndPassAccumulatedSyncDurationWhenMainExchangeFinishes() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.of(sessionsService),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_14);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, true);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        ArgumentCaptor<Long> syncDurationCaptor = ArgumentCaptor.forClass(Long.class);

        verify(sessionsService).finishSession(
                eq(exchange),
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyString(),
                anyLong(),
                syncDurationCaptor.capture()
        );

        assertTrue(syncDurationCaptor.getValue() > 0);
        assertFalse(getSyncDurationMap(processor).containsKey(SESSION_ID_14));
    }

    @Test
    void shouldFinishSessionWhenThreadStatusesPropertyMissing() throws Exception {
        ChainFinishProcessor processor = createProcessor(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID_15);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        mockFinalizationProperties(exchange, LogLoggingLevel.ERROR);

        try (MockedStatic<DebuggerUtils> debuggerUtils = mockStatic(DebuggerUtils.class)) {
            debuggerUtils.when(() -> DebuggerUtils.extractExecutionStatus(exchange))
                    .thenReturn(ExecutionStatus.COMPLETED_NORMALLY);

            processor.process(exchange);
        }

        verify(camelDebugger).finishCheckpointSession(
                eq(exchange),
                eq(dbgProperties),
                eq(SESSION_ID_15),
                eq(ExecutionStatus.COMPLETED_NORMALLY),
                anyLong()
        );
        verify(metricsService).processSessionFinish(
                eq(dbgProperties),
                eq(ExecutionStatus.COMPLETED_NORMALLY.toString()),
                anyLong()
        );
    }

    private ChainFinishProcessor createProcessor(
            Optional<SessionsService> sessionsServiceOptional,
            Optional<SessionsKafkaReportingService> sessionsKafkaReportingServiceOptional,
            Optional<SdsService> sdsServiceOptional
    ) {
        @SuppressWarnings("unchecked")
        Instance<SessionsService> sessionsServiceInstance = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<SessionsKafkaReportingService> sessionsKafkaReportingServiceInstance = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<SdsService> sdsServiceInstance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtil = mockStatic(InjectUtil.class)) {
            injectUtil.when(() -> InjectUtil.injectOptional(sessionsServiceInstance))
                    .thenReturn(sessionsServiceOptional);
            injectUtil.when(() -> InjectUtil.injectOptional(sessionsKafkaReportingServiceInstance))
                    .thenReturn(sessionsKafkaReportingServiceOptional);
            injectUtil.when(() -> InjectUtil.injectOptional(sdsServiceInstance))
                    .thenReturn(sdsServiceOptional);

            return new ChainFinishProcessor(
                    metricsService,
                    propertiesService,
                    sessionsServiceInstance,
                    sessionsKafkaReportingServiceInstance,
                    sdsServiceInstance,
                    chainLogger,
                    payloadExtractor
            );
        }
    }

    private Exchange createExchange() {
        Exchange exchange = MockExchanges.withMessage();
        CamelContext camelContext = mock(CamelContext.class);

        lenient().when(exchange.getContext()).thenReturn(camelContext);
        lenient().when(camelContext.getDebugger()).thenReturn(camelDebugger);

        return exchange;
    }

    private void mockFinalizationProperties(
            Exchange exchange,
            LogLoggingLevel logLevel
    ) {
        when(propertiesService.getProperties(exchange)).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(logLevel);
    }

    private void mockSessionLevel(
            Exchange exchange,
            SessionsLoggingLevel sessionLevel
    ) {
        when(runtimeProperties.calculateSessionLevel(exchange)).thenReturn(sessionLevel);
    }

    private void mockDptEventsEnabled(boolean enabled) {
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(enabled);
    }

    private void mockMaskingEnabled(boolean enabled) {
        when(runtimeProperties.isMaskingEnabled()).thenReturn(enabled);
    }

    private void mockDeploymentInfo() {
        when(dbgProperties.getDeploymentInfo()).thenReturn(deploymentInfo);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSyncDurationMap(ChainFinishProcessor processor)
            throws Exception {
        Field field = ChainFinishProcessor.class.getDeclaredField("syncDurationMap");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) field.get(processor);
    }
}
