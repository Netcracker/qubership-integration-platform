package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.strategies;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.ChainFileVersionsGetterStrategy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Component
public class MigrationFieldStrategy implements ChainFileVersionsGetterStrategy {
    @Override
    public Optional<List<Integer>> getVersions(ObjectNode document) {
        return document.has(IMPORT_MIGRATIONS_FIELD)
                ? Optional.of(
                        nonNull(document.get(IMPORT_MIGRATIONS_FIELD))
                                ? Arrays.stream(
                                        document.get(IMPORT_MIGRATIONS_FIELD)
                                                .asText()
                                                .replaceAll("[\\[\\]]", "")
                                                .split(","))
                                .map(String::trim)
                                .filter(StringUtils::isNotEmpty)
                                .map(Integer::parseInt)
                                .toList()
                                : new ArrayList<>())
                : Optional.empty();
    }
}
