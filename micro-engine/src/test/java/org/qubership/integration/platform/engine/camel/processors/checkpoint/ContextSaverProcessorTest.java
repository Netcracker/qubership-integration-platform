package org.qubership.integration.platform.engine.camel.processors.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.xml.XmlSlurper;
import groovy.xml.slurpersupport.GPathResult;
import jakarta.enterprise.inject.Instance;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextOperationsWrapper;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.Property;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextSaverProcessorTest {

    private ContextSaverProcessor processor;

    @Mock
    CheckpointSessionService checkpointSessionService;
    @Mock
    ContextOperationsWrapper contextOperations;
    @Mock
    private Exchange exchange;

    private ObjectMapper checkpointMapper;

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        checkpointMapper = ObjectMappers.getCheckpointMapper();
    }

    @Test
    void shouldSaveCheckpointWhenProcessCalledForRegularSession() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.of(contextOperations)
        );

        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_ELEMENT_ID, "9f8fe5db-e713-4f49-8b4f-4f7a0cfef9be");
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, "497db4d8-4a3b-4e2e-8f65-2ca018a72240");
        exchange.setProperty("customProperty", "custom-value");
        exchange.getMessage().setHeader("X-Test-Header", "header-value");

        when(contextOperations.getSerializableContextData())
                .thenReturn(Map.of("trace", Map.of("traceId", "123")));

        try (MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class)) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("body-value");

            processor.process(exchange);
        }

        ArgumentCaptor<Checkpoint> checkpointCaptor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointSessionService).saveAndAssignCheckpoint(
                checkpointCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("497db4d8-4a3b-4e2e-8f65-2ca018a72240")
        );

        Checkpoint checkpoint = checkpointCaptor.getValue();

        assertEquals("9f8fe5db-e713-4f49-8b4f-4f7a0cfef9be", checkpoint.getCheckpointElementId());
        assertArrayEquals("body-value".getBytes(StandardCharsets.UTF_8), checkpoint.getBody());

        JsonNode actualHeaders = checkpointMapper.readTree(checkpoint.getHeaders());
        JsonNode expectedHeaders = checkpointMapper.readTree("""
                {
                  "X-Test-Header": "header-value"
                }
                """);
        assertEquals(expectedHeaders, actualHeaders);

        JsonNode actualContextData = checkpointMapper.readTree(checkpoint.getContextData());
        JsonNode expectedContextData = checkpointMapper.readTree("""
                {
                  "trace": {
                    "traceId": "123"
                  }
                }
                """);
        assertEquals(expectedContextData, actualContextData);

        Property savedProperty = checkpoint.getProperties().stream()
                .filter(property -> "customProperty".equals(property.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(String.class.getName(), savedProperty.getType());
        assertEquals("custom-value", deserialize(savedProperty.getValue()));
    }

    @Test
    void shouldSkipCheckpointSaveWhenChainCallTriggeredSession() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, true);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_ELEMENT_ID, "1b9b5406-1a56-4887-9d27-2b777b7bc8de");

        try (MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class)) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("body-value");

            processor.process(exchange);
        }

        verify(checkpointSessionService, never()).saveAndAssignCheckpoint(any(), any());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenCheckpointSaveFails() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_ELEMENT_ID, "7a37f33b-30ae-4183-9fd7-f0e0b980b005");
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, "00f05368-cf2f-4eb3-a318-8f2e4f7c4e31");

        doThrow(new RuntimeException("boom"))
                .when(checkpointSessionService)
                .saveAndAssignCheckpoint(any(), any());

        try (MockedStatic<MessageHelper> messageHelperMock = mockStatic(MessageHelper.class)) {
            messageHelperMock.when(() -> MessageHelper.extractBody(exchange))
                    .thenReturn("body-value");

            RuntimeException exception = assertThrows(RuntimeException.class, () -> processor.process(exchange));

            assertEquals("Failed to create session checkpoint", exception.getMessage());
            assertEquals("boom", exception.getCause().getMessage());
        }
    }

    @Test
    void shouldCreatePropertiesForSave() {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        List<Property> result = processor.getPropertiesForSave(Map.of("customProperty", "custom-value"));

        assertEquals(1, result.size());
        assertEquals("customProperty", result.getFirst().getName());
        assertEquals(String.class.getName(), result.getFirst().getType());
        assertEquals("custom-value", deserialize(result.getFirst().getValue()));
    }

    @Test
    void shouldSerializeSerializablePropertyWithIoLibrary() {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        byte[] result = processor.serializeProperty(String.class, "value");

        assertEquals("value", deserialize(result));
    }

    @Test
    void shouldSerializeNonSerializablePropertyWithObjectMapper() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        NonSerializableProperty property = new NonSerializableProperty("value");

        byte[] result = processor.serializeProperty(NonSerializableProperty.class, property);

        assertArrayEquals(checkpointMapper.writeValueAsBytes(property), result);
    }

    @Test
    void shouldFallbackToObjectMapperWhenSerializablePropertyCannotBeSerializedWithIoLibrary() throws Exception {
        ObjectMapper mockCheckpointMapper = mock(ObjectMapper.class);
        ContextSaverProcessor processor = processor(
                checkpointSessionService,
                mockCheckpointMapper,
                Optional.empty()
        );

        BrokenSerializableProperty property = new BrokenSerializableProperty("value");
        byte[] bytes = "json".getBytes(StandardCharsets.UTF_8);

        when(mockCheckpointMapper.writeValueAsBytes(property)).thenReturn(bytes);

        byte[] result = processor.serializeProperty(BrokenSerializableProperty.class, property);

        assertArrayEquals(bytes, result);
    }

    @Test
    void shouldSerializeGPathResultProperty() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        GPathResult property = new XmlSlurper().parseText("<root><value>42</value></root>");

        byte[] result = processor.serializeProperty(property.getClass(), property);

        String xml = new String(result, StandardCharsets.UTF_8);
        assertNotNull(xml);
        assertTrue(xml.contains("42"));
    }

    @Test
    void shouldSerializeWithObjectMapper() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        byte[] result = processor.serializeWithObjectMapper("value");

        assertArrayEquals(checkpointMapper.writeValueAsBytes("value"), result);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenSerializeWithObjectMapperFails() throws Exception {
        ObjectMapper mockCheckpointMapper = mock(ObjectMapper.class);
        ContextSaverProcessor processor = processor(
                checkpointSessionService,
                mockCheckpointMapper,
                Optional.empty()
        );

        when(mockCheckpointMapper.writeValueAsBytes("value")).thenThrow(new RuntimeException("boom"));

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> processor.serializeWithObjectMapper("value")
        );

        assertEquals("Failed to create session checkpoint", exception.getMessage());
        assertEquals("boom", exception.getCause().getMessage());
    }

    @Test
    void shouldSerializeWithIoLibrary() throws Exception {
        processor = processor(
                checkpointSessionService,
                checkpointMapper,
                Optional.empty()
        );

        byte[] result = processor.serializeWithIOLibrary("value");

        assertEquals("value", deserialize(result));
    }

    private static ContextSaverProcessor processor(
            CheckpointSessionService checkpointSessionService,
            ObjectMapper checkpointMapper,
            Optional<ContextOperationsWrapper> contextOperations
    ) {
        @SuppressWarnings("unchecked")
        Instance<ContextOperationsWrapper> instance = mock(Instance.class);

        try (MockedStatic<InjectUtil> injectUtilMock = mockStatic(InjectUtil.class)) {
            injectUtilMock.when(() -> InjectUtil.injectOptional(instance)).thenReturn(contextOperations);
            return new ContextSaverProcessor(checkpointSessionService, checkpointMapper, instance);
        }
    }

    private static Object deserialize(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream in = new ObjectInputStream(bis)) {
            return in.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class NonSerializableProperty {
        private final String value;

        private NonSerializableProperty(String value) {
            this.value = value;
        }

        @SuppressWarnings("unused")
        public String getValue() {
            return value;
        }
    }

    private static class BrokenSerializableProperty implements Serializable {
        private final Object value;

        private BrokenSerializableProperty(Object value) {
            this.value = new Thread(String.valueOf(value));
        }

        @SuppressWarnings("unused")
        public Object getValue() {
            return value;
        }
    }
}
