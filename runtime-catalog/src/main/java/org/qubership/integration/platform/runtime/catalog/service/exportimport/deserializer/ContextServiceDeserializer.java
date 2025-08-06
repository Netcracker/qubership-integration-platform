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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ImportFileMigrationUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_VERSION_FIELD_OLD;


@Slf4j
@Component
public class ContextServiceDeserializer {

    private final YAMLMapper yamlMapper;
    private final Map<Integer, ServiceImportFileMigration> importFileMigrations;

    @Autowired
    public ContextServiceDeserializer(YAMLMapper yamlExportImportMapper,
                               List<ServiceImportFileMigration> importFileMigrations) {
        this.yamlMapper = yamlExportImportMapper;
        this.importFileMigrations = importFileMigrations.stream()
                .collect(Collectors.toMap(ImportFileMigration::getVersion, Function.identity()));
    }

    public ContextSystem deserializeSystem(ObjectNode serviceNode) {

        ContextSystem system;
        try {
            serviceNode = migrateToActualFileVersion(serviceNode);
            system = yamlMapper.treeToValue(serviceNode, ContextSystem.class);
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return system;
    }

    private ObjectNode migrateToActualFileVersion(ObjectNode serviceNode) throws Exception {

        if ((!serviceNode.has(IMPORT_VERSION_FIELD_OLD) && !serviceNode.has(IMPORT_MIGRATIONS_FIELD))
                ||
                (serviceNode.has(IMPORT_VERSION_FIELD_OLD) && serviceNode.get(IMPORT_VERSION_FIELD_OLD) != null
                        &&
                        serviceNode.has(IMPORT_MIGRATIONS_FIELD) && serviceNode.get(IMPORT_MIGRATIONS_FIELD) != null)
        ) {
            log.error(
                    "Incorrect combination of \"{}\" and \"{}\" fields for a service migration data",
                    IMPORT_VERSION_FIELD_OLD,
                    IMPORT_MIGRATIONS_FIELD);
            throw new Exception("Incorrect combination of fields for a service migration data");
        }

        List<Integer> importVersions;
        if (serviceNode.has(IMPORT_VERSION_FIELD_OLD)) {
            importVersions =
                    IntStream.rangeClosed(1, serviceNode.get(IMPORT_VERSION_FIELD_OLD).asInt())
                            .boxed()
                            .toList();
        } else {
            importVersions =
                    serviceNode.get(IMPORT_MIGRATIONS_FIELD) != null
                            ? Arrays.stream(
                                    serviceNode.get(IMPORT_MIGRATIONS_FIELD)
                                            .asText()
                                            .replaceAll("[\\[\\]]", "")
                                            .split(","))
                            .map(String::trim)
                            .filter(StringUtils::isNotEmpty)
                            .map(Integer::parseInt)
                            .toList()
                            : new ArrayList<>();
        }
        log.trace("importVersions = {}", importVersions);

        List<Integer> actualVersions = ImportFileMigrationUtils.getActualServiceFileMigrationVersions();
        log.trace("actualVersions = {}", actualVersions);

        List<Integer> nonexistentVersions = new ArrayList<>(importVersions);
        nonexistentVersions.removeAll(actualVersions);
        if (!nonexistentVersions.isEmpty()) {
            String serviceId = Optional.ofNullable(serviceNode.get("id")).map(JsonNode::asText).orElse(null);
            String serviceName = Optional.ofNullable(serviceNode.get("name")).map(JsonNode::asText).orElse(null);

            log.error(
                    "Unable to import the service {} ({}) exported from newer version: nonexistent migrations {} are present",
                    serviceName,
                    serviceId,
                    nonexistentVersions);

            throw new ServiceImportException(
                    serviceId,
                    serviceName,
                    "Unable to import a service exported from newer version");
        }

        List<Integer> versionsToMigrate = new ArrayList<>(actualVersions);
        versionsToMigrate.removeAll(importVersions);
        versionsToMigrate.sort(null);
        log.trace("versionsToMigrate = {}", versionsToMigrate);

        for (int version : versionsToMigrate) {
            serviceNode = importFileMigrations.get(version).makeMigration(serviceNode);
        }

        return serviceNode;
    }
}
