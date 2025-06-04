package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.strategies;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.ChainFileVersionsGetterStrategy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_VERSION_FIELD_OLD;

@Order(Ordered.LOWEST_PRECEDENCE)
@Component
public class VersionFieldStrategy implements ChainFileVersionsGetterStrategy {
    @Override
    public Optional<List<Integer>> getVersions(ObjectNode document) {
        return document.has(IMPORT_VERSION_FIELD_OLD)
                ? Optional.of(
                        IntStream.rangeClosed(1, document.get(IMPORT_VERSION_FIELD_OLD).asInt())
                                .boxed()
                                .toList())
                : Optional.empty();
    }
}
