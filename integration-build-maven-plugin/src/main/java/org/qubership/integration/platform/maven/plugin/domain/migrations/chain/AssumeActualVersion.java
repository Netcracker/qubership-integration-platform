package org.qubership.integration.platform.maven.plugin.domain.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.integration.platform.io.readers.migrations.ImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Order(Ordered.LOWEST_PRECEDENCE)
@Component
public class AssumeActualVersion implements VersionsGetterStrategy {
    private final Collection<ChainImportFileMigration> chainMigrations;
    private final Collection<ServiceImportFileMigration> serviceMigrations;

    @Autowired
    public AssumeActualVersion(
        Collection<ChainImportFileMigration> chainMigrations,
        Collection<ServiceImportFileMigration> serviceMigrations
    ) {
        this.chainMigrations = chainMigrations;
        this.serviceMigrations = serviceMigrations;
    }

    @Override
    public Optional<List<Integer>> getVersions(JsonNode document) {
        return Optional.of((isService(document) ? serviceMigrations : chainMigrations).stream()
            .map(ImportFileMigration::getVersion)
            .toList());
    }

    private boolean isService(JsonNode document) {
        return document.path("$schema").asText().contains("service");
    }
}
