package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferElementRequestDeserializationTest {

    @Test
    void deserializesRequestWithJackson3() {
        TransferElementRequest request = JsonMapper.builder().build().readValue(
                "{\"parentId\":\"target\",\"swimlaneId\":\"lane\",\"elements\":[\"element-1\"]}",
                TransferElementRequest.class);

        assertThat(request.getParentId()).isEqualTo("target");
        assertThat(request.getSwimlaneId()).isEqualTo("lane");
        assertThat(request.getElements()).isEqualTo(List.of("element-1"));
    }
}
