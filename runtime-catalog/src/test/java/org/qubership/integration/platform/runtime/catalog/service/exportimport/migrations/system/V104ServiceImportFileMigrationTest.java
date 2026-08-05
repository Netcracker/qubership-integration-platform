package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V104ServiceImportFileMigrationTest {

    private static final String APP_NAME = "qip";

    private final V104ServiceImportFileMigration migration = new V104ServiceImportFileMigration();
    private final YAMLMapper mapper = new YAMLMapper();

    @Test
    void renamesTheInlineGroupListToApiGroups() throws JsonProcessingException {
        ObjectNode result = migrate("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  specificationGroups:
                  - id: "group-1"
                    name: "Group One"
                """);

        assertTrue(result.path("content").path("specificationGroups").isMissingNode(),
                "the legacy key is gone");
        JsonNode groups = result.path("content").path("apiGroups");
        assertTrue(groups.isArray() && groups.size() == 1);
        assertEquals("group-1", groups.path(0).path("id").asText());
    }

    @Test
    void leavesDocumentsWithoutAnInlineGroupListAlone() throws JsonProcessingException {
        // A group or a model document runs through the same migration list and has no such field.
        ObjectNode result = migrate("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                """);

        assertTrue(result.path("content").path("apiGroups").isMissingNode());
        assertEquals("system-1", result.path("content").path("parentId").asText());
    }

    /**
     * The migration owns {@code content} only. V101 has already relocated a legacy file's root fields into
     * {@code content} by the time this one runs, so a root-level list is not this migration's to rename.
     */
    @Test
    void leavesARootLevelGroupListAlone() throws JsonProcessingException {
        ObjectNode result = migrate("""
                ---
                id: "system-1"
                specificationGroups:
                - id: "group-1"
                """);

        assertEquals("group-1", result.path("specificationGroups").path(0).path("id").asText(),
                "the root-level list keeps its name and its contents");
        assertTrue(result.path("apiGroups").isMissingNode(), "no renamed key is invented at the root");
        assertTrue(result.path("content").isMissingNode(), "no content node is invented either");
    }

    /**
     * The silent-failure mode this migration exists to prevent: {@code IntegrationSystemContentDto} is
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so without this migration renaming the field first, an old
     * archive's {@code specificationGroups} key is dropped without error, the discriminator in
     * {@code ServiceDeserializer} sees an empty list, and the import silently takes the multi-file branch instead of
     * the legacy inline one — ending up with zero groups.
     */
    @Test
    void anOldInlineArchiveStillImportsItsGroupThroughTheRealDeserializer(@TempDir Path directory) throws IOException {
        File serviceFile = write(directory, "service-system-1.yaml", """
                ---
                id: "system-1"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103]"
                  specificationGroups:
                  - id: "group-1"
                    name: "Test group"
                    content:
                      synchronization: true
                      systemModels: []
                """);

        IntegrationSystem system = deserializer().deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size(),
                "the inline group must not be silently dropped after the field rename");
        assertEquals("group-1", system.getApiGroups().get(0).getId());
    }

    private ObjectNode migrate(String yaml) throws JsonProcessingException {
        JsonNode node = mapper.readTree(yaml);
        assertInstanceOf(ObjectNode.class, node);
        return migration.makeMigration((ObjectNode) node);
    }

    private ServiceDeserializer deserializer() {
        YAMLMapper yamlMapper = new MapperAutoConfiguration().yamlExportImportMapper();
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        VersionsGetterService versionsGetterService = new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));

        FileMigrationService fileMigrationService =
                new FileMigrationService(yamlMapper, versionsGetterService, List.of());
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", false);

        List<ServiceImportFileMigration> migrations = TestServiceMigrations.all();

        ServiceDeserializer built = new ServiceDeserializer(
                yamlMapper,
                versionsGetterService,
                new IntegrationSystemDtoMapper(URI.create("http://qubership.org/schemas/product/qip/service"), migrations),
                new ApiGroupDtoMapper(URI.create("http://qubership.org/schemas/product/qip/api-group")),
                new SystemModelDtoMapper(
                        URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml"),
                        new ApiOperationDtoMapper()),
                fileMigrationService,
                migrations,
                ExtractorTestParsers.extractor(),
                new ServiceTypeFiles(new ApplicationJsonSchemaProperties()));
        ReflectionTestUtils.setField(built, "appName", APP_NAME);
        return built;
    }

    private static File write(Path directory, String relativePath, String content) throws IOException {
        Path path = directory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path.toFile();
    }
}
