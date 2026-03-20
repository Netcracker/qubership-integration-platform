package org.qubership.integration.platform.engine.camel.processors.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.xml.slurpersupport.GPathResult;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityNotFoundException;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextOperationsWrapper;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.Property;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.util.CheckpointUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.SESSION_ID;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextLoaderProcessorTest {

    @Test
    void shouldRestoreCheckpointAndUpdatePayloadWhenProcessCalled() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextOperationsWrapper contextOperations = mock(ContextOperationsWrapper.class);
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.of(contextOperations)
        );

        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(SESSION_ID, "6dc1fb7d-bf4b-4c8d-ae92-3d28e4f5022c");

        CheckpointUtils.CheckpointInfo checkpointInfo = mock(CheckpointUtils.CheckpointInfo.class);
        when(checkpointInfo.sessionId()).thenReturn("a7d6d278-87db-43f8-8cda-2d23f6b7f0b1");
        when(checkpointInfo.chainId()).thenReturn("f2b4f91e-4fd5-43e8-b9b9-fcb4c7b7e28a");
        when(checkpointInfo.checkpointElementId()).thenReturn("e93ad8d0-a925-4b89-b6d0-551be4ee2a77");

        Checkpoint checkpoint = mock(Checkpoint.class);
        SessionInfo parentSession = mock(SessionInfo.class);
        SessionInfo originalSession = mock(SessionInfo.class);

        when(checkpointSessionService.findCheckpoint(
                "a7d6d278-87db-43f8-8cda-2d23f6b7f0b1",
                "f2b4f91e-4fd5-43e8-b9b9-fcb4c7b7e28a",
                "e93ad8d0-a925-4b89-b6d0-551be4ee2a77"
        )).thenReturn(checkpoint);
        when(checkpoint.getContextData()).thenReturn("{\"trace\":{\"traceId\":\"123\"}}");
        when(checkpoint.getProperties()).thenReturn(List.of());
        when(checkpoint.getHeaders()).thenReturn("{\"restoredHeader\":\"restored-value\"}");
        when(checkpoint.getBody()).thenReturn("checkpoint-body".getBytes(StandardCharsets.UTF_8));
        when(checkpoint.getSession()).thenReturn(parentSession);
        when(parentSession.getId()).thenReturn("8f8d0b21-0d41-46e6-b536-c80b1fbc9cf4");
        when(checkpointSessionService.findOriginalSessionInfo("8f8d0b21-0d41-46e6-b536-c80b1fbc9cf4"))
                .thenReturn(java.util.Optional.of(originalSession));
        when(originalSession.getId()).thenReturn("d2edc4b7-307e-4c4c-8702-c8f3e7d9f6de");

        Map<String, Map<String, Object>> contextData = Map.of(
                "trace", Map.of("traceId", "123")
        );

        try (
                MockedStatic<CheckpointUtils> checkpointUtilsMock = mockStatic(CheckpointUtils.class);
                MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class)
        ) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(exchange))
                    .thenReturn(checkpointInfo);
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("""
                        {
                          "properties": {
                            "requestProperty": "request-value"
                          },
                          "headers": {
                            "requestHeader": "request-header-value"
                          },
                          "body": "request-body"
                        }
                        """);

            processor.process(exchange);

            verify(contextOperations).activateWithSerializableContextData(contextData);
            verify(checkpointSessionService).updateSessionParent(
                    "6dc1fb7d-bf4b-4c8d-ae92-3d28e4f5022c",
                    "8f8d0b21-0d41-46e6-b536-c80b1fbc9cf4"
            );
            checkpointUtilsMock.verify(() -> CheckpointUtils.setSessionProperties(
                    exchange,
                    "8f8d0b21-0d41-46e6-b536-c80b1fbc9cf4",
                    "d2edc4b7-307e-4c4c-8702-c8f3e7d9f6de"
            ));

            assertEquals("request-body", exchange.getMessage().getBody(String.class));
            assertEquals("restored-value", exchange.getMessage().getHeader("restoredHeader"));
            assertEquals("request-header-value", exchange.getMessage().getHeader("requestHeader"));
            assertEquals("request-value", exchange.getProperty("requestProperty"));
        }
    }

    @Test
    void shouldThrowCheckpointExceptionWhenCheckpointNotFound() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.empty()
        );

        Exchange exchange = MockExchanges.defaultExchange();

        CheckpointUtils.CheckpointInfo checkpointInfo = mock(CheckpointUtils.CheckpointInfo.class);
        when(checkpointInfo.sessionId()).thenReturn("4d0d2a1e-7b63-44f6-9f97-bdf83ff8d4de");
        when(checkpointInfo.chainId()).thenReturn("46a4ce5f-a4f4-4f57-8f88-3f7463220de1");
        when(checkpointInfo.checkpointElementId()).thenReturn("7f9f42e8-c089-4236-9f87-5bbd6e2e0c10");

        when(checkpointSessionService.findCheckpoint(
                "4d0d2a1e-7b63-44f6-9f97-bdf83ff8d4de",
                "46a4ce5f-a4f4-4f57-8f88-3f7463220de1",
                "7f9f42e8-c089-4236-9f87-5bbd6e2e0c10"
        )).thenReturn(null);

        try (MockedStatic<CheckpointUtils> checkpointUtilsMock = mockStatic(CheckpointUtils.class)) {
            checkpointUtilsMock.when(() -> CheckpointUtils.extractTriggeredCheckpointInfo(exchange))
                    .thenReturn(checkpointInfo);

            CheckpointException exception = assertThrows(
                    CheckpointException.class,
                    () -> processor.process(exchange)
            );

            assertEquals("Failed to load session from checkpoint", exception.getMessage());
            assertInstanceOf(EntityNotFoundException.class, exception.getCause());
        }
    }

    @Test
    void shouldDeserializeSerializablePropertyWithMetadata() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.empty()
        );

        Checkpoint checkpoint = mock(Checkpoint.class);
        ArrayList<String> value = new ArrayList<>(List.of("one", "two"));
        Property property = property("list", ArrayList.class.getName(), serialize(value), null);

        when(checkpoint.getProperties()).thenReturn(List.of(property));

        Map<String, Object> result = new HashMap<>();

        processor.deserializeProperties(checkpoint, result);

        assertEquals(value, result.get("list"));
    }

    @Test
    void shouldDeserializeXmlPropertyAsGPathResult() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.empty()
        );

        Checkpoint checkpoint = mock(Checkpoint.class);
        Property property = property(
                "xml",
                GPathResult.class.getName(),
                "<root><id>42</id></root>".getBytes(StandardCharsets.UTF_8),
                null
        );

        when(checkpoint.getProperties()).thenReturn(List.of(property));

        Map<String, Object> result = new HashMap<>();

        processor.deserializeProperties(checkpoint, result);

        Object xml = result.get("xml");
        assertInstanceOf(GPathResult.class, xml);
    }

    @Test
    void shouldDeserializePropertyWithoutTypeUsingMapperWhenClassNotFound() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.empty()
        );

        Checkpoint checkpoint = mock(Checkpoint.class);
        byte[] value = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        Property property = property("prop", "missing.Type", value, null);

        when(checkpoint.getProperties()).thenReturn(List.of(property));

        Map<String, Object> result = new HashMap<>();

        processor.deserializeProperties(checkpoint, result);

        assertEquals(Map.of("a", 1), result.get("prop"));
    }

    @Test
    void shouldFallbackToStringWhenPropertyDeserializationFails() {
        CheckpointSessionService checkpointSessionService = mock(CheckpointSessionService.class);
        ObjectMapper checkpointMapper = ObjectMappers.getCheckpointMapper();
        ContextLoaderProcessor processor = processor(
                checkpointSessionService,
                checkpointMapper,
                java.util.Optional.empty()
        );

        Checkpoint checkpoint = mock(Checkpoint.class);
        byte[] value = "plain-text".getBytes(StandardCharsets.UTF_8);
        Property property = property("prop", ArrayList.class.getName(), value, null);

        when(checkpoint.getProperties()).thenReturn(List.of(property));

        Map<String, Object> result = new HashMap<>();

        processor.deserializeProperties(checkpoint, result);

        assertEquals("plain-text", result.get("prop"));
    }

    @Test
    void shouldDeserializeWithMetadata() {
        ArrayList<String> value = new ArrayList<>(List.of("one", "two"));

        Object result = ContextLoaderProcessor.deserializeWithMetadata(serialize(value));

        assertSame(ArrayList.class, result.getClass());
        assertEquals(value, result);
    }

    private static ContextLoaderProcessor processor(
            CheckpointSessionService checkpointSessionService,
            ObjectMapper checkpointMapper,
            java.util.Optional<ContextOperationsWrapper> contextOperations
    ) {
        @SuppressWarnings("unchecked")
        Instance<ContextOperationsWrapper> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtilMock = mockStatic(InjectUtil.class)) {
            injectUtilMock.when(() -> InjectUtil.injectOptional(instance)).thenReturn(contextOperations);
            return new ContextLoaderProcessor(checkpointSessionService, checkpointMapper, instance);
        }
    }

    private static Property property(String name, String type, byte[] value, byte[] deprecatedValue) {
        Property property = mock(Property.class);

        when(property.getName()).thenReturn(name);
        when(property.getType()).thenReturn(type);
        when(property.getValue()).thenReturn(value);

        if (deprecatedValue != null) {
            when(property.getDeprecatedValue()).thenReturn(deprecatedValue);
        }

        return property;
    }

    private static byte[] serialize(Serializable value) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(value);
            out.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
