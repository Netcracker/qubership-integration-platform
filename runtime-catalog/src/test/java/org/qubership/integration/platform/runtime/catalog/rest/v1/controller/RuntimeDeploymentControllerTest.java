package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeDeploymentControllerTest {

    @Test
    void returnsRawJsonBytesForRuntimeDeploymentResponse() {
        RuntimeDeploymentService runtimeDeploymentService = mock(RuntimeDeploymentService.class);
        when(runtimeDeploymentService.getChainRuntimeDeployments()).thenReturn(Map.of("chain-1", List.of()));
        RuntimeDeploymentController controller = new RuntimeDeploymentController(
                runtimeDeploymentService, JsonMapper.builder().build());

        ResponseEntity<?> response = controller.findChainRuntimeDeployments(null);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(byte[].class);
        assertThat(new String((byte[]) response.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"chain-1\":[]}");
    }
}
