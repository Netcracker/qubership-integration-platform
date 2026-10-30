package org.qubership.integration.platform.engine.configuration.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenSearchInitializerPauseTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer(
            mock(Environment.class), new ObjectMapper(), mock(DbaasOpensearchClient.class));

    @Test
    void shouldReturnAfterPauseWhenNotInterrupted() {
        assertDoesNotThrow(() -> initializer.pause(1L));
    }

    @Test
    void shouldThrowAndKeepInterruptFlagWhenPauseIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> initializer.pause(1000L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
