package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.LogExchangeMarkers;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JsonSessionStepLoggerTest {

    @Mock
    private PayloadExtractor extractor;

    @Mock
    private ChainRuntimePropertiesService chainRuntimePropertiesService;

    @Mock
    private LogExchangeMarkers logExchangeMarkers;

    private Logger mockLog;
    private Logger originalLog;

    @BeforeEach
    void setUp() throws Exception {
        originalLog = getStaticLogger();
        mockLog = mock(Logger.class);
        lenient().when(mockLog.isInfoEnabled()).thenReturn(true);
        setStaticLogger(mockLog);

        lenient().when(extractor.convertToJson(any())).thenReturn("{}");
        lenient().when(logExchangeMarkers.buildExchangeMarkers(any(), any(), any())).thenAnswer(a -> {
            LogExchangeMarkers real = new LogExchangeMarkers();
            return real.buildExchangeMarkers(a.getArgument(0), a.getArgument(1), a.getArgument(2));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        setStaticLogger(originalLog);
    }

    @Test
    void recordStepAfterBuildsRecordAndLogs() {
        Exchange exchange = mockExchange();
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));
        }

        SessionStepLogRecord rec = ref.get();
        assertNotNull(rec);
        assertEquals("dom", rec.domain());
        assertEquals("snap", rec.snapshotName());
        assertEquals("sessElem", rec.elementId());
        assertEquals("chainElem", rec.chainElementId());
        assertEquals("http-sender", rec.camelElementName());
        assertEquals("elemName", rec.elementName());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, rec.executionStatus());
        assertNull(rec.parentElementId());
        verify(spy).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDoesNotLogWhenDetailsOff() {
        Exchange exchange = mockExchange();
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.OFF);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDeduplicatesSameKey() {
        Exchange exchange = mockExchange();
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            SessionStepLogContext ctx = context(exchange, "sessElem", "node1", "dom");
            spy.recordStepAfter(ctx);
            ref.set(null);
            spy.recordStepAfter(ctx);
        }

        assertNull(ref.get());
        verify(spy, times(1)).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDoesNotLogWhenNodeIdEmpty() {
        Exchange exchange = mockExchange();
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, "sessElem", "", "dom"));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDoesNotLogWhenElementPropertiesMissing() {
        Exchange exchange = mockExchange();
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.empty());

            spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));
        }

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterResolvesParentFromElementProperty() {
        Exchange exchange = mockExchange();
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        elementInfo.setParentId("parentId");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));
        }

        assertEquals("parentId", ref.get().parentElementId());
    }

    @Test
    void recordStepAfterResolvesParentFromSteps() {
        Exchange exchange = mockExchange();
        Deque<String> steps = new ArrayDeque<>();
        steps.add("stepParent");
        steps.add("stepChild");
        exchange.setProperty(CamelConstants.Properties.STEPS, steps);
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));
        }

        assertEquals("stepParent", ref.get().parentElementId());
    }

    @Test
    void recordStepAfterResolvesParentFromExecutionMap() {
        Exchange exchange = mockExchange();
        Deque<String> steps = new ArrayDeque<>();
        steps.add("p");
        exchange.setProperty(CamelConstants.Properties.STEPS, steps);
        Map<String, String> executionMap = new HashMap<>();
        executionMap.put("p", "execParent");
        exchange.setProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP, executionMap);
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "node1", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfter(context(exchange, "sessElem", "node1", "dom"));
        }

        assertEquals("execParent", ref.get().parentElementId());
    }

    @Test
    void recordStepAfterForStepBuildsRecordAndLogs() {
        Exchange exchange = mockExchange();
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "stepChainElementId", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                    "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), "dom", null));
        }

        SessionStepLogRecord rec = ref.get();
        assertNotNull(rec);
        assertEquals("stepName", rec.elementName());
        assertEquals("sessElem", rec.elementId());
        assertEquals("chainElem", rec.chainElementId());
        assertEquals("http-sender", rec.camelElementName());
        verify(spy).logAfter(any(), any());
    }

    @Test
    void recordStepAfterForStepDoesNotLogWhenStepNameEmpty() {
        Exchange exchange = mockExchange();
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                null, "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), "dom", null));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterForStepDoesNotLogWhenParentPropertiesMissing() {
        Exchange exchange = mockExchange();
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "stepChainElementId", ElementInfo.class))
                    .thenReturn(Optional.empty());

            spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                    "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), "dom", null));
        }

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterForStepResolvesParentFromSteps() {
        Exchange exchange = mockExchange();
        Deque<String> steps = new ArrayDeque<>();
        steps.add("a");
        steps.add("b");
        exchange.setProperty(CamelConstants.Properties.STEPS, steps);
        ElementInfo elementInfo = elementInfo("chainElem", "http-sender", "elemName");
        ChainRuntimeProperties props = runtimeProperties(SessionLogDetails.FULL);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange)).thenReturn(props);
        DeploymentInfo deploymentInfo = deploymentInfo("snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        try (MockedStatic<MetadataUtil> mocked = mockStatic(MetadataUtil.class)) {
            mocked.when(() -> MetadataUtil.lookupBeanForElement(exchange, "stepChainElementId", ElementInfo.class))
                    .thenReturn(Optional.of(elementInfo));
            mocked.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                    "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), "dom", null));
        }

        assertEquals("b", ref.get().parentElementId());
    }

    @Test
    void logAfterDoesNotLogWhenDetailsNull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("http-sender"), null);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logAfterDoesNotLogWhenDetailsOff() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.OFF);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logAfterDoesNotLogForSendersWhenElementNotInfoLevel() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("try"), SessionLogDetails.SENDERS);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logAfterDoesNotLogForSendersWhenElementTypeUnknown() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord(null), SessionLogDetails.SENDERS);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logAfterLogsForSendersWhenInfoLevelElement() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.SENDERS);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLog).info(captor.capture(), any(Object[].class));
        assertTrue(captor.getValue().contains("Session step finished with status"));
    }

    @Test
    void logAfterLogsForFullDetails() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("try"), SessionLogDetails.FULL);
        verify(mockLog).info(anyString(), any(Object[].class));
    }

    @Test
    void logAfterDoesNotLogWhenInfoLevelDisabled() {
        lenient().when(mockLog.isInfoEnabled()).thenReturn(false);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.FULL);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
        lenient().when(mockLog.isInfoEnabled()).thenReturn(true);
    }

    @Test
    void logSessionStartDoesNotLogWhenDetailsOff() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        logger.logSessionStart(session, SessionLogDetails.OFF);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logSessionStartDoesNotLogWhenDetailsNull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        logger.logSessionStart(session, null);
        verify(mockLog, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void logSessionStartLogsWhenDetailsFull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(extractor, chainRuntimePropertiesService, logExchangeMarkers);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        when(session.getSnapshotName()).thenReturn("snap");
        logger.logSessionStart(session, SessionLogDetails.FULL);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLog).info(captor.capture(), any(Object[].class));
        assertEquals("Session started", captor.getValue());
    }

    private JsonSessionStepLogger spyCapturing(JsonSessionStepLogger logger, AtomicReference<SessionStepLogRecord> ref) {
        JsonSessionStepLogger spy = spy(logger);
        doAnswer(invocation -> {
            ref.set(invocation.getArgument(0));
            return null;
        }).when(spy).logAfter(any(), any());
        return spy;
    }

    private SessionStepLogContext context(Exchange exchange, String sessionElementId, String nodeId, String domain) {
        return new SessionStepLogContext(exchange, "sessionId", sessionElementId, nodeId,
                null, null, "body", Map.of(), Map.of(), Map.of(), domain, null);
    }

    private ElementInfo elementInfo(String elementId, String elementType, String elementName) {
        return ElementInfo.builder()
                .id(elementId)
                .type(elementType)
                .name(elementName)
                .build();
    }

    private DeploymentInfo deploymentInfo(String snapshotName) {
        return DeploymentInfo.builder()
                .snapshot(SnapshotInfo.builder().name(snapshotName).id("snap-id").build())
                .build();
    }

    private ChainRuntimeProperties runtimeProperties(SessionLogDetails details) {
        return ChainRuntimeProperties.builder()
                .sessionLogDetails(details)
                .build();
    }

    private SessionStepLogRecord createRecord(String camelElementName) {
        return new SessionStepLogRecord("dom", null, "snap", null, camelElementName,
                ExecutionStatus.COMPLETED_NORMALLY, "body", "{}", "{}", "elemName", "elemId", "chainElem");
    }

    private Exchange mockExchange() {
        Exchange exchange = mock(Exchange.class);
        Map<String, Object> props = new HashMap<>();
        lenient().when(exchange.getProperty(anyString())).thenAnswer(a -> props.get(a.getArgument(0)));
        lenient().when(exchange.getProperty(anyString(), any(Class.class))).thenAnswer(a -> props.get(a.getArgument(0)));
        lenient().when(exchange.getProperty(anyString(), any())).thenAnswer(a -> props.get(a.getArgument(0)));
        lenient().when(exchange.getProperty(anyString(), any(), any(Class.class)))
                .thenAnswer(a -> props.get(a.getArgument(0)));
        lenient().doAnswer(a -> {
            props.put(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(exchange).setProperty(anyString(), any());
        return exchange;
    }

    private Logger getStaticLogger() throws Exception {
        Field field = JsonSessionStepLogger.class.getDeclaredField("LOG");
        field.setAccessible(true);
        return (Logger) field.get(null);
    }

    private void setStaticLogger(Logger logger) throws Exception {
        Field field = JsonSessionStepLogger.class.getDeclaredField("LOG");
        field.setAccessible(true);
        try {
            // Try Unsafe to bypass final
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Object staticBase = unsafe.staticFieldBase(field);
            long staticOffset = unsafe.staticFieldOffset(field);
            unsafe.putObject(staticBase, staticOffset, logger);
            return;
        } catch (Exception e) {
            // fallback to classic reflection
        }
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (NoSuchFieldException expected) {
            // Modifiers field removed in newer JDKs, nothing to adjust
        }
        try {
            field.set(null, logger);
            return;
        } catch (IllegalAccessException e) {
            // fallback to VarHandle
        }
        try {
            var lookup = MethodHandles.privateLookupIn(JsonSessionStepLogger.class, MethodHandles.lookup());
            var handle = lookup.findStaticVarHandle(JsonSessionStepLogger.class, "LOG", Logger.class);
            handle.set(logger);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set LOG field", t);
        }
    }
}
