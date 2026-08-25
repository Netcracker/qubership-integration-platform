package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedApiGroup;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedIntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecificationSource;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V103RevertMigrationTest {

    private static final String APP_NAME = "qip";
    private static final URI API_SCHEMA = URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml");
    private static final URI SERVICE_SCHEMA = URI.create("http://qubership.org/schemas/product/qip/service.schema.yaml");
    private static final URI GROUP_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification-group.schema.yaml");
    private static final URI SPECIFICATION_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification.schema.yaml");

    private final V103RevertMigration migration = new V103RevertMigration(
            new ApiOperationDtoMapper(), SPECIFICATION_SCHEMA, TestRevertMigrations.matcher());
    private final YAMLMapper mapper = new MapperAutoConfiguration().yamlExportImportMapper();

    // --- model node transform --------------------------------------------------------------------------------------

    @Test
    void revertsAnApiModelNodeToTheSpecificationShape() throws JsonProcessingException {
        ObjectNode content = migration.revert(read("""
                ---
                id: "spec-1"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  specificationVersion: "3.0.0"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "group-1"
                  operations:
                  - id: "op-1"
                    name: "addPet"
                    type: "openapi"
                    method: "get"
                    path: "/pets"
                    summary: "List pets"
                    isDeprecated: false
                    specification:
                      summary: "List pets"
                  specifications:
                  - id: "src-1"
                    name: "api.yaml"
                    filePath: "source-spec-1/api.yaml"
                    isRoot: true
                """)).path("content").deepCopy();

        assertFalse(content.has("specificationType"), "specificationType is dropped, or import treats it as current");
        assertFalse(content.has("specificationVersion"), "specificationVersion is not part of the legacy shape");

        JsonNode operation = content.path("operations").path(0);
        assertEquals("op-1", operation.path("id").asText());
        assertEquals("addPet", operation.path("name").asText());
        assertEquals("GET", operation.path("method").asText(), "openapi method is uppercased back to the legacy column");
        assertEquals("/pets", operation.path("path").asText());
        assertFalse(operation.has("type"), "the typed discriminator collapses away");
        assertFalse(operation.has("summary"), "typed-only fields do not belong to the legacy shape");
        assertFalse(operation.has("isDeprecated"), "typed-only fields do not belong to the legacy shape");
        assertFalse(operation.has("requestSchema"), "de-materialized request schema stays absent");
        assertFalse(operation.has("responseSchemas"), "de-materialized response schemas stay absent");
    }

    @Test
    void renamesSourceFieldsBackToTheLegacyShape() throws JsonProcessingException {
        ObjectNode content = migration.revert(read("""
                ---
                id: "spec-1"
                content:
                  specifications:
                  - id: "src-1"
                    name: "api.yaml"
                    filePath: "source-spec-1/api.yaml"
                    isRoot: true
                """)).path("content").deepCopy();

        assertFalse(content.has("specifications"), "the api array name is gone");
        JsonNode source = content.path("specificationSources").path(0);
        assertEquals("source-spec-1/api.yaml", source.path("fileName").asText());
        assertTrue(source.path("mainSource").asBoolean());
        assertFalse(source.has("filePath"));
        assertFalse(source.has("isRoot"));
        assertEquals("src-1", source.path("id").asText(), "the source id survives");
    }

    @Test
    void collapsesAsyncapiOperationsBackToMethodAndChannelPath() throws JsonProcessingException {
        JsonNode operation = migration.revert(read("""
                ---
                id: "spec-1"
                content:
                  operations:
                  - id: "op-1"
                    type: "asyncapi"
                    method: "publish"
                    channel: "user/notify"
                """)).path("content").path("operations").path(0);

        assertEquals("publish", operation.path("method").asText());
        assertEquals("user/notify", operation.path("path").asText(), "the channel becomes the legacy path");
        assertFalse(operation.has("channel"), "the typed channel field collapses away");
        assertFalse(operation.has("type"));
    }

    @Test
    void keepsTheOperationSpecificationThroughTheRevert() throws JsonProcessingException {
        JsonNode operation = migration.revert(read("""
                ---
                id: "spec-1"
                content:
                  operations:
                  - id: "op-1"
                    type: "asyncapi"
                    method: "publish"
                    channel: "user/notify"
                    specification:
                      maas.kafka.classifier.name: "orders-topic"
                """)).path("content").path("operations").path(0);

        assertEquals("orders-topic", operation.path("specification").path("maas.kafka.classifier.name").asText(),
                "the MaaS classifier stored on the operation specification survives the revert");
    }

    @Test
    void stampsTheSpecificationSchemaOnTheRevertedModelFile() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "spec-1"
                $schema: "http://qubership.org/schemas/product/qip/api.schema.yaml"
                content:
                  specificationType: "openapi"
                  operations: []
                """));

        assertEquals(SPECIFICATION_SCHEMA.toString(), result.path("$schema").asText(),
                "a legacy .specification artifact must carry the specification $schema, not the api one");
    }

    @Test
    void leavesTheServiceSchemaUntouched() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[103]"
                """));

        assertEquals(SERVICE_SCHEMA.toString(), result.path("$schema").asText(),
                "the service document keeps its own $schema; only the model file is restamped");
    }

    // --- service node migrations strip -----------------------------------------------------------------------------

    @Test
    void stripsVersion103FromTheServiceMigrations() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "system-1"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103]"
                """));

        assertEquals("[100, 101, 102]", result.path("content").path("migrations").asText(),
                "103 is stripped so the forward migration re-runs on import");
        assertFalse(result.path("content").has("specificationType"), "the service node is not turned into a model");
    }

    // --- specification group apis list -----------------------------------------------------------------------------

    @Test
    void dropsTheApisListFromTheSpecificationGroup() throws JsonProcessingException {
        ObjectNode content = migration.revert(read("""
                ---
                id: "group-1"
                name: "group"
                content:
                  description: "Pet store APIs"
                  synchronization: false
                  parentId: "system-1"
                  apis:
                  - "spec-1"
                  - "spec-2"
                """)).path("content").deepCopy();

        assertFalse(content.has("apis"), "apis arrived with version 103 and is not part of the legacy group shape");
        assertEquals("Pet store APIs", content.path("description").asText(), "the group description survives");
        assertFalse(content.path("synchronization").asBoolean(), "the synchronization flag survives");
        assertEquals("system-1", content.path("parentId").asText(), "the parent link survives");
    }

    @Test
    void doesNotStampASchemaOnTheRevertedSpecificationGroup() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                  apis:
                  - "spec-1"
                """));

        assertFalse(result.has("$schema"),
                "the specification $schema restamp is api-model only; a group must not pick it up");
    }

    // --- supportsDocument ------------------------------------------------------------------------------------------

    @Test
    void supportsASpecificationGroupThatCarriesAnApisList() throws JsonProcessingException {
        ObjectNode group = read("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                  apis:
                  - "spec-1"
                """);

        assertTrue(migration.supportsDocument(group), "the group has an apis list to strip");
    }

    @Test
    void supportsApiModelAndServiceButRejectsChainAndAGroupWithoutApis() throws JsonProcessingException {
        ObjectNode apiModel = read("""
                ---
                id: "spec-1"
                content:
                  specificationType: "openapi"
                  operations: []
                """);
        ObjectNode service = read("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[103]"
                """);
        ObjectNode chain = read("""
                ---
                id: "chain-1"
                content:
                  elements: []
                  migrations: "[103, 108]"
                """);
        ObjectNode group = read("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                """);

        assertTrue(migration.supportsDocument(apiModel), "an api-model document is reverted");
        assertTrue(migration.supportsDocument(service), "a service document has its migrations stripped");
        assertFalse(migration.supportsDocument(chain), "a chain owns its own 103 and must be left alone");
        assertFalse(migration.supportsDocument(group),
                "an empty group omits apis, so there is nothing to strip");
    }

    @Test
    void leavesAChainMigrationsListUntouchedWhenTheGuardRejectsIt() throws JsonProcessingException {
        ObjectNode chain = read("""
                ---
                id: "chain-1"
                content:
                  elements: []
                  migrations: "[103, 108]"
                """);

        assertFalse(migration.supportsDocument(chain));
        // The pipeline only calls revert when supportsDocument is true, so the chain keeps its own 103.
    }

    // --- round trip ------------------------------------------------------------------------------------------------

    /**
     * The whole point of the strip: a legacy export writes the service file too, and re-importing it must run the
     * forward V103 migration again so operations regain their typed shape. Without the strip the version set is empty,
     * V103 never fires, and the operation imports untyped.
     */
    @Test
    void legacyExportReimportsToTheTypedApiShape(@TempDir Path directory) throws IOException {
        IntegrationSystem system = sampleSystem();

        ExportedIntegrationSystem exported = (ExportedIntegrationSystem) legacySerializer().serialize(system);

        JsonNode serviceMigrations = exported.getObjectNode().path("migrations");
        assertTrue(serviceMigrations.isTextual() && !serviceMigrations.asText().contains("103"),
                "the exported service file must carry migrations without 103: " + serviceMigrations);
        assertTrue(serviceMigrations.asText().contains("100"), "the other migration versions are preserved");

        File serviceFile = writeExport(directory, exported);

        IntegrationSystem reimported = deserializer().deserializeSystem(serviceFile);

        SystemModel model = reimported.getApiGroups().get(0).getSystemModels().get(0);
        Operation operation = model.getOperations().get(0);
        assertEquals("openapi", operation.getOperationKind(), "V103 re-ran on import and typed the operation");
        assertEquals("GET", operation.getMethod());
        assertEquals("/pets", operation.getPath());
        assertEquals("getPet", operation.getName());
        assertNotNull(operation.getSpecification(), "the operation specification survives the round trip");

        assertEquals(1, model.getSpecificationSources().size());
        assertEquals("openapi: 3.0.0", model.getSpecificationSources().get(0).getSource());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private ServiceSerializer legacySerializer() {
        FileMigrationService fileMigrationService = new FileMigrationService(
                mapper,
                versionsGetterService(),
                TestRevertMigrations.all(SPECIFICATION_SCHEMA));
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", true);
        return new ServiceSerializer(
                mapper,
                new IntegrationSystemDtoMapper(new ServiceTypeFiles(new ApplicationJsonSchemaProperties()), TestServiceMigrations.all()),
                new ApiGroupDtoMapper(GROUP_SCHEMA),
                new SystemModelDtoMapper(API_SCHEMA, new ApiOperationDtoMapper()),
                fileMigrationService,
                ExtractorTestParsers.extractor());
    }

    private ServiceDeserializer deserializer() {
        VersionsGetterService versionsGetterService = versionsGetterService();
        FileMigrationService fileMigrationService = new FileMigrationService(
                mapper, versionsGetterService, List.of());
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", false);

        ServiceDeserializer deserializer = new ServiceDeserializer(
        mapper,
        new IntegrationSystemReader(mapper, fileMigrationService, versionsGetterService, TestServiceMigrations.all()),
        new IntegrationSystemDtoMapper(new ServiceTypeFiles(new ApplicationJsonSchemaProperties()), TestServiceMigrations.all()),
        new ApiGroupDtoMapper(GROUP_SCHEMA),
        new SystemModelDtoMapper(API_SCHEMA, new ApiOperationDtoMapper()),
        ExtractorTestParsers.extractor(),
        new ServiceTypeFiles(new ApplicationJsonSchemaProperties()));
        return deserializer;
    }

    private static VersionsGetterService versionsGetterService() {
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        return new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));
    }

    private IntegrationSystem sampleSystem() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id("system-1")
                .name("Test service")
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .protocol(OperationProtocol.HTTP)
                .build();

        ApiGroup group = ApiGroup.builder()
                .id("group-1")
                .name("group")
                .system(system)
                .build();

        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .method("get")
                .path("/pets")
                .typed(new OpenapiOperation("Add a new pet", "/pets", "get", false))
                .specification(mapper.createObjectNode().put("type", "object"))
                .build();

        SpecificationSource source = SpecificationSource.builder()
                .id("src-1")
                .name("api.yaml")
                .isMainSource(true)
                .source("openapi: 3.0.0")
                .build();

        SystemModel model = SystemModel.builder()
                .id("spec-1")
                .name("Model 1")
                .version("1.0.0")
                .specificationType("openapi")
                .specificationVersion("3.0.0")
                .source(SystemModelSource.MANUAL)
                .apiGroup(group)
                .operations(new ArrayList<>(List.of(operation)))
                .specificationSources(new ArrayList<>(List.of(source)))
                .build();
        source.setSystemModel(model);
        group.setSystemModels(new ArrayList<>(List.of(model)));
        system.setApiGroups(new ArrayList<>(List.of(group)));
        return system;
    }

    private File writeExport(Path directory, ExportedIntegrationSystem exported) throws IOException {
        File serviceFile = write(directory, "service-" + exported.getId() + ".yaml",
                mapper.writeValueAsString(exported.getObjectNode()));
        for (ExportedApiGroup group : exported.getApiGroups()) {
            write(directory, "specGroup-" + group.getId() + ".yaml", mapper.writeValueAsString(group.getObjectNode()));
            for (ExportedSpecification specification : group.getSpecifications()) {
                write(directory, "specification-" + specification.getId() + ".yaml",
                        mapper.writeValueAsString(specification.getObjectNode()));
                for (ExportedSpecificationSource source : specification.getSpecificationSources()) {
                    if (source.getSource() != null) {
                        write(directory, source.getName(), source.getSource());
                    }
                }
            }
        }
        return serviceFile;
    }

    private static File write(Path directory, String relativePath, String content) throws IOException {
        Path path = directory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path.toFile();
    }

    private ObjectNode read(String yaml) throws JsonProcessingException {
        JsonNode node = mapper.readTree(yaml);
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) node;
    }
}
