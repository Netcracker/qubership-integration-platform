package org.qubership.integration.platform.runtime.catalog.configuration;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.chain.SessionsLoggingLevel;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.instructions.DeleteInstructionsRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapperAutoConfigurationTest {

    @Test
    void primaryMapperOmitsNullMapValues() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("contextPath", null);

        String serialized = new MapperAutoConfiguration().primaryObjectMapper().writeValueAsString(properties);

        assertThat(serialized).doesNotContain("contextPath");
    }

    @Test
    void primaryMapperReadsDeleteInstructionIds() {
        String requestBody = """
                {"chains":["chain-id"],"services":[],"commonVariables":[]}
                """;

        DeleteInstructionsRequest request = new MapperAutoConfiguration().primaryObjectMapper()
                .readValue(requestBody, DeleteInstructionsRequest.class);

        assertThat(request.getChains()).containsExactly("chain-id");
    }

    @Test
    void primaryMapperReadsPartialLoggingPropertiesRequest() {
        String requestBody = """
                {"sessionsLoggingLevel":"DEBUG"}
                """;

        DeploymentRuntimeProperties request = new MapperAutoConfiguration().primaryObjectMapper()
                .readValue(requestBody, DeploymentRuntimeProperties.class);

        assertThat(request.getSessionsLoggingLevel()).isEqualTo(SessionsLoggingLevel.DEBUG);
        assertThat(request.isMaskingEnabled()).isFalse();
    }

    @Test
    void exportImportYamlMapperReadsPartialLoggingProperties() {
        DeploymentRuntimeProperties request = new MapperAutoConfiguration()
                .yamlExportImportMapper(false)
                .readValue("sessionsLoggingLevel: DEBUG", DeploymentRuntimeProperties.class);

        assertThat(request.isMaskingEnabled()).isFalse();
    }
}
