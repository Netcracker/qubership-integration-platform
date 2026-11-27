package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.checkpoint.CheckpointPayloadOptions;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointMapperConfigurationTest {

    @Test
    void checkpointMapperKeepsDefaultMapsWhenRetryOnlyReplacesHeaders() {
        CheckpointPayloadOptions options = new MapperConfiguration().checkpointMapper()
                .readValue("{\"headers\":{\"e2e-fail\":\"false\"}}", CheckpointPayloadOptions.class);

        assertThat(options.getHeaders()).containsEntry("e2e-fail", "false");
        assertThat(options.getProperties()).isEmpty();
    }
}
