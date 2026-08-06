package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V103ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V104ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.ImportConfigFactory;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceConfigurationsToFilesConverterTest {

    private static final String APP_PREFIX = "qip";
    private static final String SERVICE_ID = "svc-abc";
    private static final String SPEC_GROUP_ID = "specgroup-xyz";
    private static final String SPEC_ID = "spec-001";

    @TempDir Path packageRoot;

    private ObjectMapper objectMapper;
    private ServiceTypeFiles serviceTypeFiles;
    private ServiceConfigurationsToFilesConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serviceTypeFiles = new ServiceTypeFiles(new ApplicationJsonSchemaProperties());
        converter = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX, Collections.emptyList(), serviceTypeFiles);
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

    /**
     * The shape a conformant post-#553 package actually has: the per-type schemas carry
     * {@code not: {required: [integrationSystemType]}}, so the type is stated in {@code $schema} and nowhere else.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("A service stating its type only in $schema is written under the per-type file name")
    void serviceStatingItsTypeOnlyInTheSchemaIsWrittenUnderThePerTypeFileName(IntegrationSystemType type)
            throws JsonProcessingException {
        RolloutImportConfigurationItem item = item(SERVICE_ID, objectMapper.createObjectNode());
        item.setSchema(serviceTypeFiles.schemaUri(type));
        Path expected = Path.of(SERVICE_ID)
                .resolve(SERVICE_ID + ServiceTypeFiles.postfix(type) + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(
                Map.of(SERVICE_ID, item), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    /**
     * Import reads the file name before the field and refuses a document where the two disagree, so the name has to
     * follow the field whenever the package states one.
     */
    @Test
    @DisplayName("The content field wins over a disagreeing $schema, so the written name cannot contradict it")
    void contentFieldWinsOverADisagreeingSchema() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode()
                .put("integrationSystemType", IntegrationSystemType.INTERNAL.name());
        RolloutImportConfigurationItem item = item(SERVICE_ID, content);
        item.setSchema(serviceTypeFiles.schemaUri(IntegrationSystemType.EXTERNAL));
        Path expected = Path.of(SERVICE_ID).resolve(
                SERVICE_ID + ServiceTypeFiles.postfix(IntegrationSystemType.INTERNAL) + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(
                Map.of(SERVICE_ID, item), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    /**
     * The classify → write → import chain a rollout package runs end to end. Each half was green on its own while the
     * two contradicted each other: the classifier routed a per-type {@code $schema} into the service bucket, and the
     * converter then wrote a type-less {@code .service.} name that the importer refuses.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("A post-#553 package item keeps its type from the classifier through to the imported service")
    void post553PackageItemKeepsItsTypeThroughTheWholeChain(IntegrationSystemType type) throws IOException {
        RolloutImportConfigurationItem item = item(SERVICE_ID, objectMapper.createObjectNode());
        item.setSchema(serviceTypeFiles.schemaUri(type));
        ImportConfig config = new ImportConfigFactory(new ApplicationJsonSchemaProperties(), serviceTypeFiles)
                .fromConfigurationsAndResources(List.of(item), null);
        assertThat(config.getServices()).containsKey(SERVICE_ID);

        Map<Path, byte[]> files = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX, TestServiceMigrations.all(), serviceTypeFiles)
                .convert(config.getServices(), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(),
                        emptyResourceMap());

        IntegrationSystem imported = GoldenServiceCorpus.deserializer().deserializeSystem(write(files));

        assertThat(imported.getIntegrationSystemType()).isEqualTo(type);
    }

    /**
     * The converter builds its names on its own path, so the export-side refusal of an id the current format cannot
     * state does not reach it. It writes the legacy flat name instead, which states the id whole and carries the type
     * in the document. A current-format name would come back as another id, another type, or not be discovered at all.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("An id the current format cannot state is written under the legacy flat name, type and all")
    void idTheCurrentFormatCannotStateIsWrittenUnderTheLegacyFlatName(IntegrationSystemType type) throws IOException {
        String serviceId = "svc" + ServiceTypeFiles.postfix(type) + "1";
        RolloutImportConfigurationItem item = item(serviceId, objectMapper.createObjectNode());
        item.setSchema(serviceTypeFiles.schemaUri(type));

        Map<Path, byte[]> files = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX, TestServiceMigrations.all(), serviceTypeFiles)
                .convert(Map.of(serviceId, item), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(),
                        emptyResourceMap());

        assertThat(files).containsOnlyKeys(Path.of(serviceId).resolve("service-" + serviceId + ".yaml"));
        File written = write(files);
        assertThat(ExportImportUtils.extractSystemIdFromFileName(written)).isEqualTo(serviceId);
        assertThat(GoldenServiceCorpus.deserializer().deserializeSystem(written).getIntegrationSystemType())
                .isEqualTo(type);
    }

    /** The flat prefix tells the two name formats apart, so an id carrying it belongs to the flat format too. */
    @Test
    @DisplayName("An id carrying the legacy flat prefix is written under the legacy flat name")
    void idCarryingTheLegacyFlatPrefixIsWrittenUnderTheLegacyFlatName() throws JsonProcessingException {
        String serviceId = "service-abc";

        Map<Path, byte[]> result = converter.convert(
                Map.of(serviceId, item(serviceId, objectMapper.createObjectNode())),
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsOnlyKeys(Path.of(serviceId).resolve("service-" + serviceId + ".yaml"));
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
                        new V104ServiceImportFileMigration()),
                serviceTypeFiles);
        Map<String, RolloutImportConfigurationItem> services =
                Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = stampingConverter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path servicePath = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");
        JsonNode written = objectMapper.readTree(result.get(servicePath));
        assertThat(written.path("content").path("migrations").asText()).isEqualTo("[103]");
    }

    /** Writes the converted package under the temp root and answers the single service file it holds. */
    private File write(Map<Path, byte[]> files) throws IOException {
        File serviceFile = null;
        for (Map.Entry<Path, byte[]> file : files.entrySet()) {
            Path path = packageRoot.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
            serviceFile = path.toFile();
        }
        return requireNonNull(serviceFile, "the converter wrote no file");
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
