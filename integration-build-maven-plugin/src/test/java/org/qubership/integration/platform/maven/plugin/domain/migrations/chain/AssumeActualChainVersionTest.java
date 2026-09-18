package org.qubership.integration.platform.maven.plugin.domain.migrations.chain;

import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssumeActualChainVersionTest {

    @Test
    void reportsEveryRegisteredMigrationVersionWhateverTheDocumentSays() {
        AssumeActualChainVersion strategy =
            new AssumeActualChainVersion(List.of(migration(1), migration(2), migration(5)));

        assertEquals(List.of(1, 2, 5), strategy.getVersions(NullNode.getInstance()).orElseThrow());
    }

    @Test
    void reportsNoVersionsWhenNoMigrationIsRegistered() {
        AssumeActualChainVersion strategy = new AssumeActualChainVersion(List.of());

        assertEquals(List.of(), strategy.getVersions(NullNode.getInstance()).orElseThrow());
    }

    private static ChainImportFileMigration migration(int version) {
        ChainImportFileMigration migration = mock(ChainImportFileMigration.class);
        when(migration.getVersion()).thenReturn(version);
        return migration;
    }
}
