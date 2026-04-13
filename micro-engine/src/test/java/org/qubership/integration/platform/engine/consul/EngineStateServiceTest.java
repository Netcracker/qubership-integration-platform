package org.qubership.integration.platform.engine.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.KeyValueOptions;
import io.vertx.mutiny.ext.consul.ConsulClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusComponentTest(value = {EngineStateService.class, ConsulKeyValidator.class})
@TestConfigProperty(key = "consul.keys.prefix", value = "config/test")
@TestConfigProperty(key = "consul.keys.engine-config-root", value = "/qip-engine-configurations")
@TestConfigProperty(key = "consul.keys.engines-state", value = "/engines-state")
@TestConfigProperty(key = "consul.dynamic-state-keys.enabled", value = "false")
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineStateServiceTest {

    private static final String SESSION_ID = "d40c8573-f495-4f69-a2ff-5e13759e6f9f";

    @Inject
    EngineStateService service;

    @InjectMock
    ConsulClient consulClient;

    @InjectMock
    ConsulSessionService consulSessionService;

    @InjectMock
    EngineInfo engineInfo;

    @InjectMock
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    @InjectMock
    Supplier<ConsulClient> consulClientSupplier;

    @BeforeEach
    void setUp() {
        when(consulClientSupplier.get()).thenReturn(consulClient);
    }

    @Test
    void shouldPutSerializedStateToConsulWhenSessionPresentAndDynamicKeysDisabled() throws Exception {
        EngineState state = new EngineState();

        when(consulSessionService.getOrCreateSession()).thenReturn(SESSION_ID);
        when(engineInfo.getEngineDeploymentName()).thenReturn("engine-deployment");
        when(engineInfo.getDomain()).thenReturn("customer-domain");
        when(engineInfo.getHost()).thenReturn("host.example.com");
        when(objectMapper.writeValueAsString(state)).thenReturn("serialized-state");
        when(consulClient.putValueWithOptions(
                eq("config/test/qip-engine-configurations/engines-state/engine-deployment-customer-domain-host_example_com"),
                eq("serialized-state"),
                any(KeyValueOptions.class)
        )).thenReturn(Uni.createFrom().item(true));

        service.updateState(state);

        ArgumentCaptor<KeyValueOptions> optionsCaptor = ArgumentCaptor.forClass(KeyValueOptions.class);
        verify(consulClient).putValueWithOptions(
                eq("config/test/qip-engine-configurations/engines-state/engine-deployment-customer-domain-host_example_com"),
                eq("serialized-state"),
                optionsCaptor.capture()
        );

        assertEquals(SESSION_ID, optionsCaptor.getValue().getAcquireSession());
    }

    @Test
    @TestConfigProperty(key = "consul.dynamic-state-keys.enabled", value = "true")
    void shouldAppendLocalNodeIdToConsulKeyWhenDynamicStateKeysEnabled() throws Exception {
        EngineState state = new EngineState();

        when(consulSessionService.getOrCreateSession()).thenReturn(SESSION_ID);
        when(engineInfo.getEngineDeploymentName()).thenReturn("engine-deployment");
        when(engineInfo.getDomain()).thenReturn("customer-domain");
        when(engineInfo.getHost()).thenReturn("host.example.com");
        when(objectMapper.writeValueAsString(state)).thenReturn("serialized-state");
        when(consulClient.putValueWithOptions(
                any(),
                eq("serialized-state"),
                any(KeyValueOptions.class)
        )).thenReturn(Uni.createFrom().item(true));

        service.updateState(state);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(consulClient).putValueWithOptions(
                keyCaptor.capture(),
                eq("serialized-state"),
                any(KeyValueOptions.class)
        );

        assertTrue(Pattern.matches(
                "^config/test/qip-engine-configurations/engines-state/"
                        + "engine-deployment-customer-domain-host_example_com-[0-9a-f\\-]{36}$",
                keyCaptor.getValue()
        ));
    }

    @Test
    void shouldThrowRuntimeExceptionWhenActiveSessionAbsent() {
        EngineState state = new EngineState();

        when(consulSessionService.getOrCreateSession()).thenReturn(null);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.updateState(state)
        );

        assertEquals("Active consul session is not present", exception.getMessage());
        verifyNoInteractions(objectMapper);
        verify(consulClient, never()).putValueWithOptions(any(), any(), any());
    }

    @Test
    void shouldWrapJsonProcessingExceptionWhenSerializationFails() throws Exception {
        EngineState state = new EngineState();
        JsonProcessingException cause = new JsonProcessingException("Serialization failed") {
        };

        when(consulSessionService.getOrCreateSession()).thenReturn(SESSION_ID);
        when(engineInfo.getEngineDeploymentName()).thenReturn("engine-deployment");
        when(engineInfo.getDomain()).thenReturn("customer-domain");
        when(engineInfo.getHost()).thenReturn("host.example.com");
        when(objectMapper.writeValueAsString(state)).thenThrow(cause);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.updateState(state)
        );

        assertSame(cause, exception.getCause());
        verify(consulClient, never()).putValueWithOptions(any(), any(), any());
    }
}
