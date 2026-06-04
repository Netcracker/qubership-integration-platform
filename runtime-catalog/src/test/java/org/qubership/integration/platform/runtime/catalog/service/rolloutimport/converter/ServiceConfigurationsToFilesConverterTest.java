package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceConfigurationsToFilesConverterTest {

    private static final String APP_PREFIX = "qip";
    private static final String SERVICE_ID = "service-abc";
    private static final String SPEC_GROUP_ID = "specgroup-xyz";
    private static final String SPEC_ID = "spec-001";

    private ObjectMapper objectMapper;
    private ServiceConfigurationsToFilesConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new ServiceConfigurationsToFilesConverter(objectMapper, APP_PREFIX, Collections.emptyList());
    }

    @Test
    @DisplayName("All empty inputs return empty map")
    void allEmptyInputsReturnEmptyMap() throws JsonProcessingException {
        Map<Path, byte[]> result = converter.convert(
                emptyConfigMap(),
                emptyConfigMap(),
                emptyConfigMap(),
                emptyConfigMap(),
                emptyResourceMap()
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single service creates {serviceId}/{serviceId}.service.{appPrefix}.yaml")
    void singleServiceCreatesCorrectFilePath() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Single contextService creates {serviceId}/{serviceId}.context-service.{appPrefix}.yaml")
    void singleContextServiceCreatesCorrectFilePath() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> contextServices = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".context-service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), contextServices, emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("SpecGroup without parentId in content is skipped")
    void specGroupWithoutParentIdIsSkipped() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path specGroupPath = Path.of(SERVICE_ID).resolve(SPEC_GROUP_ID + ".specification-group." + APP_PREFIX + ".yaml");
        assertThat(result).doesNotContainKey(specGroupPath);
    }

    @Test
    @DisplayName("SpecGroup with parentId pointing to non-existing service is skipped")
    void specGroupWithNonExistingServiceIsSkipped() throws JsonProcessingException {
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", "non-existing-service");
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(emptyConfigMap(), emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path specGroupPath = Path.of("non-existing-service").resolve(SPEC_GROUP_ID + ".specification-group." + APP_PREFIX + ".yaml");
        assertThat(result).doesNotContainKey(specGroupPath);
    }

    @Test
    @DisplayName("SpecGroup with valid service parentId creates file under service directory")
    void specGroupWithValidParentCreatesFileUnderServiceDir() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_GROUP_ID + ".specification-group." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification without parentId is skipped")
    void specificationWithoutParentIdIsSkipped() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(services, specs, emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path specPath = Path.of(SERVICE_ID).resolve(SPEC_ID + ".specification." + APP_PREFIX + ".yaml");
        assertThat(result).doesNotContainKey(specPath);
    }

    @Test
    @DisplayName("Specification with valid specGroup/service chain creates spec file in service directory")
    void specificationWithValidChainCreatesSpecFile() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_ID + ".specification." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification referencing an existing resource adds resource bytes to result")
    void specificationWithExistingResourceIncludesResourceBytes() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        specContent.put("fileName", "openapi.json");
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        String resourceContent = "{\"openapi\": \"3.0\"}";
        Map<String, String> resources = Map.of("openapi.json", resourceContent);

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), resources);

        Path expectedResource = Path.of(SERVICE_ID).resolve("openapi.json");
        assertThat(result).containsKey(expectedResource);
        assertThat(result.get(expectedResource)).isEqualTo(resourceContent.getBytes());
    }

    private Map<String, RolloutImportConfigurationItem> emptyConfigMap() {
        return Collections.emptyMap();
    }

    private Map<String, String> emptyResourceMap() {
        return Collections.emptyMap();
    }

    private RolloutImportConfigurationItem item(String id, ObjectNode content) {
        RolloutImportConfigurationItem item = new RolloutImportConfigurationItem();
        item.setId(id);
        item.setContent(content);
        return item;
    }
}
