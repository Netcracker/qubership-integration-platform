package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.MigrationException;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V105ServiceImportFileMigrationTest {

    private static final String CLAIMED_BY_A_CURRENT_EXPORT = "[100, 101, 102, 103, 104, 105]";
    private static final String CLAIMED_BY_AN_OLDER_EXPORT = "[100, 101, 102, 103, 104]";

    private final V105ServiceImportFileMigration migration = new V105ServiceImportFileMigration();

    @Test
    @DisplayName("the migration claims version 105 and says it is safe to re-run")
    void claimsVersion105AndIdempotence() {
        assertEquals(105, migration.getVersion());
        assertTrue(migration.isIdempotent(),
                "a no-op must be idempotent, or the rollout path claims 105 as applied and never runs it");
    }

    /**
     * The migration is a documented no-op, and this test is what keeps a later reader from "fixing" the class by
     * giving it something to do: {@code ServiceDeserializer} resolves the type from the file name for every document.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsRunThroughTheServiceMigrationList")
    @DisplayName("the document comes back unchanged")
    void returnsTheDocumentUnchanged(String kind, String yaml) {
        ObjectNode node = GoldenServiceCorpus.read(yaml);
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
     * The reason the class exists. A QIP without migration 105 in its registry refuses a document claiming it instead
     * of importing the service without a type.
     */
    @Test
    @DisplayName("a document claiming 105 is refused by a registry that lacks it")
    void documentClaiming105IsRefusedByAnOlderRegistry() {
        MigrationException exception = assertThrows(MigrationException.class,
                () -> migrate(documentClaiming(CLAIMED_BY_A_CURRENT_EXPORT), migrationsBefore105()));

        assertEquals("Unable to import an entity exported from a newer version", exception.getMessage());
        assertEquals("system-1", exception.getEntityId());
        assertEquals("Test service", exception.getEntityName(),
                "the refusal names the service, which is what the import result reports");
    }

    @Test
    @DisplayName("the same document passes once the migration is registered")
    void documentPassesOnceRegistered() {
        ObjectNode document = documentClaiming(CLAIMED_BY_A_CURRENT_EXPORT);
        ObjectNode before = document.deepCopy();

        ObjectNode result = assertDoesNotThrow(() -> migrate(document, TestServiceMigrations.all()));

        assertEquals(before, result, "105 is already claimed, so no migration runs and nothing changes");
    }

    @Test
    @DisplayName("an older document is still migrated forward without complaint")
    void olderDocumentIsStillAccepted() {
        ObjectNode result = assertDoesNotThrow(
                () -> migrate(documentClaiming(CLAIMED_BY_AN_OLDER_EXPORT), TestServiceMigrations.all()));

        assertEquals("system-1", result.path("id").asText());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static List<ServiceImportFileMigration> migrationsBefore105() {
        return TestServiceMigrations.all().stream()
                .filter(candidate -> candidate.getVersion() != 105)
                .toList();
    }

    private static ObjectNode migrate(ObjectNode document, Collection<ServiceImportFileMigration> migrations)
            throws MigrationException {
        return GoldenServiceCorpus.forwardMigrationService().migrate(
                document,
                migrations.stream().map(ImportFileMigration.class::cast).toList());
    }

    private static ObjectNode documentClaiming(String versions) {
        return GoldenServiceCorpus.read("""
                ---
                id: "system-1"
                name: "Test service"
                content:
                  protocol: "HTTP"
                  migrations: "%s"
                """.formatted(versions));
    }
}
