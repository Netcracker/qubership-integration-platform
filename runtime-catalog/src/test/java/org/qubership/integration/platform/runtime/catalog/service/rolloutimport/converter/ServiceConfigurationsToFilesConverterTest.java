package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V103ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V104ServiceImportFileMigration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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

    /**
     * A package built after #553 carries no {@code content.integrationSystemType}, so a plain {@code .service.} name
     * would leave the importer with no type to resolve. The file name has to state it instead.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("A service stating its type is written under the per-type file name")
    void serviceStatingItsTypeIsWrittenUnderThePerTypeFileName(IntegrationSystemType type)
            throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode().put("integrationSystemType", type.name());
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, content));
        Path expected = Path.of(SERVICE_ID)
                .resolve(SERVICE_ID + ServiceTypeFiles.postfix(type) + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("A service stating an unknown type keeps the plain service file name")
    void serviceStatingAnUnknownTypeKeepsThePlainName() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode().put("integrationSystemType", "NOT_A_TYPE");
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, content));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

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

        Path servicePath = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");
        assertThat(result).containsOnlyKeys(servicePath);
    }

    @Test
    @DisplayName("SpecGroup with parentId pointing to non-existing service is skipped")
    void specGroupWithNonExistingServiceIsSkipped() throws JsonProcessingException {
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", "non-existing-service");
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(emptyConfigMap(), emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("SpecGroup with valid service parentId creates file under service directory")
    void specGroupWithValidParentCreatesFileUnderServiceDir() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_GROUP_ID + ".api-group." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification without parentId is skipped")
    void specificationWithoutParentIdIsSkipped() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(services, specs, emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path specPath = Path.of(SERVICE_ID).resolve(SPEC_ID + ".api." + APP_PREFIX + ".yaml");
        assertThat(result).doesNotContainKey(specPath);
    }

    @Test
    @DisplayName("Specification with valid specGroup/service chain creates api file in service directory")
    void specificationWithValidChainCreatesSpecFile() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_ID + ".api." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification referencing an existing resource by filePath adds resource bytes to result")
    void specificationWithExistingResourceIncludesResourceBytes() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        specContent.put("filePath", "openapi.json");
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        String resourceContent = "{\"openapi\": \"3.0\"}";
        Map<String, String> resources = Map.of("openapi.json", resourceContent);

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), resources);

        Path expectedResource = Path.of(SERVICE_ID).resolve("openapi.json");
        assertThat(result).containsKey(expectedResource);
        assertThat(result.get(expectedResource)).isEqualTo(resourceContent.getBytes());
    }

    @Test
    @DisplayName("Specification still resolves a resource referenced by the legacy fileName field")
    void specificationWithLegacyFileNameIncludesResourceBytes() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        specContent.put("fileName", "legacy.json");
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        Map<String, String> resources = Map.of("legacy.json", "{}");

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), resources);

        assertThat(result).containsKey(Path.of(SERVICE_ID).resolve("legacy.json"));
    }

    /**
     * A package carries no version data, so the converter writes the list itself. Claiming a version it never applied
     * disables that migration for the whole rollout path, which is how the V104 group rename got skipped there.
     */
    @Test
    @DisplayName("Stamped migration versions leave out an idempotent migration, so it still runs on import")
    void stampedVersionsLeaveOutIdempotentMigrations() throws IOException {
        ServiceConfigurationsToFilesConverter stampingConverter = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX,
                List.of(new V103ServiceImportFileMigration(new ApiOperationDtoMapper()),
                        new V104ServiceImportFileMigration()));
        Map<String, RolloutImportConfigurationItem> services =
                Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = stampingConverter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path servicePath = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");
        JsonNode written = objectMapper.readTree(result.get(servicePath));
        assertThat(written.path("content").path("migrations").asText()).isEqualTo("[103]");
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
