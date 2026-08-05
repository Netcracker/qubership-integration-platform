package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V104RevertMigrationTest {

    private static final String APP_NAME = "qip";
    private static final URI SERVICE_SCHEMA = URI.create("http://qubership.org/schemas/product/qip/service.schema.yaml");
    private static final URI GROUP_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification-group.schema.yaml");
    private static final URI SPECIFICATION_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification.schema.yaml");
    private static final URI API_SCHEMA = URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml");

    private final V104RevertMigration migration = new V104RevertMigration(TestRevertMigrations.matcher());
    private final YAMLMapper mapper = new MapperAutoConfiguration().yamlExportImportMapper();

    // --- service node field rename ---------------------------------------------------------------------------------

    @Test
    void renamesApiGroupsBackToSpecificationGroupsOnTheServiceDocument() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  apiGroups:
                  - id: "group-1"
                """));

        assertTrue(result.path("content").path("apiGroups").isMissingNode(), "the new key is gone");
        JsonNode groups = result.path("content").path("specificationGroups");
        assertTrue(groups.isArray() && groups.size() == 1);
        assertEquals("group-1", groups.path(0).path("id").asText());
    }

    @Test
    void stripsVersion104FromTheServiceMigrations() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103, 104]"
                """));

        assertEquals("[100, 101, 102, 103]", result.path("content").path("migrations").asText(),
                "104 is stripped so the forward migration re-runs on import");
    }

    // --- supportsDocument ------------------------------------------------------------------------------------------

    @Test
    void supportsOnlyTheServiceDocument() throws JsonProcessingException {
        ObjectNode service = read("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                """);
        ObjectNode group = read("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                  apis:
                  - "api-1"
                """);
        ObjectNode chain = read("""
                ---
                id: "chain-1"
                content:
                  elements: []
                  migrations: "[103, 104, 108]"
                """);
        ObjectNode apiModel = read("""
                ---
                id: "spec-1"
                content:
                  specificationType: "openapi"
                  operations: []
                """);

        assertTrue(migration.supportsDocument(service), "a service document has its group list renamed back");
        assertFalse(migration.supportsDocument(group), "the group document carries nothing this migration owns");
        assertFalse(migration.supportsDocument(chain), "a chain owns its own migrations list and has neither field");
        assertFalse(migration.supportsDocument(apiModel), "an api-model document has neither field either");
    }

    /**
     * {@code IntegrationSystemContentDto} is {@code @JsonInclude(NON_EMPTY)} and the export mapper never fills
     * {@code apiGroups}, so a service with no type, no protocol, and no environments exports a content holding
     * nothing but {@code migrations}. The field-shape discriminator missed it; {@code $schema} does not.
     */
    @Test
    void supportsAServiceDocumentWhoseContentIsOnlyTheMigrationsField() throws JsonProcessingException {
        ObjectNode bareService = read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml"
                content:
                  migrations: "[100, 101, 102, 103, 104]"
                """);

        assertTrue(migration.supportsDocument(bareService),
                "a service claiming 104 must have it stripped whatever else its content holds");
    }

    @Test
    void supportsTheContextAndMcpServiceDocumentsThatShareTheServiceMigrationList() throws JsonProcessingException {
        ObjectNode contextService = read("""
                ---
                id: "context-1"
                $schema: "http://qubership.org/schemas/product/qip/context-service.schema.yaml"
                content:
                  migrations: "[100, 101, 102, 103, 104]"
                """);
        ObjectNode mcpService = read("""
                ---
                id: "mcp-1"
                $schema: "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml"
                content:
                  identifier: "mcp"
                  migrations: "[100, 101, 102, 103, 104]"
                """);

        assertTrue(migration.supportsDocument(contextService), "a context service is stamped with the same list");
        assertTrue(migration.supportsDocument(mcpService), "so is an MCP service");
    }

    @Test
    void rejectsAChainDocumentThatCarriesItsOwnVersion104() throws JsonProcessingException {
        ObjectNode chain = read("""
                ---
                id: "chain-1"
                $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml"
                content:
                  elements: []
                  migrations: "[103, 104, 108]"
                """);

        assertFalse(migration.supportsDocument(chain), "the chain sequence numbers its migrations independently");
    }

    /**
     * The whole scenario end to end, off the real export mapper: a service built with nothing but an id and a name.
     * Its legacy export must not claim 104, or a pre-rename QIP rejects the whole archive as coming from a newer
     * version.
     */
    @Test
    void aBareServiceExportsWithoutClaimingVersion104() {
        IntegrationSystem system = IntegrationSystem.builder().id("system-1").name("Test service").build();
        IntegrationSystemDto dto =
                new IntegrationSystemDtoMapper(SERVICE_SCHEMA, TestServiceMigrations.all()).toExternalEntity(system);

        ObjectNode exported = productionRevertPipeline().revertMigrationIfNeeded(mapper.valueToTree(dto));

        assertFalse(exported.path("migrations").asText().contains("104"),
                "104 must be stripped so the forward migration re-runs on import");
    }

    // --- the production revert chain -------------------------------------------------------------------------------

    /**
     * The revert list is sorted descending, so V104 runs before V103 and V101 runs last. V103's own service
     * discriminator reads {@code specificationGroups}, which only holds once V104 has already renamed the field back.
     */
    @Test
    void theProductionChainRevertsAServiceDocumentInTheRightOrder() throws JsonProcessingException {
        ObjectNode result = productionRevertPipeline().revertMigrationIfNeeded(read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103, 104]"
                  apiGroups:
                  - id: "group-1"
                """));

        assertTrue(result.path("content").isMissingNode(), "V101 flattens content onto the root");
        assertTrue(result.path("apiGroups").isMissingNode(), "the renamed key must not survive a legacy export");
        assertEquals("group-1", result.path("specificationGroups").path(0).path("id").asText());
        assertEquals("[100, 102]", result.path("migrations").asText(),
                "101, 103, and 104 are all stripped so every forward migration re-runs on import");
    }

    /**
     * A legacy artifact carries no {@code $schema} on any document: V101 rebuilds each node from {@code id},
     * {@code name}, and the children of {@code content}, so any root field a later-numbered revert wrote is dropped.
     * Pinning that here is what keeps a would-be {@code $schema} restamp from being written as dead code again.
     */
    @Test
    void theProductionChainLeavesNoSchemaOnAGroupDocument() throws JsonProcessingException {
        ObjectNode result = productionRevertPipeline().revertMigrationIfNeeded(read("""
                ---
                id: "group-1"
                $schema: "http://qubership.org/schemas/product/qip/api-group.schema.yaml"
                name: "group"
                content:
                  synchronization: false
                  parentId: "system-1"
                  apis:
                  - "api-1"
                """));

        assertFalse(result.has("$schema"), "V101 drops every root field the legacy format never carried");
        assertFalse(result.has("apis"), "V103 removes the derived child list");
        assertEquals("system-1", result.path("parentId").asText(), "the group content is flattened onto the root");
        assertEquals("group-1", result.path("id").asText());
    }

    // --- round trip ------------------------------------------------------------------------------------------------

    /**
     * The whole point of the strip: the service file a legacy export writes has to import back through the real
     * deserializer, with the inline group found again. Export and import halves are wired together here, so a wrong
     * rename direction or a missing version strip fails the test.
     */
    @Test
    void aLegacyExportedServiceReimportsItsInlineGroup(@TempDir Path directory) throws IOException {
        ObjectNode exported = productionRevertPipeline().revertMigrationIfNeeded(read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103, 104]"
                  apiGroups:
                  - id: "group-1"
                    name: "group"
                    content:
                      synchronization: false
                      parentId: "system-1"
                """));

        File serviceFile = write(directory, "service-system-1.yaml", mapper.writeValueAsString(exported));

        IntegrationSystem reimported = deserializer().deserializeSystem(serviceFile);

        assertEquals(1, reimported.getApiGroups().size(),
                "the inline group must survive the legacy export and come back on import");
        assertEquals("group-1", reimported.getApiGroups().get(0).getId());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    // The bean set FileMigrationService receives in production; the service sorts it into descending order itself.
    private FileMigrationService productionRevertPipeline() {
        FileMigrationService service = new FileMigrationService(
                mapper, versionsGetterService(), TestRevertMigrations.all(SPECIFICATION_SCHEMA));
        ReflectionTestUtils.setField(service, "isLegacyExport", true);
        return service;
    }

    private ServiceDeserializer deserializer() {
        VersionsGetterService versionsGetterService = versionsGetterService();
        FileMigrationService fileMigrationService = new FileMigrationService(mapper, versionsGetterService, List.of());
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", false);

        ServiceDeserializer deserializer = new ServiceDeserializer(
                mapper,
                versionsGetterService,
                new IntegrationSystemDtoMapper(SERVICE_SCHEMA, TestServiceMigrations.all()),
                new ApiGroupDtoMapper(GROUP_SCHEMA),
                new SystemModelDtoMapper(API_SCHEMA, new ApiOperationDtoMapper()),
                fileMigrationService,
                TestServiceMigrations.all(),
                ExtractorTestParsers.extractor(),
                new ServiceTypeFiles(new ApplicationJsonSchemaProperties()));
        ReflectionTestUtils.setField(deserializer, "appName", APP_NAME);
        return deserializer;
    }

    private static VersionsGetterService versionsGetterService() {
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        return new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));
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
