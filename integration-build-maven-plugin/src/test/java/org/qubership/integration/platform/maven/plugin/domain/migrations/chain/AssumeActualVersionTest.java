package org.qubership.integration.platform.maven.plugin.domain.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssumeActualVersionTest {

    private static final String CHAIN_SCHEMA = "http://qubership.org/schemas/product/qip/chain";
    private static final String SERVICE_SCHEMA = "http://qubership.org/schemas/product/qip/service";

    private final AssumeActualVersion strategy = new AssumeActualVersion(
        List.of(chainMigration(1), chainMigration(2), chainMigration(5)),
        List.of(serviceMigration(3), serviceMigration(4)));

    @Test
    void reportsEveryRegisteredChainMigrationVersionForAChainDocument() {
        assertEquals(List.of(1, 2, 5), strategy.getVersions(document(CHAIN_SCHEMA)).orElseThrow());
    }

    @Test
    void reportsEveryRegisteredServiceMigrationVersionForAServiceDocument() {
        assertEquals(List.of(3, 4), strategy.getVersions(document(SERVICE_SCHEMA)).orElseThrow());
    }

    @Test
    void readsADocumentThatNamesNoSchemaAsAChain() {
        assertEquals(List.of(1, 2, 5), strategy.getVersions(JsonNodeFactory.instance.objectNode()).orElseThrow());
    }

    @Test
    void reportsNoVersionsWhenNoMigrationIsRegistered() {
        AssumeActualVersion emptyStrategy = new AssumeActualVersion(List.of(), List.of());

        assertEquals(List.of(), emptyStrategy.getVersions(document(CHAIN_SCHEMA)).orElseThrow());
        assertEquals(List.of(), emptyStrategy.getVersions(document(SERVICE_SCHEMA)).orElseThrow());
    }

    private static JsonNode document(String schema) {
        return JsonNodeFactory.instance.objectNode().put("$schema", schema);
    }

    private static ChainImportFileMigration chainMigration(int version) {
        ChainImportFileMigration migration = mock(ChainImportFileMigration.class);
        when(migration.getVersion()).thenReturn(version);
        return migration;
    }

    private static ServiceImportFileMigration serviceMigration(int version) {
        ServiceImportFileMigration migration = mock(ServiceImportFileMigration.class);
        when(migration.getVersion()).thenReturn(version);
        return migration;
    }
}
