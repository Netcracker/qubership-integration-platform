package org.qubership.integration.platform.engine.camel.processors.session;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.AbstractChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainFinishProcessorTest {

    private static final String SESSION_ID = "session-7631ae9";
    private static final String SESSION_ELEMENT_ID = "element-7631ae9";

    @Mock
    private MetricsService metricsService;

    @Mock
    private CamelDebuggerPropertiesService propertiesService;

    @Mock
    private SessionsService sessionsService;

    @Mock
    private AbstractChainLogger chainLogger;

    @Mock
    private PayloadExtractor payloadExtractor;

    @Mock
    private CamelDebugger camelDebugger;

    @Mock
    private CamelDebuggerProperties dbgProperties;

    @Mock
    private DeploymentRuntimeProperties runtimeProperties;

    @Test
    void shouldLogExchangeFinishedWhenSessionLogDetailsEnabledEvenIfLogLevelNotInfo() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.empty(), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.OFF);
        lenient().when(payloadExtractor.extractHeadersForLogging(any(), any(), any(Boolean.class))).thenReturn(Map.of());
        lenient().when(payloadExtractor.extractExchangePropertiesForLogging(any(), any(), any(Boolean.class))).thenReturn(Map.of());
        lenient().when(payloadExtractor.extractBodyForLogging(any(), any(), any(Boolean.class))).thenReturn("body");

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_NORMALLY);
            processor.process(exchange);
        }

        verify(chainLogger).logExchangeFinished(any(), anyString(), anyString(), anyString(), eq(ExecutionStatus.COMPLETED_NORMALLY), anyLong());
    }

    @Test
    void shouldNotLogExchangeFinishedWhenBothDisabled() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.empty(), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.OFF);

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_NORMALLY);
            processor.process(exchange);
        }

        verify(chainLogger, never()).logExchangeFinished(any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void shouldLogExchangeFinishedWhenInfoLevelEnabledRegardlessOfSessionLogDetails() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.empty(), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.INFO);
        lenient().when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        lenient().when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.OFF);
        lenient().when(payloadExtractor.extractHeadersForLogging(any(), any(), any(Boolean.class))).thenReturn(Map.of());
        lenient().when(payloadExtractor.extractExchangePropertiesForLogging(any(), any(), any(Boolean.class))).thenReturn(Map.of());
        lenient().when(payloadExtractor.extractBodyForLogging(any(), any(), any(Boolean.class))).thenReturn("body");

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_NORMALLY);
            processor.process(exchange);
        }

        verify(chainLogger).logExchangeFinished(any(), anyString(), anyString(), anyString(), eq(ExecutionStatus.COMPLETED_NORMALLY), anyLong());
    }

    @Test
    void shouldLogSingleElementAfterWhenCompletedWithErrorsAndSessionLevelError() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.of(sessionsService), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(Properties.LAST_EXCEPTION, new IllegalStateException("boom"));

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(runtimeProperties.isMaskingEnabled()).thenReturn(false);
        when(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID)).thenReturn(SESSION_ELEMENT_ID);

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_WITH_ERRORS);
            processor.process(exchange);
        }

        verify(sessionsService).logSessionElementAfter(eq(exchange), any(), eq(SESSION_ID), eq(SESSION_ELEMENT_ID), any(), eq(false));
    }

    @Test
    void shouldNotLogSingleElementAfterWhenMovedSingleElementIdEmpty() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.of(sessionsService), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());
        exchange.setProperty(Properties.LAST_EXCEPTION, new IllegalStateException("boom"));

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.ERROR);
        when(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID)).thenReturn("");

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_WITH_ERRORS);
            processor.process(exchange);
        }

        verify(sessionsService, never()).logSessionElementAfter(any(), any(), anyString(), anyString(), any(), any(Boolean.class));
    }

    @Test
    void shouldRemoveSyncDurationEntryAndFinishSession() throws Exception {
        ChainFinishProcessor processor = createProcessor(Optional.of(sessionsService), Optional.empty());
        Exchange exchange = createExchange();

        exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        exchange.setProperty(Properties.THREAD_SESSION_STATUSES, new HashMap<>());
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, true);
        exchange.setProperty(CamelConstants.Properties.START_TIME, LocalDateTime.now().minusSeconds(1).toString());

        when(propertiesService.getProperties(any(), anyString())).thenReturn(dbgProperties);
        when(dbgProperties.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        when(runtimeProperties.getLogLoggingLevel()).thenReturn(LogLoggingLevel.ERROR);
        when(runtimeProperties.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);
        when(runtimeProperties.isDptEventsEnabled()).thenReturn(false);
        when(runtimeProperties.calculateSessionLevel(any())).thenReturn(SessionsLoggingLevel.OFF);

        getSyncDurationMap(processor).put(SESSION_ID, 123L);

        try (MockedStatic<DebuggerUtils> utils = mockStatic(DebuggerUtils.class)) {
            utils.when(() -> DebuggerUtils.extractExecutionStatus(any())).thenReturn(ExecutionStatus.COMPLETED_NORMALLY);
            processor.process(exchange);
        }

        verify(sessionsService).finishSession(any(), eq(dbgProperties), eq(ExecutionStatus.COMPLETED_NORMALLY), anyString(), anyLong(), anyLong());
        verify(camelDebugger).finishCheckpointSession(any(), eq(dbgProperties), eq(SESSION_ID), eq(ExecutionStatus.COMPLETED_NORMALLY), anyLong());
    }

    private ChainFinishProcessor createProcessor(Optional<SessionsService> sessionsServiceOptional,
            Optional<SessionsKafkaReportingService> kafkaOptional) {
        Optional<SdsService> sdsOptional = Optional.empty();
        SessionsService service = sessionsServiceOptional.orElse(sessionsService);
        return new ChainFinishProcessor(metricsService, propertiesService,
                service, kafkaOptional, sdsOptional, chainLogger, payloadExtractor);
    }

    private Exchange createExchange() {
        Exchange exchange = mock(Exchange.class, org.mockito.Mockito.withSettings().lenient());
        ConcurrentHashMap<String, Object> props = new ConcurrentHashMap<>();
        lenient().doAnswer(inv -> {
            props.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(exchange).setProperty(anyString(), any());
        lenient().when(exchange.getProperties()).thenReturn(props);
        lenient().when(exchange.getProperty(anyString())).thenAnswer(inv -> props.get(inv.getArgument(0)));
        lenient().when(exchange.getProperty(anyString(), any(Class.class))).thenAnswer(inv -> {
            Object v = props.get(inv.getArgument(0));
            return v == null ? null : inv.<Class<?>>getArgument(1).cast(v);
        });
        lenient().when(exchange.getProperty(anyString(), any(), any(Class.class))).thenAnswer(inv -> {
            Object v = props.get(inv.getArgument(0));
            return v == null ? inv.getArgument(1) : inv.<Class<?>>getArgument(2).cast(v);
        });
        CamelContext camelContext = mock(CamelContext.class);
        lenient().when(exchange.getContext()).thenReturn(camelContext);
        lenient().when(camelContext.getDebugger()).thenReturn(camelDebugger);
        lenient().when(camelDebugger.getDeploymentId()).thenReturn("test-deployment");
        lenient().when(exchange.getContext().getDebugger()).thenReturn(camelDebugger);
        props.put(Properties.SESSION_ACTIVE_THREAD_COUNTER, new AtomicInteger(1));
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSyncDurationMap(ChainFinishProcessor processor) throws Exception {
        Field field = ChainFinishProcessor.class.getDeclaredField("syncDurationMap");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) field.get(processor);
    }
}
