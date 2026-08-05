package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

class V105RevertMigrationTest {

    private static final String APP_NAME = "qip";
    private static final String SERVICE_SCHEMA_URI = "http://qubership.org/schemas/product/qip/service.schema.yaml";
    private static final URI SERVICE_SCHEMA = URI.create(SERVICE_SCHEMA_URI);
    private static final URI GROUP_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification-group.schema.yaml");
    private static final URI SPECIFICATION_SCHEMA =
            URI.create("http://qubership.org/schemas/product/qip/specification.schema.yaml");
    private static final URI API_SCHEMA = URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml");
    private static final String CLAIMED_BY_A_POST553_EXPORT = "[100, 101, 102, 103, 104, 105]";

    private final ApplicationJsonSchemaProperties schemas = new ApplicationJsonSchemaProperties();
    private final ServiceDocumentMatcher matcher = new ServiceDocumentMatcher(schemas);
    private final V105RevertMigration migration =
            new V105RevertMigration(matcher, new ServiceTypeFiles(schemas), schemas);
    private final YAMLMapper mapper = new MapperAutoConfiguration().yamlExportImportMapper();

    @Test
    void claimsVersion105() {
        assertEquals(105, migration.getVersion());
    }

    // --- the narrow write ------------------------------------------------------------------------------------------

    /**
     * A post-#553 export states the type in the file name and the {@code $schema} and carries no type field. The
     * legacy format has neither a type-bearing name nor a per-type schema, so the field is the only place the type
     * can go, and without it the reverted file cannot be imported at all.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml, EXTERNAL",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml, INTERNAL",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml, IMPLEMENTED"})
    void writesTheTypeBackAndRestoresThePlainServiceSchema(String schemaUri, String type)
            throws JsonProcessingException {
        ObjectNode result = migration.revert(perTypeService(schemaUri));

        assertEquals(type, result.path("content").path("integrationSystemType").asText(),
                "the legacy format states the type in the document, not in the file name");
        assertEquals(SERVICE_SCHEMA_URI, result.path("$schema").asText(),
                "the reverted document is a plain service document again");
        assertEquals("[100, 101, 102, 103, 104]", result.path("content").path("migrations").asText(),
                "105 is stripped so the forward migration re-runs on import");
    }

    /**
     * Context and MCP services are stamped 105 from the same migration list ({@code ContextServiceDtoMapper}), so the
     * strip has to reach them. Nothing else about them may change: neither carries a service type, and a type stamped
     * onto a context service would be read back as one on import.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml"})
    void stripsTheVersionFromASharedDocumentWithoutStampingATypeOnIt(String schemaUri)
            throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "service-1"
                name: "Test service"
                $schema: "%s"
                content:
                  description: "A service that shares the service migration list"
                  migrations: "%s"
                """.formatted(schemaUri, CLAIMED_BY_A_POST553_EXPORT)));

        assertEquals("[100, 101, 102, 103, 104]", result.path("content").path("migrations").asText(),
                "a kept 105 claim makes the legacy export unimportable by an older QIP");
        assertFalse(result.path("content").has("integrationSystemType"), "these documents have no service type");
        assertEquals(schemaUri, result.path("$schema").asText(), "their own $schema is left alone");
    }

    /** A pre-#553 document already carries the field and the plain schema; the revert has nothing to write. */
    @Test
    void leavesAPre553ServiceDocumentAloneApartFromTheStrip() throws JsonProcessingException {
        ObjectNode result = migration.revert(read("""
                ---
                id: "system-1"
                name: "Test service"
                $schema: "%s"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103, 104]"
                """.formatted(SERVICE_SCHEMA_URI)));

        assertEquals("EXTERNAL", result.path("content").path("integrationSystemType").asText());
        assertEquals(SERVICE_SCHEMA_URI, result.path("$schema").asText());
        assertEquals("[100, 101, 102, 103, 104]", result.path("content").path("migrations").asText(),
                "there is no 105 to strip");
    }

    @Test
    void leavesTheDocumentItWasHandedUnmutated() throws JsonProcessingException {
        ObjectNode document =
                perTypeService("http://qubership.org/schemas/product/qip/external-service.schema.yaml");
        ObjectNode before = document.deepCopy();

        migration.revert(document);

        assertEquals(before, document, "the revert chain passes each result on; mutating the input aliases them");
    }

    // --- the broad match -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml",
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml"})
    void supportsEveryDocumentStampedFromTheServiceMigrationList(String schemaUri) throws JsonProcessingException {
        assertTrue(migration.supportsDocument(perTypeService(schemaUri)));
    }

