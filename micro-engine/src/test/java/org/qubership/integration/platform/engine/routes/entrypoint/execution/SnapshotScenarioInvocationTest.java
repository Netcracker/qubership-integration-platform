package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.routes.entrypoint.bundle.SnapshotYamlMappers;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SnapshotScenarioInvocationTest {
    private final ObjectMapper mapper = SnapshotYamlMappers.create();

    @Test
    void shouldReadTimeoutWhenInvocationOverridesTheDefault() throws IOException {
        SnapshotScenarioInvocation invocation = mapper.readValue("""
                id: rejects-connection-refused
                timeoutSeconds: 120
                """, SnapshotScenarioInvocation.class);

        assertEquals(120, invocation.getTimeoutSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id: send", "id: send\ntimeoutSeconds: null"})
    void shouldLeaveTimeoutUnsetWhenInvocationUsesTheDefault(String yaml) throws IOException {
        SnapshotScenarioInvocation invocation = mapper.readValue(yaml, SnapshotScenarioInvocation.class);

        assertNull(invocation.getTimeoutSeconds());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRejectTimeoutWhenItIsNotPositive(int seconds) {
        JsonMappingException failure = assertThrows(JsonMappingException.class, () -> mapper.readValue(
                "id: send\ntimeoutSeconds: " + seconds, SnapshotScenarioInvocation.class));

        assertTrue(failure.getMessage().contains("invocation 'send' timeoutSeconds must be greater than zero."));
    }
}
