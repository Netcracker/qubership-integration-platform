package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.strategies;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.ChainFileVersionsGetterStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_CONTENT_FIELD;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class MigrationFieldInContentStrategy implements ChainFileVersionsGetterStrategy {
    private final MigrationFieldStrategy migrationFieldStrategy;

    @Autowired
    public MigrationFieldInContentStrategy(MigrationFieldStrategy migrationFieldStrategy) {
        this.migrationFieldStrategy = migrationFieldStrategy;
    }

    @Override
    public Optional<List<Integer>> getVersions(ObjectNode document) {
        return document.has(IMPORT_CONTENT_FIELD)
                && nonNull(document.get(IMPORT_CONTENT_FIELD))
                && document.get(IMPORT_CONTENT_FIELD).isObject()
                        ? migrationFieldStrategy.getVersions((ObjectNode) document.get(IMPORT_CONTENT_FIELD))
                        : Optional.empty();
    }
}
