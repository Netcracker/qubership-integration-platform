package org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralImportInstructionsConfigTest {

    private final YAMLMapper yamlMapper = new YAMLMapper();

    @Test
    @DisplayName("an instruction file written with the pre-rename specificationGroups key still loads")
    void oldFieldNameStillDeserializes() throws Exception {
        String yaml = """
                specificationGroups:
                  delete:
                    - group-1
                """;

        GeneralImportInstructionsConfig config = yamlMapper.readValue(yaml, GeneralImportInstructionsConfig.class);

        assertThat(config.getApiGroups().getDelete()).containsExactly("group-1");
    }

    @Test
    @DisplayName("an instruction file written with the renamed apiGroups key loads")
    void newFieldNameDeserializes() throws Exception {
        String yaml = """
                apiGroups:
                  delete:
                    - group-1
                """;

        GeneralImportInstructionsConfig config = yamlMapper.readValue(yaml, GeneralImportInstructionsConfig.class);

        assertThat(config.getApiGroups().getDelete()).containsExactly("group-1");
    }
}
