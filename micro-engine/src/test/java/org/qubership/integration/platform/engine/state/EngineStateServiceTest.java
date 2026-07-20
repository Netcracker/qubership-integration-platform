package org.qubership.integration.platform.engine.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.KeyValueOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.consul.ConsulKeyValidator;
import org.qubership.integration.platform.engine.consul.ConsulSessionService;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.engine.EngineState;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineStateServiceTest {

    private static final String SESSION_ID = "7289cd81-4380-4850-9145-26541be8b2ce";
    private static final String SERIALIZED_STATE = "{\"state\":\"ACTIVE\"}";
    private static final String VALID_ENGINE_KEY = "qip-engine-v1-default-localhost";

    @Mock
    private Supplier<ConsulClient> consulClientSupplier;

    @Mock
    private ConsulClient consulClient;

    @Mock
    private ConsulSessionService consulSessionService;

    @Mock
    private EngineInfo engineInfo;

    @Mock
    private ConsulKeyValidator consulKeyValidator;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private EngineState state;

    private EngineStateService service;

    @BeforeEach
    void setUp() {
        service = new EngineStateService();
        service.keyPrefix = "qip";
        service.keyEngineConfigRoot = "/engine-config";
        service.keyEnginesState = "/engines-state";
        service.dynamicStateKeys = false;
        service.consulClientSupplier = consulClientSupplier;
        service.consulSessionService = consulSessionService;
        service.engineInfo = engineInfo;
        service.consulKeyValidator = consulKeyValidator;
        service.objectMapper = objectMapper;
    }

    @Test
    void shouldThrowWhenActiveConsulSessionIsMissing() {
        when(consulSessionService.getOrCreateSession()).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.updateState(state));

        assertEquals("Active consul session is not present", exception.getMessage());
        verifyNoInteractions(engineInfo, consulKeyValidator, objectMapper, consulClientSupplier, consulClient);
    }

    @Test
    void shouldUpdateEngineStateInConsulWithAcquiredSession() throws Exception {
        stubConsulSessionForKvWrite();
        stubEngineInfo();
        when(consulKeyValidator.makeKeyValid("qip-engine-v1-default-localhost")).thenReturn(VALID_ENGINE_KEY);
        when(objectMapper.writeValueAsString(state)).thenReturn(SERIALIZED_STATE);
        when(consulClientSupplier.get()).thenReturn(consulClient);

        ArgumentCaptor<KeyValueOptions> optionsCaptor = ArgumentCaptor.forClass(KeyValueOptions.class);
        when(consulClient.putValueWithOptions(
            eq("qip/engine-config/engines-state/" + VALID_ENGINE_KEY),
            eq(SERIALIZED_STATE),
            optionsCaptor.capture()
        )).thenReturn(Uni.createFrom().item(true));

        service.updateState(state);

        assertEquals(SESSION_ID, optionsCaptor.getValue().getAcquireSession());
    }

    @Test
    void shouldBuildDynamicConsulKeyWhenDynamicStateKeysAreEnabled() throws Exception {
        service.dynamicStateKeys = true;

        stubConsulSessionForKvWrite();
        stubEngineInfo();
        when(consulKeyValidator.makeKeyValid(anyString())).thenReturn("valid-dynamic-engine-key");
        when(objectMapper.writeValueAsString(state)).thenReturn(SERIALIZED_STATE);
        when(consulClientSupplier.get()).thenReturn(consulClient);
        when(consulClient.putValueWithOptions(
            eq("qip/engine-config/engines-state/valid-dynamic-engine-key"),
            eq(SERIALIZED_STATE),
            org.mockito.ArgumentMatchers.any(KeyValueOptions.class)
        )).thenReturn(Uni.createFrom().item(true));

        service.updateState(state);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(consulKeyValidator).makeKeyValid(keyCaptor.capture());

        String rawEngineKey = keyCaptor.getValue();
        assertTrue(rawEngineKey.startsWith("qip-engine-v1-default-localhost-"));
        assertTrue(rawEngineKey.length() > "qip-engine-v1-default-localhost-".length());
    }

    @Test
    void shouldWrapJsonProcessingExceptionWhenStateSerializationFails() throws Exception {
        JsonProcessingException jsonProcessingException = new JsonProcessingException("Serialization failed") {
        };

        stubConsulSession();
        stubEngineInfo();
        when(consulKeyValidator.makeKeyValid("qip-engine-v1-default-localhost")).thenReturn(VALID_ENGINE_KEY);
        when(objectMapper.writeValueAsString(state)).thenThrow(jsonProcessingException);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.updateState(state));

        assertSame(jsonProcessingException, exception.getCause());
        verifyNoInteractions(consulClientSupplier, consulClient);
    }

    @Test
    void shouldThrowTimeoutExceptionWhenKvWriteStalls() throws Exception {
        stubConsulSession();
        stubEngineInfo();
        when(consulSessionService.getAwaitTimeout()).thenReturn(Duration.ofMillis(100));
        when(consulKeyValidator.makeKeyValid("qip-engine-v1-default-localhost")).thenReturn(VALID_ENGINE_KEY);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(EngineState.class)))
                .thenReturn(SERIALIZED_STATE);
        when(consulClientSupplier.get()).thenReturn(consulClient);
        when(consulClient.putValueWithOptions(
                eq("qip/engine-config/engines-state/" + VALID_ENGINE_KEY),
                eq(SERIALIZED_STATE),
                org.mockito.ArgumentMatchers.any(KeyValueOptions.class)
        )).thenReturn(Uni.createFrom().nothing());

        EngineState engineState = new EngineState();
        assertThrows(TimeoutException.class, () -> service.updateState(engineState));
    }

    @Test
    void shouldPropagateConsulFailureWhenPutValueFails() throws Exception {
        IllegalStateException consulException = new IllegalStateException("Consul write failed");

        stubConsulSessionForKvWrite();
        stubEngineInfo();
        when(consulKeyValidator.makeKeyValid("qip-engine-v1-default-localhost")).thenReturn(VALID_ENGINE_KEY);
        when(objectMapper.writeValueAsString(state)).thenReturn(SERIALIZED_STATE);
        when(consulClientSupplier.get()).thenReturn(consulClient);
        when(consulClient.putValueWithOptions(
            eq("qip/engine-config/engines-state/" + VALID_ENGINE_KEY),
            eq(SERIALIZED_STATE),
            org.mockito.ArgumentMatchers.any(KeyValueOptions.class)
        )).thenReturn(Uni.createFrom().failure(consulException));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.updateState(state));

        assertSame(consulException, exception);
    }

    private void stubConsulSession() {
        when(consulSessionService.getOrCreateSession()).thenReturn(SESSION_ID);
    }

    private void stubConsulKvTimeout() {
        when(consulSessionService.getAwaitTimeout()).thenReturn(Duration.ofSeconds(51));
    }

    private void stubConsulSessionForKvWrite() {
        stubConsulSession();
        stubConsulKvTimeout();
    }

    private void stubEngineInfo() {
        when(engineInfo.getEngineDeploymentName()).thenReturn("qip-engine-v1");
        when(engineInfo.getDomain()).thenReturn("default");
        when(engineInfo.getHost()).thenReturn("localhost");
    }
}
