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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.RevertMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class FileMigrationService {

    @Value("${qip.export.legacy-format}")
    private boolean isLegacyExport;

    private final YAMLMapper yamlMapper;
    private final VersionsGetterService versionsGetterService;
    private final List<RevertMigration> revertMigration;

    @Autowired
    public FileMigrationService(YAMLMapper yamlMapper,
                                VersionsGetterService versionsGetterService,
                                List<RevertMigration> revertMigration) {
        this.yamlMapper = yamlMapper;
        this.versionsGetterService = versionsGetterService;
        this.revertMigration = revertMigration;
    }

    Collection<Integer> getMigrationVersions(Collection<ImportFileMigration> migrations) {
        return migrations.stream().map(ImportFileMigration::getVersion).toList();
    }

    public String migrate(String data, Collection<ImportFileMigration> migrations) throws MigrationException {
        JsonNode node;
        try {
            node = yamlMapper.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new MigrationException("Failed to parse document to import", exception);
        }
        if (!node.isObject()) {
            throw new MigrationException("Root node of document to import is not an object");
        }

        ObjectNode documentNode = (ObjectNode) node;
        try {
            return yamlMapper.writeValueAsString(migrate((ObjectNode) node, migrations));
        } catch (JsonProcessingException exception) {
            throw new MigrationException("Failed to serialize migrated document",
                    exception, getId(documentNode), getName(documentNode));
        }
    }

    public ObjectNode migrate(ObjectNode documentNode, Collection<ImportFileMigration> migrations) throws MigrationException {
        String id = getId(documentNode);
        String name = getName(documentNode);

        Collection<Integer> migrationVersions = getMigrationVersions(migrations);
        log.trace("Actual versions: {}", migrationVersions);

        Collection<Integer> documentVersions = getDocumentVersions(documentNode);
        log.trace("Document versions: {}", documentVersions);

        Collection<Integer> nonexistentVersions = subtract(documentVersions, migrationVersions);
        if (!nonexistentVersions.isEmpty()) {
            log.error("Unable to import an entity {} ({}) exported from newer version: nonexistent migrations {} are present",
                    name, id, nonexistentVersions);
            throw new MigrationException("Unable to import an entity exported from a newer version", id, name);
        }

        Collection<Integer> versionsToMigrate = subtract(migrationVersions, documentVersions);
        log.trace("versions to migrate = {}", versionsToMigrate);

        Iterator<ImportFileMigration> iterator = migrations.stream()
                .filter(migration -> versionsToMigrate.contains(migration.getVersion()))
                .sorted(Comparator.comparing(ImportFileMigration::getVersion))
                .iterator();
        while (iterator.hasNext()) {
            ImportFileMigration migration = iterator.next();
            try {
                documentNode = migration.makeMigration(documentNode);
            } catch (Exception exception) {
                String message = String.format("Failed to make migration %d", migration.getVersion());
                throw new MigrationException(message, exception, id, name);
            }
        }

        return documentNode;
    }

    public ObjectNode revertMigrationIfNeeded(ObjectNode node) {
        if (node == null) {
            return null;
        }

        if (isLegacyExport) {
            return revertMigration.stream()
                    .filter(m -> m.getVersion() == 101)
                    .findFirst()
                    .map(m -> m.revert(node))
                    .orElse(node);
        }

        return node;
    }

    private Collection<Integer> getDocumentVersions(ObjectNode node) throws MigrationException {
        try {
            return versionsGetterService.getVersions(node);
        } catch (Exception exception) {
            throw new MigrationException("Failed to retrieve migration data", exception,
                    getId(node), getName(node));
        }
    }

    private static Collection<Integer> subtract(
            Collection<Integer> collection1,
            Collection<Integer> collection2
    ) {
        Set<Integer> set = new HashSet<>(collection1);
        set.removeAll(collection2);
        return set;
    }

    private static String getId(ObjectNode node) {
        return Optional.ofNullable(node.get("id"))
                .map(JsonNode::asText)
                .orElse(null);
    }

    private static String getName(ObjectNode node) {
        return Optional.ofNullable(node.get("name"))
                .map(JsonNode::asText)
                .orElse(null);
    }
}
