package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.metadata.WireTapInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.logging.Payload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SessionsServiceTest {

    private static final String CHAIN_ID = "623c08d9-1c63-469e-bfdc-5982eec84055";
    private static final String CHAIN_NAME = "Test Chain";
    private static final String SNAPSHOT_NAME = "V8";
    private static final String PARENT_ELEMENT_ID = "4fa02c29-ae41-4551-93b4-a0b1a7369074";
    private static final String ELEMENT_ID = "33b5814a-b345-437e-9242-d578072cf0a1";
    private static final String ELEMENT_NAME = "Service Call";
    private static final String NODE_ID = "450e8d96-c2f1-48f5-854d-7540122d5d51";
    private static final String PARENT_SESSION_ID = "af098b72-61b8-4b5e-b49b-56333decc6ff";
    private static final String SESSION_ID = "a85784d4-e79f-486a-aff9-1df25fa6ae7b";
    private static final String EXTERNAL_SESSION_ID = "e820c00c-f0c2-4f66-be0a-11297436e603";
    private static final String DOMAIN = "domain-a";
    private static final String ENGINE = "engine-a";
    private static final String CORRELATION_ID_VALUE = "c45e96cc-7f6f-4218-96ae-7702dfd17e96";

    private SessionsService sessionsService;

    @Mock
    private PayloadExtractor extractor;
    @Mock
    private ChainRuntimePropertiesService chainRuntimePropertiesService;

    private TestOpenSearchWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TestOpenSearchWriter();
        EngineInfo engineInfo = EngineInfo.builder()
                .domain(DOMAIN)
                .host(ENGINE)
                .build();
        sessionsService = new SessionsService(extractor, writer, engineInfo, chainRuntimePropertiesService);
    }

    @Test
    void shouldStartSessionAndPutItToCacheWhenSessionLoggingIsEnabled() {
        Exchange exchange = exchange();
        exchange.getMessage().setHeader(Headers.EXTERNAL_SESSION_CIP_ID, EXTERNAL_SESSION_ID);
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.DEBUG));

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(deploymentInfo());

            Session session = sessionsService.startSession(exchange, PARENT_SESSION_ID);

            assertNotNull(session.getId());
            assertEquals(EXTERNAL_SESSION_ID, session.getExternalId());
            assertEquals(DOMAIN, session.getDomain());
            assertEquals(ENGINE, session.getEngineAddress());
            assertEquals(CHAIN_ID, session.getChainId());
            assertEquals(CHAIN_NAME, session.getChainName());
            assertEquals(ExecutionStatus.IN_PROGRESS, session.getExecutionStatus());
            assertEquals(SessionsLoggingLevel.DEBUG.toString(), session.getLoggingLevel());
            assertEquals(SNAPSHOT_NAME, session.getSnapshotName());
            assertEquals(PARENT_SESSION_ID, session.getParentSessionId());
            assertSame(session, writer.getSessionFromCache(session.getId()).getRight());
        }
    }

    @Test
    void shouldStartSessionWithoutCachingWhenSessionLoggingIsOff() {
        Exchange exchange = exchange();
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.OFF));

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(deploymentInfo());

            Session session = sessionsService.startSession(exchange, null);

            assertEquals(SessionsLoggingLevel.OFF.toString(), session.getLoggingLevel());
            assertNull(writer.getSessionFromCache(session.getId()));
        }
    }

    @Test
    void shouldFinishSessionAndScheduleCachedElementsWhenSessionLoggingIsEnabled() {
        Exchange exchange = exchange();
        String finishTime = "2026-06-22T12:15:30";
        Session session = session();
        SessionElementElastic runningElement = cachedElement("running-element", ExecutionStatus.IN_PROGRESS);
        SessionElementElastic completedElement = cachedElement("completed-element", ExecutionStatus.COMPLETED_NORMALLY);
        writer.putSessionToCache(session);
        writer.putSessionElementToCacheForTest(runningElement);
        writer.putSessionElementToCacheForTest(completedElement);
        exchange.setProperty(
                CamelConstants.SYSTEM_PROPERTY_PREFIX + "executionStatus",
                ExecutionStatus.COMPLETED_WITH_WARNINGS
        );
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.DEBUG));

        sessionsService.finishSession(exchange, ExecutionStatus.COMPLETED_NORMALLY, finishTime, 123L, 45L);

        assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, session.getExecutionStatus());
        assertEquals(finishTime, session.getFinished());
        assertEquals(123L, session.getDuration());
        assertEquals(45L, session.getSyncDuration());
        assertEquals(2, writer.scheduledElements().size());
        assertTrue(writer.scheduledElements().contains(runningElement));
        assertTrue(writer.scheduledElements().contains(completedElement));
        assertEquals(ExecutionStatus.CANCELLED_OR_UNKNOWN, runningElement.getExecutionStatus());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, completedElement.getExecutionStatus());
        for (SessionElementElastic scheduledElement : writer.scheduledElements()) {
            assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, scheduledElement.getSessionExecutionStatus());
            assertEquals(finishTime, scheduledElement.getSessionFinished());
            assertEquals(123L, scheduledElement.getSessionDuration());
            assertEquals(45L, scheduledElement.getSessionSyncDuration());
            assertEquals(CHAIN_ID, scheduledElement.getChainId());
            assertEquals(DOMAIN, scheduledElement.getDomain());
            assertEquals(ENGINE, scheduledElement.getEngineAddress());
            assertEquals(SessionsLoggingLevel.DEBUG.toString(), scheduledElement.getLoggingLevel());
            assertEquals(CORRELATION_ID_VALUE, scheduledElement.getCorrelationId());
        }
        assertNull(writer.getSessionFromCache(SESSION_ID));
        assertTrue(writer.getSessionElementsFromCache(SESSION_ID).isEmpty());
    }

    @Test
    void shouldClearSessionCacheWithoutSchedulingElementsWhenSessionLoggingIsOff() {
        Exchange exchange = exchange();
        writer.putSessionToCache(session());
        writer.putSessionElementToCacheForTest(cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS));
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.OFF));

        sessionsService.finishSession(exchange, ExecutionStatus.COMPLETED_NORMALLY, "2026-06-22T12:15:30", 1L, 1L);

        assertTrue(writer.scheduledElements().isEmpty());
        assertNull(writer.getSessionFromCache(SESSION_ID));
        assertTrue(writer.getSessionElementsFromCache(SESSION_ID).isEmpty());
    }

    @Test
    void shouldBuildAndScheduleElementBeforeWhenLoggingElementStarts() {
        Exchange exchange = exchange();
        Session session = session();
        writer.putSessionToCache(session);
        TestPayload payload = payload(
                "before-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "context-value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.DEBUG));
        when(extractor.convertToJson(payload.getHeaders())).thenReturn("headers-before-json");
        when(extractor.convertToJson(payload.getProperties())).thenReturn("properties-before-json");
        when(extractor.convertToJson(payload.getContext())).thenReturn("context-before-json");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, NODE_ID, ElementInfo.class))
                    .thenReturn(elementInfo());
            metadataUtil.when(() -> MetadataUtil.lookupBeanForElement(exchange, NODE_ID, WireTapInfo.class))
                    .thenReturn(Optional.empty());

            sessionsService.logSessionElementBefore(exchange, SESSION_ID, ELEMENT_ID, NODE_ID, payload);
        }

        SessionElementElastic sessionElement = writer.scheduledElements().get(0);
        assertEquals(ELEMENT_ID, sessionElement.getId());
        assertEquals(SESSION_ID, sessionElement.getSessionId());
        assertEquals(ELEMENT_ID, sessionElement.getChainElementId());
        assertEquals(ELEMENT_NAME, sessionElement.getElementName());
        assertEquals(ChainElementType.SERVICE_CALL.getText(), sessionElement.getCamelElementName());
        assertEquals(CHAIN_ID, sessionElement.getActualElementChainId());
        assertEquals(PARENT_ELEMENT_ID, sessionElement.getParentElementId());
        assertEquals("before-body", sessionElement.getBodyBefore());
        assertEquals("headers-before-json", sessionElement.getHeadersBefore());
        assertEquals("properties-before-json", sessionElement.getPropertiesBefore());
        assertEquals("context-before-json", sessionElement.getContextBefore());
        assertEquals(ExecutionStatus.IN_PROGRESS, sessionElement.getExecutionStatus());
        assertEquals(CHAIN_ID, sessionElement.getChainId());
        assertEquals(CHAIN_NAME, sessionElement.getChainName());
        assertEquals(DOMAIN, sessionElement.getDomain());
        assertEquals(ENGINE, sessionElement.getEngineAddress());
        assertEquals(SNAPSHOT_NAME, sessionElement.getSnapshotName());
        assertSame(sessionElement, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
        assertEquals(ELEMENT_ID, elementExecutionMap(exchange).get(NODE_ID));
    }

    @Test
    void shouldUseWireTapParentWhenLoggingElementBefore() {
        Exchange exchange = exchange();
        writer.putSessionToCache(session());
        elementExecutionMap(exchange).put("wire-parent", "wire-parent-element");
        TestPayload payload = payload(
                "before-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.DEBUG));
        stubPayloadJson(payload, "headers-before-json", "properties-before-json", "context-before-json");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, NODE_ID, ElementInfo.class))
                    .thenReturn(elementInfo());
            metadataUtil.when(() -> MetadataUtil.lookupBeanForElement(exchange, NODE_ID, WireTapInfo.class))
                    .thenReturn(Optional.of(WireTapInfo.builder()
                            .parentIds(List.of("wire-parent"))
                            .build()));

            sessionsService.logSessionElementBefore(exchange, SESSION_ID, ELEMENT_ID, NODE_ID, payload);
        }

        SessionElementElastic sessionElement = writer.scheduledElements().get(0);
        assertEquals("wire-parent-element", sessionElement.getParentElementId());
        assertSame(sessionElement, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldBuildAndScheduleStepElementBeforeWhenLoggingStepStarts() {
        Exchange exchange = exchange();
        writer.putSessionToCache(session());
        TestPayload payload = payload(
                "step-body",
                Map.of("step-header", "value"),
                Map.of("step-context", "value"),
                Map.of("step-property", SessionElementProperty.builder().type("string").value("value").build())
        );
        when(extractor.extractPayload(exchange)).thenReturn(payload);
        stubPayloadJson(payload, "step-headers-json", "step-properties-json", "step-context-json");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.lookupBeanForElement(exchange, NODE_ID, WireTapInfo.class))
                    .thenReturn(Optional.empty());

            sessionsService.logSessionStepElementBefore(exchange, SESSION_ID, ELEMENT_ID, ELEMENT_NAME, elementInfo());
        }

        SessionElementElastic sessionElement = writer.scheduledElements().get(0);
        assertEquals(ELEMENT_ID, sessionElement.getId());
        assertEquals(ELEMENT_NAME, sessionElement.getElementName());
        assertEquals(PARENT_ELEMENT_ID, sessionElement.getParentElementId());
        assertEquals("step-body", sessionElement.getBodyBefore());
        assertEquals("step-headers-json", sessionElement.getHeadersBefore());
        assertEquals("step-properties-json", sessionElement.getPropertiesBefore());
        assertEquals("step-context-json", sessionElement.getContextBefore());
        assertSame(sessionElement, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldPutElementToSingleElementCacheWhenSessionLoggingLevelIsError() {
        Exchange exchange = exchange();
        writer.putSessionToCache(session());
        TestPayload payload = payload(
                "before-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        when(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .thenReturn(runtimeProperties(SessionsLoggingLevel.ERROR));
        stubPayloadJson(payload, "headers-before-json", "properties-before-json", "context-before-json");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, NODE_ID, ElementInfo.class))
                    .thenReturn(elementInfo());
            metadataUtil.when(() -> MetadataUtil.lookupBeanForElement(exchange, NODE_ID, WireTapInfo.class))
                    .thenReturn(Optional.empty());

            sessionsService.putElementToSingleElCache(exchange, SESSION_ID, ELEMENT_ID, NODE_ID, payload);
        }

        String result = sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID);
        SessionElementElastic sessionElement = writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID);
        assertEquals(ELEMENT_ID, result);
        assertNotNull(sessionElement);
        assertNull(sessionElement.getParentElementId());
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldPutStepElementToSingleElementCacheWhenSessionIsAlive() {
        Exchange exchange = exchange();
        writer.putSessionToCache(session());
        TestPayload payload = payload(
                "step-body",
                Map.of("step-header", "value"),
                Map.of("step-context", "value"),
                Map.of("step-property", SessionElementProperty.builder().type("string").value("value").build())
        );
        when(extractor.extractPayload(exchange)).thenReturn(payload);
        stubPayloadJson(payload, "step-headers-json", "step-properties-json", "step-context-json");

        sessionsService.putStepElementToSingleElCache(
                exchange,
                SESSION_ID,
                ELEMENT_ID,
                "custom-step",
                elementInfo()
        );

        String result = sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID);
        SessionElementElastic sessionElement = writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID);
        assertEquals(ELEMENT_ID, result);
        assertNotNull(sessionElement);
        assertEquals("custom-step", sessionElement.getElementName());
        assertEquals(PARENT_ELEMENT_ID, sessionElement.getParentElementId());
        assertTrue(writer.scheduledElements().isEmpty());
    }

    @Test
    void shouldUpdateCachedElementAndSessionCorrelationWhenLoggingElementFinishes() {
        Exchange exchange = exchange();
        Session session = session();
        SessionElementElastic sessionElement = cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionToCache(session);
        writer.putSessionElementToCacheForTest(sessionElement);
        exchange.setProperty(CORRELATION_ID, "correlation-updated");
        RuntimeException externalException = new RuntimeException("external failure");
        TestPayload payload = payload(
                "after-body",
                Map.of("header-b", "value-b"),
                Map.of("context-b", "context-value-b"),
                Map.of("property-b", SessionElementProperty.builder().type("string").value("value-b").build())
        );
        when(extractor.convertToJson(payload.getHeaders())).thenReturn("headers-after-json");
        when(extractor.convertToJson(payload.getProperties())).thenReturn("properties-after-json");
        when(extractor.convertToJson(payload.getContext())).thenReturn("context-after-json");

        sessionsService.logSessionElementAfter(exchange, externalException, SESSION_ID, ELEMENT_ID, payload);

        assertEquals(List.of(sessionElement), writer.scheduledElements());
        assertEquals("after-body", sessionElement.getBodyAfter());
        assertEquals("headers-after-json", sessionElement.getHeadersAfter());
        assertEquals("properties-after-json", sessionElement.getPropertiesAfter());
        assertEquals("context-after-json", sessionElement.getContextAfter());
        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, sessionElement.getExecutionStatus());
        assertNotNull(sessionElement.getFinished());
        assertTrue(sessionElement.getDuration() >= 0L);
        assertNotNull(sessionElement.getExceptionInfo());
        assertTrue(sessionElement.getExceptionInfo().getStackTrace().contains(RuntimeException.class.getName()));
        assertEquals("external failure", sessionElement.getExceptionInfo().getMessage());
        assertEquals("correlation-updated", session.getCorrelationId());
    }

    @Test
    void shouldExtractPayloadWhenLoggingElementFinishesWithoutProvidedPayload() {
        Exchange exchange = exchange();
        SessionElementElastic sessionElement = cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionToCache(session());
        writer.putSessionElementToCacheForTest(sessionElement);
        TestPayload payload = payload(
                "after-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        when(extractor.extractPayload(exchange)).thenReturn(payload);
        stubPayloadJson(payload, "headers-after-json", "properties-after-json", "context-after-json");

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, ELEMENT_ID);

        assertEquals("after-body", sessionElement.getBodyAfter());
        assertEquals("headers-after-json", sessionElement.getHeadersAfter());
        assertEquals("properties-after-json", sessionElement.getPropertiesAfter());
        assertEquals("context-after-json", sessionElement.getContextAfter());
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, sessionElement.getExecutionStatus());
    }

    @Test
    void shouldMarkExceptionHandleElementAsWarningWhenElementWarningIsSet() {
        Exchange exchange = exchange();
        SessionElementElastic sessionElement = cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        sessionElement.setCamelElementName(ChainElementType.TRY.getText());
        writer.putSessionToCache(session());
        writer.putSessionElementToCacheForTest(sessionElement);
        exchange.setProperty(Properties.ELEMENT_WARNING, true);
        TestPayload payload = payload(
                "after-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        stubPayloadJson(payload, "headers-after-json", "properties-after-json", "context-after-json");

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, ELEMENT_ID, payload);

        assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, sessionElement.getExecutionStatus());
        assertNull(sessionElement.getExceptionInfo());
    }

    @Test
    void shouldUseCaughtExceptionWhenElementFailed() {
        Exchange exchange = exchange();
        SessionElementElastic sessionElement = cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionToCache(session());
        writer.putSessionElementToCacheForTest(sessionElement);
        IllegalStateException caughtException = new IllegalStateException("caught failure");
        exchange.setProperty(Properties.ELEMENT_FAILED, true);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, caughtException);
        TestPayload payload = payload(
                "after-body",
                Map.of("header-a", "value-a"),
                Map.of("context-a", "value-a"),
                Map.of("property-a", SessionElementProperty.builder().type("string").value("value-a").build())
        );
        stubPayloadJson(payload, "headers-after-json", "properties-after-json", "context-after-json");

        sessionsService.logSessionElementAfter(exchange, null, SESSION_ID, ELEMENT_ID, payload);

        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, sessionElement.getExecutionStatus());
        assertNotNull(sessionElement.getExceptionInfo());
        assertEquals("caught failure", sessionElement.getExceptionInfo().getMessage());
        assertTrue(sessionElement.getExceptionInfo().getStackTrace().contains(IllegalStateException.class.getName()));
    }

    @Test
    void shouldReturnNullWhenMovingMissingSingleElementCache() {
        assertNull(sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID));
    }

    @Test
    void shouldMoveSingleElementCacheToCommonCacheWhenSessionIsAlive() {
        SessionElementElastic sessionElement = cachedElement(ELEMENT_ID, ExecutionStatus.IN_PROGRESS);
        writer.putSessionToCache(session());
        writer.putToSingleElementCache(SESSION_ID, sessionElement);

        String result = sessionsService.moveFromSingleElCacheToCommonCache(SESSION_ID);

        assertEquals(ELEMENT_ID, result);
        assertSame(sessionElement, writer.getSessionElementFromCache(SESSION_ID, ELEMENT_ID));
    }

    @Test
    void shouldUseSamplerProbabilityWhenCheckingWhetherSessionShouldBeLogged() {
        sessionsService.samplerProbabilistic = 1.0;
        assertTrue(sessionsService.sessionShouldBeLogged());

        sessionsService.samplerProbabilistic = -1.0;
        assertFalse(sessionsService.sessionShouldBeLogged());
    }

    private Exchange exchange() {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(Properties.SESSION_ID, SESSION_ID);
        exchange.setProperty(Properties.ELEMENT_EXECUTION_MAP, new HashMap<String, String>());
        exchange.setProperty(Properties.STEPS, new ArrayDeque<>(List.of(PARENT_ELEMENT_ID)));
        return exchange;
    }

    private Map<String, String> elementExecutionMap(Exchange exchange) {
        return exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP, Map.class);
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder()
                .chain(ChainInfo.builder()
                        .id(CHAIN_ID)
                        .name(CHAIN_NAME)
                        .build())
                .snapshot(SnapshotInfo.builder()
                        .name(SNAPSHOT_NAME)
                        .build())
                .build();
    }

    private Session session() {
        return Session.builder()
                .id(SESSION_ID)
                .externalId(EXTERNAL_SESSION_ID)
                .started("2026-06-22T12:00:00")
                .executionStatus(ExecutionStatus.IN_PROGRESS)
                .chainId(CHAIN_ID)
                .chainName(CHAIN_NAME)
                .domain(DOMAIN)
                .engineAddress(ENGINE)
                .loggingLevel(SessionsLoggingLevel.DEBUG.toString())
                .snapshotName(SNAPSHOT_NAME)
                .correlationId(CORRELATION_ID_VALUE)
                .parentSessionId(PARENT_SESSION_ID)
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

    private ElementInfo elementInfo() {
        return ElementInfo.builder()
                .id(ELEMENT_ID)
                .name(ELEMENT_NAME)
                .type(ChainElementType.SERVICE_CALL.getText())
                .chainId(CHAIN_ID)
                .build();
    }

    private ChainRuntimeProperties runtimeProperties(SessionsLoggingLevel sessionsLoggingLevel) {
        return ChainRuntimeProperties.builder()
                .sessionsLoggingLevel(sessionsLoggingLevel)
                .build();
    }

    private void stubPayloadJson(
            TestPayload payload,
            String headersJson,
            String propertiesJson,
            String contextJson
    ) {
        when(extractor.convertToJson(payload.getHeaders())).thenReturn(headersJson);
        when(extractor.convertToJson(payload.getProperties())).thenReturn(propertiesJson);
        when(extractor.convertToJson(payload.getContext())).thenReturn(contextJson);
    }

    private TestPayload payload(
            String body,
            Map<String, String> headers,
            Map<String, String> context,
            Map<String, SessionElementProperty> properties
    ) {
        return new TestPayload(headers, context, properties, body);
    }

    private record TestPayload(
            Map<String, String> headers,
            Map<String, String> context,
            Map<String, SessionElementProperty> properties,
            String body
    ) implements Payload {
        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public Map<String, String> getContext() {
            return context;
        }

        @Override
        public Map<String, SessionElementProperty> getProperties() {
            return properties;
        }

        @Override
        public String getBody() {
            return body;
        }
    }

    private static class TestOpenSearchWriter extends OpenSearchWriter {
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

        private void putSessionElementToCacheForTest(SessionElementElastic element) {
            putSessionElementToCache(element);
        }

        private List<SessionElementElastic> scheduledElements() {
            return scheduledElements;
        }
    }
}
