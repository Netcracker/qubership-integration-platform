package org.qubership.integration.platform.runtime.catalog.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jackson-reachable half of the api-group rename compatibility surface. Each shim below is a single annotation
 * that nothing else exercises, and dropping one breaks a caller with no compile error and no test failure.
 */
class ApiGroupWireCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"SPECIFICATION_GROUP", "API_GROUP"})
    void filterRequestAcceptsBothTheOldAndTheNewFeatureValue(String value) throws Exception {
        FilterRequestDTO filter = mapper.readValue(
                "{\"column\":\"" + value + "\",\"condition\":\"IS\",\"value\":\"x\"}", FilterRequestDTO.class);

        assertEquals(FilterFeature.API_GROUP, filter.getFeature());
    }

    @Test
    void instructionFileAcceptsTheOldSpecificationGroupsSection() throws Exception {
        GeneralImportInstructionsConfig config = mapper.readValue(
                "{\"specificationGroups\":{\"delete\":[\"group-1\"]}}", GeneralImportInstructionsConfig.class);

        assertEquals(Set.of("group-1"), config.getApiGroups().getDelete());
    }

    @Test
    void instructionFileAcceptsTheRenamedApiGroupsSection() throws Exception {
        GeneralImportInstructionsConfig config = mapper.readValue(
                "{\"apiGroups\":{\"delete\":[\"group-1\"]}}", GeneralImportInstructionsConfig.class);

        assertEquals(Set.of("group-1"), config.getApiGroups().getDelete());
    }

    @Test
    void instructionFileSerializesTheRenamedSectionKey() {
        GeneralImportInstructionsConfig config = GeneralImportInstructionsConfig.builder()
                .apiGroups(ImportInstructionsConfig.builder().delete(Set.of("group-1")).build())
                .build();

        JsonNode serialized = mapper.valueToTree(config);

        assertTrue(serialized.has("apiGroups"), "new files are written under the renamed key");
        assertFalse(serialized.has("specificationGroups"), "the old key is read-only compatibility");
    }

    /**
     * The service document binds through Lombok's {@code @Jacksonized} {@code @SuperBuilder}, so the alias has to
     * survive that indirection. Nothing else in the suite reaches this DTO with the pre-rename key: every fixture
     * lets V104 rename the field first.
     */
    @Test
    void serviceContentAcceptsTheOldInlineGroupListKey() throws Exception {
        IntegrationSystemContentDto content = mapper.readValue(
                "{\"specificationGroups\":[{\"id\":\"group-1\",\"name\":\"Test group\"}]}",
                IntegrationSystemContentDto.class);

        assertEquals(1, content.getApiGroups().size());
        assertEquals("group-1", content.getApiGroups().get(0).getId());
    }

    @Test
    void serviceContentAcceptsTheRenamedInlineGroupListKey() throws Exception {
        IntegrationSystemContentDto content = mapper.readValue(
                "{\"apiGroups\":[{\"id\":\"group-1\",\"name\":\"Test group\"}]}",
                IntegrationSystemContentDto.class);

        assertEquals(1, content.getApiGroups().size());
        assertEquals("group-1", content.getApiGroups().get(0).getId());
    }

    @Test
    void theV1ResponseKeepsItsSpecificationGroupsProperty() {
        GeneralImportInstructionsDTO dto = GeneralImportInstructionsDTO.builder()
                .specificationGroups(ImportInstructionsDTO.builder().build())
                .build();

        JsonNode serialized = mapper.valueToTree(dto);

        assertTrue(serialized.has("specificationGroups"),
                "renaming this property silently empties the admin page, which falls back to an empty object");
        assertFalse(serialized.has("apiGroups"));
    }
}
