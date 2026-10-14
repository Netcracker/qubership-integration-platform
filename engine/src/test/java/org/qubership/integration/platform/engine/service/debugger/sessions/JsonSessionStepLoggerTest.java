package org.qubership.integration.platform.engine.service.debugger.sessions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.marker.Markers;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.logging.LogExchangeMarkers;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonSessionStepLoggerTest {

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    private LogExchangeMarkers markers;
    private PayloadExtractor extractor;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(JsonSessionStepLogger.class);
        originalLevel = logbackLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.INFO);

        markers = mock(LogExchangeMarkers.class);
        when(markers.buildExchangeMarkers(any(), any(), any())).thenReturn(Markers.append("exchange_body", ""));
        extractor = mock(PayloadExtractor.class);
        when(extractor.convertToJson(any())).thenReturn("{}");
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        logbackLogger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void recordStepAfterBuildsRecordAndLogs() {
        Exchange exchange = mockExchange();
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

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
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.OFF, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDeduplicatesSameKey() {
        Exchange exchange = mockExchange();
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        SessionStepLogContext ctx = context(exchange, dbg, "sessElem", "node1", "dom");
        spy.recordStepAfter(ctx);
        ref.set(null);
        spy.recordStepAfter(ctx);

        assertNull(ref.get());
        verify(spy, times(1)).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDoesNotLogWhenNodeIdEmpty() {
        Exchange exchange = mockExchange();
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "", "dom"));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterDoesNotLogWhenElementPropertiesMissing() {
        Exchange exchange = mockExchange();
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, null, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterResolvesParentFromElementProperty() {
        Exchange exchange = mockExchange();
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        elementProps.put(CamelConstants.ChainProperties.PARENT_ELEMENT_ID, "parentId");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

        assertEquals("parentId", ref.get().parentElementId());
    }

    @Test
    void recordStepAfterResolvesParentFromSteps() {
        Exchange exchange = mockExchange();
        Deque<String> steps = new ArrayDeque<>();
        steps.add("stepParent");
        steps.add("stepChild");
        exchange.setProperty(CamelConstants.Properties.STEPS, steps);
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

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
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfter(context(exchange, dbg, "sessElem", "node1", "dom"));

        assertEquals("execParent", ref.get().parentElementId());
    }

    @Test
    void recordStepAfterForStepBuildsRecordAndLogs() {
        Exchange exchange = mockExchange();
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), dbg, "dom", null));

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
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                null, "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), dbg, "dom", null));

        assertNull(ref.get());
        verify(spy, never()).logAfter(any(), any());
    }

    @Test
    void recordStepAfterForStepDoesNotLogWhenParentPropertiesMissing() {
        Exchange exchange = mockExchange();
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, null, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), dbg, "dom", null));

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
        Map<String, String> elementProps = elementProperties("chainElem", "http-sender", "elemName");
        CamelDebuggerProperties dbg = dbgProperties(SessionLogDetails.FULL, elementProps, "snap");
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        AtomicReference<SessionStepLogRecord> ref = new AtomicReference<>();
        JsonSessionStepLogger spy = spyCapturing(logger, ref);

        spy.recordStepAfterForStep(new SessionStepLogContext(exchange, "sid", "sessElem", "node1",
                "stepName", "stepChainElementId", "body", Map.of(), Map.of(), Map.of(), dbg, "dom", null));

        assertEquals("b", ref.get().parentElementId());
    }

    @Test
    void logAfterDoesNotLogWhenDetailsNull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("http-sender"), null);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logAfterDoesNotLogWhenDetailsOff() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.OFF);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logAfterDoesNotLogForSendersWhenElementNotInfoLevel() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("try"), SessionLogDetails.SENDERS);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logAfterDoesNotLogForSendersWhenElementTypeUnknown() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord(null), SessionLogDetails.SENDERS);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logAfterLogsForSendersWhenInfoLevelElement() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.SENDERS);
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("Session step finished with status"));
    }

    @Test
    void logAfterLogsForFullDetails() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("try"), SessionLogDetails.FULL);
        assertEquals(1, appender.list.size());
    }

    @Test
    void logAfterDoesNotLogWhenInfoLevelDisabled() {
        logbackLogger.setLevel(Level.WARN);
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        logger.logAfter(createRecord("http-sender"), SessionLogDetails.FULL);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logSessionStartDoesNotLogWhenDetailsOff() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        logger.logSessionStart(session, SessionLogDetails.OFF);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logSessionStartDoesNotLogWhenDetailsNull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        logger.logSessionStart(session, null);
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void logSessionStartLogsWhenDetailsFull() {
        JsonSessionStepLogger logger = new JsonSessionStepLogger(markers, extractor);
        Session session = mock(Session.class);
        when(session.getDomain()).thenReturn("dom");
        when(session.getSnapshotName()).thenReturn("snap");
        logger.logSessionStart(session, SessionLogDetails.FULL);
        assertEquals(1, appender.list.size());
        assertEquals("Session started", appender.list.get(0).getFormattedMessage());
    }

    private JsonSessionStepLogger spyCapturing(JsonSessionStepLogger logger, AtomicReference<SessionStepLogRecord> ref) {
        JsonSessionStepLogger spy = spy(logger);
        doAnswer(invocation -> {
            ref.set(invocation.getArgument(0));
            return null;
        }).when(spy).logAfter(any(), any());
        return spy;
    }

    private SessionStepLogContext context(Exchange exchange, CamelDebuggerProperties dbg,
            String sessionElementId, String nodeId, String domain) {
        return new SessionStepLogContext(exchange, "sessionId", sessionElementId, nodeId,
                null, null, "body", Map.of(), Map.of(), Map.of(), dbg, domain, null);
    }

    private Map<String, String> elementProperties(String elementId, String elementType, String elementName) {
        Map<String, String> props = new HashMap<>();
        props.put(CamelConstants.ChainProperties.ELEMENT_ID, elementId);
        props.put(CamelConstants.ChainProperties.ELEMENT_TYPE, elementType);
        props.put(CamelConstants.ChainProperties.ELEMENT_NAME, elementName);
        return props;
    }

    private CamelDebuggerProperties dbgProperties(SessionLogDetails details,
            Map<String, String> elementProps, String snapshotName) {
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties drp = mock(DeploymentRuntimeProperties.class);
        when(drp.getSessionLogDetails()).thenReturn(details);
        when(dbg.getRuntimeProperties(any())).thenReturn(drp);
        when(dbg.getElementProperty(anyString())).thenReturn(elementProps);
        DeploymentInfo deploymentInfo = mock(DeploymentInfo.class);
        when(deploymentInfo.getSnapshotName()).thenReturn(snapshotName);
        when(dbg.getDeploymentInfo()).thenReturn(deploymentInfo);
        return dbg;
    }

    private SessionStepLogRecord createRecord(String camelElementName) {
        return new SessionStepLogRecord("dom", null, "snap", null, camelElementName,
                ExecutionStatus.COMPLETED_NORMALLY, "body", "{}", "{}", "elemName", "elemId", "chainElem");
    }

    private Exchange mockExchange() {
        Exchange exchange = mock(Exchange.class);
        Map<String, Object> props = new HashMap<>();
        when(exchange.getProperty(anyString())).thenAnswer(a -> props.get(a.getArgument(0)));
        when(exchange.getProperty(anyString(), any(Class.class))).thenAnswer(a -> props.get(a.getArgument(0)));
        when(exchange.getProperty(anyString(), any())).thenAnswer(a -> props.get(a.getArgument(0)));
        when(exchange.getProperty(anyString(), any(), any(Class.class)))
                .thenAnswer(a -> props.get(a.getArgument(0)));
        doAnswer(a -> {
            props.put(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(exchange).setProperty(anyString(), any());
        return exchange;
    }
}
