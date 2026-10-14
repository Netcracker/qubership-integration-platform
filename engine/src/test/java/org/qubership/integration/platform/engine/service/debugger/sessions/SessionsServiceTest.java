package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.DomainType;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class SessionsServiceTest {

    private static final String CHAIN_ID = "623c08d9-1c63-469e-bfdc-5982eec84055";
    private static final String CHAIN_NAME = "Test Chain";
    private static final String SNAPSHOT_NAME = "V1";
    private static final String SESSION_ID = "a85784d4-e79f-486a-aff9-1df25fa6ae7b";
    private static final String EXTERNAL_SESSION_ID = "e820c00c-f0c2-4f66-be0a-11297436e603";
    private static final String ELEMENT_ID = "33b5814a-b345-437e-9242-d578072cf0a1";
    private static final String SESSION_ELEMENT_ID = "session-element-1";
    private static final String NODE_ID = "450e8d96-c2f1-48f5-854d-7540122d5d51";
    private static final String PARENT_ELEMENT_ID = "4fa02c29-ae41-4551-93b4-a0b1a7369074";
    private static final String PARENT_SESSION_ELEMENT_ID = "parent-session-element-1";
    private static final String INTERMEDIATE_SESSION_ELEMENT_ID = "intermediate-session-element-1";
    private static final String DOMAIN = "domain-a";
    private static final String ENGINE_ADDRESS = "engine-a";
    private static final String CORRELATION_ID_VALUE = "c45e96cc-7f6f-4218-96ae-7702dfd17e96";

    @Mock
    private PayloadExtractor extractor;

    private TestOpenSearchWriter writer;
    private SessionsService sessionsService;

    @BeforeEach
    void setUp() {
        writer = new TestOpenSearchWriter();
        sessionsService = new SessionsService(extractor, writer, Optional.empty());

        lenient().when(extractor.extractBodyForLogging(any(), any(), anyBoolean())).thenReturn("extracted-body");
        lenient().when(extractor.extractHeadersForLogging(any(), any(), anyBoolean())).thenReturn(Map.of("h", "v"));
        lenient().when(extractor.extractContextForLogging(any(), anyBoolean())).thenReturn(Map.of("c", "v"));
        lenient().when(extractor.extractExchangePropertiesForLogging(any(), any(), anyBoolean())).thenReturn(Map.of());
        lenient().when(extractor.convertToJson(any())).thenReturn("converted-json");
    }

    @Test
    void shouldStartSessionAndCacheItWhenLoggingLevelIsNotOff() {
        Exchange exchange = createExchange();
        exchange.getMessage().setHeader(Headers.EXTERNAL_SESSION_CIP_ID, EXTERNAL_SESSION_ID);
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);

        Session session = sessionsService.startSession(
                exchange, dbg, SESSION_ID, "parent-session", "2026-01-01T00:00:00", DOMAIN, ENGINE_ADDRESS);

        assertNotNull(session);
        assertEquals(EXTERNAL_SESSION_ID, session.getExternalId());
        assertEquals(DOMAIN, session.getDomain());
        assertEquals(DomainType.CLASSIC, session.getDomainType());
        assertEquals(ENGINE_ADDRESS, session.getEngineAddress());
        assertEquals(CHAIN_ID, session.getChainId());
        assertEquals(CHAIN_NAME, session.getChainName());
        assertEquals(SNAPSHOT_NAME, session.getSnapshotName());
        assertEquals(ExecutionStatus.IN_PROGRESS, session.getExecutionStatus());
        assertEquals(SessionsLoggingLevel.DEBUG.toString(), session.getLoggingLevel());
        assertEquals("parent-session", session.getParentSessionId());
        assertSame(session, writer.getSessionFromCache(SESSION_ID).getRight());
    }

    @Test
    void shouldStartSessionWithoutCachingWhenLoggingLevelIsOff() {
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.OFF);

        Session session = sessionsService.startSession(
                exchange, dbg, SESSION_ID, null, "2026-01-01T00:00:00", DOMAIN, ENGINE_ADDRESS);

        assertEquals(SessionsLoggingLevel.OFF.toString(), session.getLoggingLevel());
        assertNull(writer.getSessionFromCache(SESSION_ID));
    }

    @Test
    void shouldFinishSessionAndScheduleCachedElementsWhenLoggingEnabled() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Session session = session();
        SessionElementElastic running = cachedElement("el-running", ExecutionStatus.IN_PROGRESS);
        SessionElementElastic completed = cachedElement("el-completed", ExecutionStatus.COMPLETED_NORMALLY);
        writer.putSessionToCache(session);
        writer.putSessionElementToCacheForTest(running);
        writer.putSessionElementToCacheForTest(completed);
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);

        sessionsService.finishSession(
                exchange, dbg, ExecutionStatus.COMPLETED_NORMALLY, "2026-01-01T00:01:00", 1000L, 200L);

        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, session.getExecutionStatus());
        assertEquals("2026-01-01T00:01:00", session.getFinished());
        assertEquals(1000L, session.getDuration());
        assertEquals(200L, session.getSyncDuration());
        assertEquals(2, writer.scheduledElements().size());
        assertEquals(ExecutionStatus.CANCELLED_OR_UNKNOWN, running.getExecutionStatus());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, completed.getExecutionStatus());
        for (SessionElementElastic element : writer.scheduledElements()) {
            assertEquals(ExecutionStatus.COMPLETED_NORMALLY, element.getSessionExecutionStatus());
            assertEquals(CHAIN_ID, element.getChainId());
            assertEquals(DOMAIN, element.getDomain());
            assertEquals(ENGINE_ADDRESS, element.getEngineAddress());
        }
        assertNull(writer.getSessionFromCache(SESSION_ID));
        assertTrue(writer.getSessionElementsFromCache(SESSION_ID).isEmpty());
    }

    @Test
    void shouldFinishSessionWithElementExecutionStatusOverride() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Session session = session();
        writer.putSessionToCache(session);
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.containsElementProperty(ChainProperties.EXECUTION_STATUS)).thenReturn(true);
        when(dbg.getElementProperty(ChainProperties.EXECUTION_STATUS))
                .thenReturn(Map.of(ChainProperties.EXECUTION_STATUS, ExecutionStatus.COMPLETED_WITH_ERRORS.name()));

        sessionsService.finishSession(
                exchange, dbg, ExecutionStatus.COMPLETED_NORMALLY, "2026-01-01T00:01:00", 1000L, 200L);

        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, session.getExecutionStatus());
    }

    @Test
    void shouldClearSessionCacheWithoutSchedulingWhenLoggingIsOff() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        writer.putSessionElementToCacheForTest(cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS));
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.OFF);

        sessionsService.finishSession(
                exchange, dbg, ExecutionStatus.COMPLETED_NORMALLY, "2026-01-01T00:01:00", 1L, 1L);

        assertTrue(writer.scheduledElements().isEmpty());
        assertNull(writer.getSessionFromCache(SESSION_ID));
        assertTrue(writer.getSessionElementsFromCache(SESSION_ID).isEmpty());
    }

    @Test
    void shouldLogSessionElementBeforeWithProvidedPayload() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Service Call",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText()));
        Map<String, String> headers = Map.of("header-a", "value-a");
        Map<String, String> context = Map.of("context-a", "value-a");
        Map<String, SessionElementProperty> properties = Map.of(
                "property-a", SessionElementProperty.builder().type("string").value("value-a").build());
        when(extractor.convertToJson(headers)).thenReturn("headers-json");
        when(extractor.convertToJson(context)).thenReturn("context-json");
        when(extractor.convertToJson(properties)).thenReturn("properties-json");

        sessionsService.logSessionElementBefore(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID, "body-before", headers, context, properties);

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertNotNull(element);
        assertEquals(SESSION_ID, element.getSessionId());
        assertEquals(NODE_ID, element.getChainElementId());
        assertEquals("Service Call", element.getElementName());
        assertEquals(ChainElementType.SERVICE_CALL.getText(), element.getCamelElementName());
        assertEquals("body-before", element.getBodyBefore());
        assertEquals("headers-json", element.getHeadersBefore());
        assertEquals("properties-json", element.getPropertiesBefore());
        assertEquals("context-json", element.getContextBefore());
        assertEquals(ExecutionStatus.IN_PROGRESS, element.getExecutionStatus());
        assertEquals(CHAIN_ID, element.getChainId());
        assertEquals(DOMAIN, element.getDomain());
        assertEquals(ENGINE_ADDRESS, element.getEngineAddress());
        assertEquals(SNAPSHOT_NAME, element.getSnapshotName());
    }

    @Test
    void shouldSetParentElementIdFromChainPropertiesWhenLoggingElementBefore() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        Map<String, String> executionMap = exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP, Map.class);
        executionMap.put(PARENT_ELEMENT_ID, PARENT_SESSION_ELEMENT_ID);
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Service Call",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.PARENT_ELEMENT_ID, PARENT_ELEMENT_ID));

        sessionsService.logSessionElementBefore(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID, "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(PARENT_SESSION_ELEMENT_ID, element.getParentElementId());
    }

    @Test
    void shouldNotSetParentElementIdOnErrorLevelWhenLoggingElementBefore() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.ERROR);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Service Call",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.PARENT_ELEMENT_ID, PARENT_ELEMENT_ID));

        sessionsService.logSessionElementBefore(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID, "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertNull(element.getParentElementId());
    }

    @Test
    void shouldResolveIntermediateParentWhenElementHasIntermediateParents() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Map<String, String> executionMap = exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP, Map.class);
        executionMap.put(PARENT_ELEMENT_ID, PARENT_SESSION_ELEMENT_ID);
        writer.putSessionToCache(session());
        SessionElementElastic parentElement = SessionElementElastic.builder()
                .id(PARENT_SESSION_ELEMENT_ID)
                .sessionId(SESSION_ID)
                .chainElementId(PARENT_ELEMENT_ID)
                .executionStatus(ExecutionStatus.IN_PROGRESS)
                .build();
        SessionElementElastic intermediateElement = SessionElementElastic.builder()
                .id(INTERMEDIATE_SESSION_ELEMENT_ID)
                .sessionId(SESSION_ID)
                .chainElementId(PARENT_ELEMENT_ID)
                .parentElementId(PARENT_SESSION_ELEMENT_ID)
                .executionStatus(ExecutionStatus.IN_PROGRESS)
                .build();
        writer.putSessionElementToCacheForTest(parentElement);
        writer.putSessionElementToCacheForTest(intermediateElement);
        executionMap.put("intermediate-node", INTERMEDIATE_SESSION_ELEMENT_ID);
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Service Call",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.PARENT_ELEMENT_ID, PARENT_ELEMENT_ID,
                ChainProperties.HAS_INTERMEDIATE_PARENTS, "true"));

        sessionsService.logSessionElementBefore(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID, "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(INTERMEDIATE_SESSION_ELEMENT_ID, element.getParentElementId());
    }

    @Test
    void shouldLogStepElementBeforeWithUuidStepId() {
        String stepId = "11111111-1111-1111-1111-111111111111";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(stepId)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Step Name",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText()));

        sessionsService.logSessionStepElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, "");

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(stepId, element.getChainElementId());
        assertEquals("Step Name", element.getElementName());
        assertEquals(ChainElementType.SERVICE_CALL.getText(), element.getCamelElementName());
        assertEquals("extracted-body", element.getBodyBefore());
        assertEquals("converted-json", element.getHeadersBefore());
        assertEquals(ExecutionStatus.IN_PROGRESS, element.getExecutionStatus());
    }

    @Test
    void shouldLogStepElementBeforeWithNonUuidStepIdAndChainElement() {
        String stepId = "custom-step";
        String stepChainElementId = "22222222-2222-2222-2222-222222222222";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Deque<String> steps = exchange.getProperty(Properties.STEPS, Deque.class);
        steps.push("parent-step-id");
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(stepId)).thenReturn(null);
        when(dbg.getElementProperty(stepChainElementId)).thenReturn(Map.of(
                ChainProperties.ELEMENT_TYPE, ChainElementType.HTTP_SENDER.getText()));

        sessionsService.logSessionStepElementBefore(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, stepChainElementId);

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(stepId, element.getElementName());
        assertEquals("parent-step-id", element.getParentElementId());
        assertEquals(stepChainElementId, element.getChainElementId());
        assertEquals(ChainElementType.HTTP_SENDER.getText(), element.getCamelElementName());
    }

    @Test
    void shouldLogStepElementBeforeWithWireTapParent() {
        String stepId = "11111111-1111-1111-1111-111111111111";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Map<String, String> executionMap = exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP, Map.class);
        executionMap.put("wire-parent", "wire-parent-element");
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(stepId)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Step Name",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.WIRE_TAP_ID, "wire-parent"));

        sessionsService.logSessionStepElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, "");

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals("wire-parent-element", element.getParentElementId());
    }

    @Test
    void shouldLogSessionElementAfterWithProvidedPayloadAndCorrelation() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(CorrelationIdSetter.CORRELATION_ID, CORRELATION_ID_VALUE);
        Session session = session();
        writer.putSessionToCache(session);
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);
        Map<String, String> headers = Map.of("header-a", "value-a");
        Map<String, String> context = Map.of("context-a", "value-a");
        Map<String, SessionElementProperty> properties = Map.of(
                "property-a", SessionElementProperty.builder().type("string").value("value-a").build());
        when(extractor.convertToJson(headers)).thenReturn("headers-after-json");
        when(extractor.convertToJson(context)).thenReturn("context-after-json");
        when(extractor.convertToJson(properties)).thenReturn("properties-after-json");

        sessionsService.logSessionElementAfter(
                exchange, null, SESSION_ID, SESSION_ELEMENT_ID, "body-after", headers, context, properties);

        assertEquals("body-after", element.getBodyAfter());
        assertEquals("headers-after-json", element.getHeadersAfter());
        assertEquals("properties-after-json", element.getPropertiesAfter());
        assertEquals("context-after-json", element.getContextAfter());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, element.getExecutionStatus());
        assertNotNull(element.getFinished());
        assertTrue(element.getDuration() >= 0L);
        assertEquals(CORRELATION_ID_VALUE, session.getCorrelationId());
    }

    @Test
    void shouldLogSessionElementAfterWithException() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);

        sessionsService.logSessionElementAfter(
                exchange, new RuntimeException("boom"), SESSION_ID, SESSION_ELEMENT_ID,
                "body-after", Map.of(), Map.of(), Map.of());

        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, element.getExecutionStatus());
        assertNotNull(element.getExceptionInfo());
        assertEquals("boom", element.getExceptionInfo().getMessage());
    }

    @Test
    void shouldMarkExceptionHandleElementAsWarningWhenElementWarningIsSet() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.ELEMENT_WARNING, true);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        element.setCamelElementName(ChainElementType.TRY.getText());
        writer.putSessionElementToCacheForTest(element);

        sessionsService.logSessionElementAfter(
                exchange, null, SESSION_ID, SESSION_ELEMENT_ID, "body-after", Map.of(), Map.of(), Map.of());

        assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, element.getExecutionStatus());
        assertNull(element.getExceptionInfo());
    }

    @Test
    void shouldUseCaughtExceptionWhenElementFailed() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.ELEMENT_FAILED, true);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("caught failure"));
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);

        sessionsService.logSessionElementAfter(
                exchange, null, SESSION_ID, SESSION_ELEMENT_ID, "body-after", Map.of(), Map.of(), Map.of());

        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, element.getExecutionStatus());
        assertNotNull(element.getExceptionInfo());
        assertEquals("caught failure", element.getExceptionInfo().getMessage());
    }

    @Test
    void shouldDoNothingWhenLoggingElementAfterForMissingElement() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);

        sessionsService.logSessionElementAfter(
                exchange, null, SESSION_ID, SESSION_ELEMENT_ID, "body-after", Map.of(), Map.of(), Map.of());

        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldLogSessionElementAfterExtractingPayloadFromExchange() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);
        when(extractor.extractBodyForLogging(any(), any(), anyBoolean())).thenReturn("extracted-body-after");
        when(extractor.extractHeadersForLogging(any(), any(), anyBoolean())).thenReturn(Map.of("h", "v"));
        when(extractor.extractContextForLogging(any(), anyBoolean())).thenReturn(Map.of("c", "v"));
        when(extractor.extractExchangePropertiesForLogging(any(), any(), anyBoolean())).thenReturn(Map.of());
        when(extractor.convertToJson(any())).thenReturn("converted-after");

        sessionsService.logSessionElementAfter(
                exchange, null, SESSION_ID, SESSION_ELEMENT_ID, java.util.Set.of(), false);

        assertEquals("extracted-body-after", element.getBodyAfter());
        assertEquals("converted-after", element.getHeadersAfter());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, element.getExecutionStatus());
    }

    @Test
    void shouldPutElementToSingleCacheAndMoveToCommonCache() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.ERROR);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Service Call",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText()));

        sessionsService.putElementToSingleElCache(
                exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID, "body", Map.of(), Map.of(), Map.of());

        String result = sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID);
        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(SESSION_ELEMENT_ID, result);
        assertNotNull(element);
        assertNull(element.getParentElementId());
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldPutStepElementToSingleCacheAndMoveToCommonCache() {
        String stepId = "11111111-1111-1111-1111-111111111111";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.ERROR);
        when(dbg.getElementProperty(stepId)).thenReturn(Map.of(
                ChainProperties.ELEMENT_NAME, "Step Name",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText()));

        sessionsService.putStepElementToSingleElCache(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, "");

        String result = sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID);
        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(SESSION_ELEMENT_ID, result);
        assertNotNull(element);
        assertEquals("Step Name", element.getElementName());
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldReturnNullWhenMovingMissingSingleElementCache() {
        assertNull(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID));
    }

    @Test
    void shouldDelegateSessionStartToLoggerWhenPresent() {
        SessionStepLogger logger = mock(SessionStepLogger.class);
        SessionsService serviceWithLogger = new SessionsService(extractor, writer, Optional.of(logger));
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        DeploymentRuntimeProperties runtime = dbg.getRuntimeProperties(exchange);
        when(runtime.getSessionLogDetails()).thenReturn(SessionLogDetails.FULL);

        Session session = serviceWithLogger.startSession(exchange, dbg, SESSION_ID, null,
                "2026-01-01T00:00:00", DOMAIN, ENGINE_ADDRESS);

        verify(logger).logSessionStart(session, SessionLogDetails.FULL);
        assertNotNull(writer.getSessionFromCache(SESSION_ID));
    }

    @Test
    void shouldStillDelegateSessionStartToLoggerWhenLevelIsOff() {
        SessionStepLogger logger = mock(SessionStepLogger.class);
        SessionsService serviceWithLogger = new SessionsService(extractor, writer, Optional.of(logger));
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.OFF);
        DeploymentRuntimeProperties runtime = dbg.getRuntimeProperties(exchange);
        when(runtime.getSessionLogDetails()).thenReturn(SessionLogDetails.OFF);

        Session session = serviceWithLogger.startSession(exchange, dbg, SESSION_ID, null,
                "2026-01-01T00:00:00", DOMAIN, ENGINE_ADDRESS);

        verify(logger).logSessionStart(session, SessionLogDetails.OFF);
        assertNull(writer.getSessionFromCache(SESSION_ID));
    }

    @Test
    void shouldResolveActualElementChainIdWhenOverrideIsPresentWithoutStepName() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = new HashMap<>();
        props.put(ChainProperties.ELEMENT_NAME, "My Element");
        props.put(ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        props.put(ChainProperties.ACTUAL_ELEMENT_CHAIN_ID, "stored-chain-id");
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals("stored-chain-id", element.getActualElementChainId());
    }

    @Test
    void shouldResolveActualElementChainIdWhenOverrideMatchesElementId() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = new HashMap<>();
        props.put(ChainProperties.ELEMENT_NAME, "My Element");
        props.put(ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        props.put(ChainProperties.ACTUAL_ELEMENT_CHAIN_ID, "stored-chain-id");
        props.put(ChainProperties.ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD, NODE_ID);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals("stored-chain-id", element.getActualElementChainId());
    }

    @Test
    void shouldNotResolveActualElementChainIdWhenOverrideMismatches() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = new HashMap<>();
        props.put(ChainProperties.ELEMENT_NAME, "My Element");
        props.put(ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        props.put(ChainProperties.ACTUAL_ELEMENT_CHAIN_ID, "stored-chain-id");
        props.put(ChainProperties.ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD, "other-step");
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertNull(element.getActualElementChainId());
    }

    @Test
    void shouldResolveActualElementChainIdWhenChainCallTriggered() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, true);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "My Element",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(CHAIN_ID, element.getActualElementChainId());
    }

    @Test
    void shouldNotResolveActualElementChainIdWhenNoOverrideAndNotChainCall() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "My Element",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertNull(element.getActualElementChainId());
    }

    @Test
    void shouldUseElementIdOverrideForRegularElement() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        String overriddenId = "overridden-element-id";
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "My Element",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.ELEMENT_ID, overriddenId);
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(overriddenId, element.getChainElementId());
    }

    @Test
    void shouldFallbackToNodeIdWhenElementIdOverrideMissing() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "My Element",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        when(dbg.getElementProperty(NODE_ID)).thenReturn(props);

        sessionsService.logSessionElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, NODE_ID,
                "body", Map.of(), Map.of(), Map.of());

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(NODE_ID, element.getChainElementId());
    }

    @Test
    void shouldUseElementIdOverrideForUuidStep() {
        String stepId = "11111111-1111-1111-1111-111111111111";
        String overriddenId = "overridden-step-id";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "Step Name",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText(),
                ChainProperties.ELEMENT_ID, overriddenId);
        when(dbg.getElementProperty(stepId)).thenReturn(props);

        sessionsService.logSessionStepElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, "");

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(overriddenId, element.getChainElementId());
        assertEquals("Step Name", element.getElementName());
    }

    @Test
    void shouldUseElementIdOverrideForStepChainElementIdWhenNonUuidStep() {
        String stepId = "custom-step";
        String stepChainElementId = "22222222-2222-2222-2222-222222222222";
        String overriddenId = "overridden-chain-element-id";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        Deque<String> steps = exchange.getProperty(Properties.STEPS, Deque.class);
        steps.push("parent-step-id");
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        when(dbg.getElementProperty(stepId)).thenReturn(null);
        Map<String, String> chainProps = Map.of(
                ChainProperties.ELEMENT_TYPE, ChainElementType.HTTP_SENDER.getText(),
                ChainProperties.ELEMENT_ID, overriddenId);
        when(dbg.getElementProperty(stepChainElementId)).thenReturn(chainProps);

        sessionsService.logSessionStepElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, stepChainElementId);

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(overriddenId, element.getChainElementId());
        assertEquals(ChainElementType.HTTP_SENDER.getText(), element.getCamelElementName());
    }

    @Test
    void shouldReturnEarlyWhenSessionElementIdIsEmptyOnLogAfter() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        element.setBodyAfter(null);
        writer.putSessionElementToCacheForTest(element);

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, "", "body-after", Map.of(), Map.of(), Map.of());

        assertNull(element.getBodyAfter());
        assertTrue(writer.scheduledElements().isEmpty());
        assertNull(element.getFinished());
    }

    @Test
    void shouldReturnEarlyWhenSessionElementIdIsNullOnLogAfter() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        element.setBodyAfter(null);
        writer.putSessionElementToCacheForTest(element);

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, null, "body-after", Map.of(), Map.of(), Map.of());

        assertNull(element.getBodyAfter());
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldDelegateRecordStepAfterToLogger() {
        SessionStepLogger logger = mock(SessionStepLogger.class);
        SessionsService serviceWithLogger = new SessionsService(extractor, writer, Optional.of(logger));
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        SessionStepLogContext ctx = new SessionStepLogContext(exchange, SESSION_ID, SESSION_ELEMENT_ID,
                NODE_ID, null, null, "body", Map.of(), Map.of(), Map.of(), dbg, DOMAIN, DomainType.CLASSIC);

        serviceWithLogger.recordStepAfter(ctx);

        verify(logger).recordStepAfter(ctx);
    }

    @Test
    void shouldDelegateRecordStepAfterForStepToLogger() {
        SessionStepLogger logger = mock(SessionStepLogger.class);
        SessionsService serviceWithLogger = new SessionsService(extractor, writer, Optional.of(logger));
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        SessionStepLogContext ctx = new SessionStepLogContext(exchange, SESSION_ID, SESSION_ELEMENT_ID,
                null, "stepName", "stepChainId", "body", Map.of(), Map.of(), Map.of(), dbg, DOMAIN, DomainType.CLASSIC);

        serviceWithLogger.recordStepAfterForStep(ctx);

        verify(logger).recordStepAfterForStep(ctx);
    }

    @Test
    void shouldDoNothingForRecordMethodsWhenLoggerAbsent() {
        Exchange exchange = createExchange();
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        SessionStepLogContext ctx = new SessionStepLogContext(exchange, SESSION_ID, SESSION_ELEMENT_ID,
                NODE_ID, null, null, "body", Map.of(), Map.of(), Map.of(), dbg, DOMAIN, DomainType.CLASSIC);

        assertDoesNotThrow(() -> sessionsService.recordStepAfter(ctx));
        assertDoesNotThrow(() -> sessionsService.recordStepAfterForStep(ctx));
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldLogSessionElementAfterViaMaskedFieldsOverload() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);
        Set<String> maskedFields = Set.of("field1");
        when(extractor.extractBodyForLogging(exchange, maskedFields, false)).thenReturn("masked-body");
        when(extractor.extractHeadersForLogging(exchange, maskedFields, false)).thenReturn(Map.of("h", "v"));
        when(extractor.extractContextForLogging(maskedFields, false)).thenReturn(Map.of("c", "v"));
        when(extractor.extractExchangePropertiesForLogging(exchange, maskedFields, false)).thenReturn(Map.of());
        when(extractor.convertToJson(any())).thenReturn("converted-json");

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, SESSION_ELEMENT_ID, maskedFields, false);

        assertEquals("masked-body", element.getBodyAfter());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, element.getExecutionStatus());
        assertNotNull(element.getFinished());
        verify(extractor).extractBodyForLogging(exchange, maskedFields, false);
    }

    @Test
    void shouldResolveActualElementChainIdForStepElementWhenChainCallTriggered() {
        String stepId = "11111111-1111-1111-1111-111111111111";
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, true);
        writer.putSessionToCache(session());
        CamelDebuggerProperties dbg = debuggerProperties(SessionsLoggingLevel.DEBUG);
        Map<String, String> props = Map.of(
                ChainProperties.ELEMENT_NAME, "Step Name",
                ChainProperties.ELEMENT_TYPE, ChainElementType.SERVICE_CALL.getText());
        when(dbg.getElementProperty(stepId)).thenReturn(props);

        sessionsService.logSessionStepElementBefore(exchange, dbg, SESSION_ID, SESSION_ELEMENT_ID, stepId, "");

        SessionElementElastic element = writer.getSessionElementFromCache(SESSION_ID, SESSION_ELEMENT_ID);
        assertEquals(CHAIN_ID, element.getActualElementChainId());
    }

    @Test
    void shouldUseExchangeExceptionOverExternalExceptionWhenPopulatingAfterFields() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        element.setCamelElementName(ChainElementType.SERVICE_CALL.getText());
        writer.putSessionElementToCacheForTest(element);
        exchange.setException(new IllegalArgumentException("exchange-exception"));
        Exception external = new RuntimeException("external-exception");

        sessionsService.logSessionElementAfter(exchange, external, SESSION_ID, SESSION_ELEMENT_ID, "body-after", Map.of(), Map.of(), Map.of());

        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, element.getExecutionStatus());
        assertNotNull(element.getExceptionInfo());
        assertEquals("exchange-exception", element.getExceptionInfo().getMessage());
    }

    @Test
    void shouldNotScheduleElementWhenIdIsEmptyEvenWithMaskedFieldsOverload() {
        Exchange exchange = createExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        writer.putSessionToCache(session());
        SessionElementElastic element = cachedElement(SESSION_ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionElementToCacheForTest(element);
        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, "", Set.of(), false);

        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldUseSamplerProbabilityWhenCheckingWhetherSessionShouldBeLogged() throws Exception {
        setSamplerProbabilistic(1.0);
        assertTrue(sessionsService.sessionShouldBeLogged());

        setSamplerProbabilistic(-1.0);
        assertFalse(sessionsService.sessionShouldBeLogged());
    }

    private void setSamplerProbabilistic(double value) throws Exception {
        Field field = SessionsService.class.getDeclaredField("samplerProbabilistic");
        field.setAccessible(true);
        field.set(sessionsService, value);
    }

    private CamelDebuggerProperties debuggerProperties(SessionsLoggingLevel level) {
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class);
        DeploymentRuntimeProperties runtimeProperties = mock(DeploymentRuntimeProperties.class);
        lenient().when(dbg.getRuntimeProperties(any())).thenReturn(runtimeProperties);
        lenient().when(runtimeProperties.calculateSessionLevel(any())).thenReturn(level);
        lenient().when(runtimeProperties.isMaskingEnabled()).thenReturn(false);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder()
                .chainId(CHAIN_ID)
                .chainName(CHAIN_NAME)
                .snapshotName(SNAPSHOT_NAME)
                .build();
        lenient().when(dbg.getDeploymentInfo()).thenReturn(deploymentInfo);
        return dbg;
    }

    private Session session() {
        return Session.builder()
                .id(SESSION_ID)
                .externalId(EXTERNAL_SESSION_ID)
                .started("2026-01-01T00:00:00")
                .executionStatus(ExecutionStatus.IN_PROGRESS)
                .chainId(CHAIN_ID)
                .chainName(CHAIN_NAME)
                .domain(DOMAIN)
                .engineAddress(ENGINE_ADDRESS)
                .loggingLevel(SessionsLoggingLevel.DEBUG.toString())
                .snapshotName(SNAPSHOT_NAME)
                .parentSessionId("parent-session")
                .build();
    }

    private SessionElementElastic cachedElement(String elementId, ExecutionStatus executionStatus) {
        return SessionElementElastic.builder()
                .id(elementId)
                .sessionId(SESSION_ID)
                .started(LocalDateTime.now().minusSeconds(1).toString())
                .executionStatus(executionStatus)
                .chainElementId("node-1")
                .camelElementName(ChainElementType.SERVICE_CALL.getText())
                .build();
    }

    private static Exchange createExchange() {
        Exchange ex = mock(Exchange.class, withSettings().lenient());
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

        Message msg = mock(Message.class, withSettings().lenient());
        ConcurrentHashMap<String, Object> headers = new ConcurrentHashMap<>();
        lenient().when(msg.getHeaders()).thenReturn(headers);
        lenient().doAnswer(inv -> {
            headers.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(msg).setHeader(anyString(), any());
        lenient().when(msg.getHeader(anyString())).thenAnswer(inv -> headers.get(inv.getArgument(0)));
        lenient().when(msg.getHeader(anyString(), any(Class.class))).thenAnswer(inv -> {
            Object value = headers.get(inv.getArgument(0));
            return value == null ? null : inv.<Class<?>>getArgument(1).cast(value);
        });
        lenient().when(ex.getMessage()).thenReturn(msg);
        lenient().when(ex.getIn()).thenReturn(msg);
        lenient().when(msg.getExchange()).thenReturn(ex);

        props.put(Properties.ELEMENT_EXECUTION_MAP, new ConcurrentHashMap<String, String>());
        props.put(Properties.STEPS, new ConcurrentLinkedDeque<String>());
        return ex;
    }

    static class TestOpenSearchWriter extends OpenSearchWriter {

        private final List<SessionElementElastic> scheduledElements = new ArrayList<>();

        @Override
        public void scheduleElementToLog(SessionElementElastic element) {
            scheduleElementToLog(element, false);
        }

        @Override
        protected void scheduleElementToLog(SessionElementElastic element, boolean addToCache) {
            scheduledElements.add(element);
            if (addToCache) {
                putSessionElementToCache(element);
            }
        }

        void putSessionElementToCacheForTest(SessionElementElastic element) {
            putSessionElementToCache(element);
        }

        List<SessionElementElastic> scheduledElements() {
            return scheduledElements;
        }
    }
}
