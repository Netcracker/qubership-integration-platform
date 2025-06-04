package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ChainImportException;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions.ChainFileVersionsGetterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChainFileMigrationService {
    private final YAMLMapper yamlMapper;
    private final Map<Integer, ChainImportFileMigration> chainImportFileMigrationMap;
    private final ChainFileVersionsGetterService chainFileVersionsGetterService;

    @Autowired
    public ChainFileMigrationService(
            YAMLMapper yamlMapper,
            Collection<ChainImportFileMigration> chainImportFileMigrations,
            ChainFileVersionsGetterService chainFileVersionsGetterService
    ) {
        this.yamlMapper = yamlMapper;
        this.chainImportFileMigrationMap = chainImportFileMigrations.stream()
                .collect(Collectors.toMap(ChainImportFileMigration::getVersion, Function.identity()));
        this.chainFileVersionsGetterService = chainFileVersionsGetterService;
    }

    public String migrateToActualVersion(String data) throws Exception {
        ObjectNode fileNode = (ObjectNode) yamlMapper.readTree(data);
        String chainId = Optional.ofNullable(fileNode.get("id")).map(JsonNode::asText).orElse(null);

        List<Integer> importVersions;
        try {
            importVersions = chainFileVersionsGetterService.getVersions(fileNode);
        } catch (Exception exception) {
            throw new Exception("Failed to retrieve chain migration data for chain with ID: " + chainId, exception);
        }
        log.trace("importVersions = {}", importVersions);

        log.trace("actualVersions = {}", getActualMigrationVersions());

        List<Integer> nonexistentVersions = new ArrayList<>(importVersions);
        nonexistentVersions.removeAll(getActualMigrationVersions());
        if (!nonexistentVersions.isEmpty()) {
            String chainName = Optional.ofNullable(fileNode.get("name")).map(JsonNode::asText).orElse(null);

            log.error(
                    "Unable to import a chain {} ({}) exported from newer version: nonexistent migrations {} are present",
                    chainName,
                    chainId,
                    nonexistentVersions);

            throw new ChainImportException(
                    chainId,
                    chainName,
                    "Unable to import a chain exported from newer version");
        }

        List<Integer> versionsToMigrate = new ArrayList<>(getActualMigrationVersions());
        versionsToMigrate.removeAll(importVersions);
        versionsToMigrate.sort(null);
        log.trace("versionsToMigrate = {}", versionsToMigrate);

        for (int version : versionsToMigrate) {
            fileNode = chainImportFileMigrationMap.get(version).makeMigration(fileNode);
        }

        return yamlMapper.writeValueAsString(fileNode);
    }

    public Collection<Integer> getActualMigrationVersions() {
        return chainImportFileMigrationMap.keySet();
    }
}
