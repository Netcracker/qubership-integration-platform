/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterStrategy;
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
public class MigrationFieldStrategy implements VersionsGetterStrategy {
    @Override
    public Optional<List<Integer>> getVersions(JsonNode document) {
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
