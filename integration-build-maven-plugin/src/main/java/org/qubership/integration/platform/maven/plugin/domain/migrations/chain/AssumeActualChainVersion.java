package org.qubership.integration.platform.maven.plugin.domain.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class AssumeActualChainVersion implements VersionsGetterStrategy {
    private final Collection<ChainImportFileMigration> migrations;

    @Autowired
    public AssumeActualChainVersion(Collection<ChainImportFileMigration> migrations) {
        this.migrations = migrations;
    }

    @Override
    public Optional<List<Integer>> getVersions(JsonNode document) {
        return Optional.of(migrations.stream()
            .map(ChainImportFileMigration::getVersion)
            .toList());
    }
}
