package org.qubership.integration.platform.io.readers.migrations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.VersionFieldStrategy;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileMigrationServiceTest {

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final FileMigrationService service = new FileMigrationService(
            yamlMapper,
            new VersionsGetterService(List.of(
                    new MigrationFieldInContentStrategy(new MigrationFieldStrategy()),
                    new MigrationFieldStrategy(),
                    new VersionFieldStrategy())),
            List.of());
    private final List<Integer> applied = new ArrayList<>();
    private final List<ImportFileMigration> migrations = List.of(migration(100), migration(101), migration(102));

    @DisplayName("A document without migration metadata is read as current, and no migration runs")
    @Test
    void appliesNoMigrationWithoutMetadata() throws Exception {
        service.migrate(document("""
                id: c-1
                name: Created
                content: {}
                """), migrations);

        assertEquals(List.of(), applied);
    }

    @DisplayName("An empty migrations list means no migration has run yet, so every one runs")
    @Test
    void appliesEveryMigrationForAnEmptyList() throws Exception {
        service.migrate(document("""
                id: c-1
                name: Created
                content:
                  migrations: "[]"
                """), migrations);

        assertEquals(List.of(100, 101, 102), applied);
    }

    private ObjectNode document(String yaml) throws Exception {
        return (ObjectNode) yamlMapper.readTree(yaml);
    }

    private ImportFileMigration migration(int version) {
        return new ImportFileMigration() {
            @Override
            public int getVersion() {
                return version;
            }

            @Override
            public ObjectNode makeMigration(ObjectNode fileNode) {
                applied.add(version);
                return fileNode;
            }
        };
    }
}
