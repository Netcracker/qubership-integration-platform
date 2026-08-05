package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.MigrationException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.VersionFieldStrategy;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V105ServiceImportFileMigrationTest {

    private static final String CLAIMED_BY_A_POST553_EXPORT = "[100, 101, 102, 103, 104, 105]";

    private final V105ServiceImportFileMigration migration = new V105ServiceImportFileMigration();
    private final YAMLMapper mapper = new YAMLMapper();

    @Test
    void claimsVersion105AndSaysItIsSafeToReRun() {
        assertEquals(105, migration.getVersion());
        assertTrue(migration.isIdempotent(),
                "a no-op must be idempotent, or the rollout path claims 105 as applied and never runs it");
    }

    /**
     * The migration is a documented no-op: {@code ServiceDeserializer} resolves the type from the file name for every
     * document, so there is nothing to rewrite here. A test that pins that is what keeps a later reader from
     * "fixing" the class by giving it something to do.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsRunThroughTheServiceMigrationList")
    void returnsTheDocumentUnchanged(String kind, String yaml) throws JsonProcessingException {
        ObjectNode node = read(yaml);
        ObjectNode before = node.deepCopy();

        ObjectNode result = migration.makeMigration(node);

        assertEquals(before, result, kind + " must come out of the migration exactly as it went in");
        assertEquals(before, node, "the node handed to the migration must not be mutated either");
    }

    static Stream<Arguments> documentsRunThroughTheServiceMigrationList() {
        return Stream.of(
                Arguments.of("a plain service", """
                        ---
                        id: "system-1"
                        name: "Test service"
                        content:
                          protocol: "HTTP"
                          migrations: "[100, 101, 102, 103, 104]"
                          activeEnvironmentId: "env-1"
                        """),
                // Context and MCP services run through the same list and are stamped from it, so they reach this
                // migration too. ContextServiceDtoMapper writes their migrations field from the service migrations.
                Arguments.of("a context service", """
                        ---
                        id: "context-1"
                        name: "Test context service"
                        content:
                          description: "Context"
                          migrations: "[100, 101, 102, 103, 104]"
                        """),
                Arguments.of("an MCP service", """
                        ---
                        id: "mcp-1"
                        name: "Test MCP service"
                        content:
                          migrations: "[100, 101, 102, 103, 104]"
                          tools: []
                        """));
    }

    /**
     * The reason the class exists. A QIP that predates #553 has no migration 105 in its registry, so a document
     * claiming it is refused instead of being imported without a type.
     */
    @Test
    void aDocumentClaiming105IsRefusedByARegistryThatLacksIt() {
        MigrationException exception = assertThrows(MigrationException.class,
                () -> migrate(documentClaiming(CLAIMED_BY_A_POST553_EXPORT), migrationsBefore105()));

        assertEquals("Unable to import an entity exported from a newer version", exception.getMessage());
        assertEquals("system-1", exception.getEntityId());
        assertEquals("Test service", exception.getEntityName(),
                "the refusal names the service, which is what the import result reports");
    }

    @Test
    void theSameDocumentPassesOnceTheMigrationIsRegistered() throws JsonProcessingException {
        ObjectNode document = documentClaiming(CLAIMED_BY_A_POST553_EXPORT);
        ObjectNode before = document.deepCopy();

        ObjectNode result = assertDoesNotThrow(() -> migrate(document, TestServiceMigrations.all()));

        assertEquals(before, result, "105 is already claimed, so no migration runs and nothing changes");
    }

    /** A pre-#553 document claims up to 104, and this QIP migrates it forward without complaint. */
    @Test
    void aPre553DocumentIsStillAccepted() {
        ObjectNode result = assertDoesNotThrow(
                () -> migrate(documentClaiming("[100, 101, 102, 103, 104]"), TestServiceMigrations.all()));

        assertEquals("system-1", result.path("id").asText());
    }

    private static List<ServiceImportFileMigration> migrationsBefore105() {
        return TestServiceMigrations.all().stream()
                .filter(candidate -> candidate.getVersion() != 105)
                .toList();
    }

    private ObjectNode migrate(ObjectNode document, Collection<ServiceImportFileMigration> migrations)
            throws MigrationException {
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        VersionsGetterService versionsGetterService = new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));
        FileMigrationService fileMigrationService =
                new FileMigrationService(mapper, versionsGetterService, List.of());

        return fileMigrationService.migrate(
                document,
                migrations.stream().map(ImportFileMigration.class::cast).toList());
    }

    private ObjectNode documentClaiming(String versions) throws JsonProcessingException {
        return read("""
                ---
                id: "system-1"
                name: "Test service"
                content:
                  protocol: "HTTP"
                  migrations: "%s"
                """.formatted(versions));
    }

    private ObjectNode read(String yaml) throws JsonProcessingException {
        JsonNode node = mapper.readTree(yaml);
        return assertInstanceOf(ObjectNode.class, node);
    }
}