    @Test
    void rejectsAChainDocument() throws JsonProcessingException {
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
     * V104 and V103 are gated on the same matcher and run after this migration, on its result. Restoring the plain
     * {@code $schema} must therefore leave the document matchable, or the legacy export silently loses the
     * {@code apiGroups} rename V104 reverts.
     */
    @Test
    void leavesTheDocumentMatchableSoV104AndV103StillApply() throws JsonProcessingException {
        ObjectNode reverted =
                migration.revert(perTypeService("http://qubership.org/schemas/product/qip/internal-service.schema.yaml"));

        assertTrue(matcher.matches(reverted), "the reverted document is still a service document");
        assertTrue(new V104RevertMigration(matcher).supportsDocument(reverted), "so V104 runs on it");
    }

    // --- the production revert chain -------------------------------------------------------------------------------

    /**
     * The whole chain over a post-#553 export carrying api groups: V105 restores the type and the plain schema, V104
     * renames {@code apiGroups} back, V103 drops the derived group list, V101 flattens content onto the root. The api
     * groups are what make this worth running end to end — a chain that stops matching after V105 loses the rename
     * without failing anything.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml, EXTERNAL",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml, INTERNAL",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml, IMPLEMENTED"})
    void theProductionChainRevertsAPost553ServiceDocument(String schemaUri, String type)
            throws JsonProcessingException {
        ObjectNode result = productionRevertPipeline().revertMigrationIfNeeded(serviceWithApiGroups(schemaUri));

        assertTrue(result.path("content").isMissingNode(), "V101 flattens content onto the root");
        assertEquals(type, result.path("integrationSystemType").asText(), "V105 wrote the type back");
        assertTrue(result.path("apiGroups").isMissingNode(), "the renamed key must not survive a legacy export");
        assertEquals("group-1", result.path("specificationGroups").path(0).path("id").asText(),
                "V104 still reverts the rename on a document exported with a per-type $schema");
        assertEquals("[100, 102]", result.path("migrations").asText(),
                "101, 103, 104, and 105 are all stripped so every forward migration re-runs on import");
        assertFalse(result.has("$schema"), "V101 drops every root field the legacy format never carried");
    }

    // --- round trip ------------------------------------------------------------------------------------------------

    /**
     * The point of the type write: a legacy file states its type nowhere but the document, and
     * {@code ServiceDeserializer} refuses a service that states it nowhere at all. Export and import halves are wired
     * together here, so dropping the write fails this test instead of surfacing as an unimportable archive.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml, EXTERNAL",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml, INTERNAL",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml, IMPLEMENTED"})
    void aLegacyExportedServiceReimportsWithItsType(String schemaUri, String type, @TempDir Path directory)
            throws IOException {
        ObjectNode exported = productionRevertPipeline().revertMigrationIfNeeded(serviceWithApiGroups(schemaUri));
        File serviceFile = write(directory, "service-system-1.yaml", mapper.writeValueAsString(exported));

        IntegrationSystem reimported = deserializer().deserializeSystem(serviceFile);

        assertEquals(type, reimported.getIntegrationSystemType().name(),
                "the legacy file name carries no type, so the restored field is the only source");
        assertEquals(1, reimported.getApiGroups().size(), "the inline group survives the round trip too");
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private ObjectNode perTypeService(String schemaUri) throws JsonProcessingException {
        return read("""
                ---
                id: "system-1"
                name: "Test service"
                $schema: "%s"
                content:
                  protocol: "HTTP"
                  migrations: "%s"
                """.formatted(schemaUri, CLAIMED_BY_A_POST553_EXPORT));
    }

    private ObjectNode serviceWithApiGroups(String schemaUri) throws JsonProcessingException {
        return read("""
                ---
                id: "system-1"
                name: "Test service"
                $schema: "%s"
                content:
                  protocol: "HTTP"
                  migrations: "%s"
                  apiGroups:
                  - id: "group-1"
                    name: "group"
                    content:
                      synchronization: false
                      parentId: "system-1"
                """.formatted(schemaUri, CLAIMED_BY_A_POST553_EXPORT));
    }

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
                new IntegrationSystemDtoMapper(new ServiceTypeFiles(schemas), TestServiceMigrations.all()),
                new ApiGroupDtoMapper(GROUP_SCHEMA),
                new SystemModelDtoMapper(API_SCHEMA, new ApiOperationDtoMapper()),
                fileMigrationService,
                TestServiceMigrations.all(),
                ExtractorTestParsers.extractor(),
                new ServiceTypeFiles(schemas));
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
