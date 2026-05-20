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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ChainExportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Transactional(readOnly = true)
@Service
public class ExportService {

    @Value("${app.prefix}")
    private String appName;

    @Value("${qip.export.legacy-format}")
    private boolean isLegacyExport;

    private final YAMLMapper yamlMapper;
    private final ChainService chainService;
    private final ChainFinderService chainFinderService;
    private final ActionsLogService actionLogger;
    private final ChainExternalEntityMapper chainExternalEntityMapper;
    private final FileMigrationService fileMigrationService;

    @Autowired
    public ExportService(
            YAMLMapper yamlMapper,
            ChainService chainService,
            ActionsLogService actionLogger,
            ChainFinderService chainFinderService,
            ChainExternalEntityMapper chainExternalEntityMapper,
            FileMigrationService fileMigrationService
    ) {
        this.yamlMapper = yamlMapper;
        this.chainService = chainService;
        this.chainFinderService = chainFinderService;
        this.actionLogger = actionLogger;
        this.chainExternalEntityMapper = chainExternalEntityMapper;
        this.fileMigrationService = fileMigrationService;
    }

    public Pair<String, byte[]> exportAllChains() {
        List<Chain> allChains = chainFinderService.findAll();
        return exportChain(allChains);
    }

    public Pair<String, byte[]> exportListChains(List<String> chainIds, boolean exportWithSubChains) {
        if (exportWithSubChains) {
            chainIds = chainService.getSubChainsIds(chainIds, new ArrayList<String>());
        }
        List<Chain> chains = chainFinderService.findAllById(chainIds);
        return exportChain(chains);
    }

    public Pair<String, byte[]> exportSingleChain(String chainId) {
        Chain chain = chainFinderService.findById(chainId);
        return exportChain(List.of(chain));
    }

    private Pair<String, byte[]> exportChain(@NonNull List<Chain> chains) {
        Map<Path, byte[]> fileContentMap = new HashMap<>();

        try {
            for (Chain chain : chains) {
                fileContentMap.putAll(createChainFiles(chain));
            }
            String zipName = generateExportZipName();
            byte[] zipBytes = zipChainFiles(fileContentMap);
            for (Chain chain : chains) {
                logChainExport(chain);
            }
            return Pair.of(zipName, zipBytes);
        } catch (Exception e) {
            throw new ChainExportException(e);
        }
    }

    private Map<Path, byte[]> createChainFiles(Chain chain) throws IOException, JSONException {
        Map<Path, byte[]> result = new HashMap<>();

        Path chainDirectory = getChainDirectory(chain);

        String chainFileName = generateChainYamlName(chain);
        List<Deployment> deployments = chain.getDeployments();
        if (deployments.size() > 1) {
            String curSnapShot = Optional.ofNullable(chain.getCurrentSnapshot())
                    .map(Snapshot::getId).orElse("");
            chain.setDeployments(Collections.singletonList(deployments
                    .stream().filter(deployment -> curSnapShot.equals(deployment.getSnapshot()
                            .getId())).findFirst().orElse(deployments.stream()
                            .min(Comparator.comparing(Deployment::getCreatedWhen)).orElse(null))));
        }
        var entity = chainExternalEntityMapper.toExternalEntity(chain);
        ObjectNode node = fileMigrationService.revertMigrationIfNeeded(yamlMapper.valueToTree(entity.getChainExternalEntity()));
        result.put(chainDirectory.resolve(chainFileName), yamlMapper.writeValueAsString(node).getBytes());
        entity.getElementPropertyFiles().forEach((name, data) -> {
            String fileName = isLegacyExport
                    ? name
                    : RESOURCES_FOLDER_PREFIX + name;

            Path targetPath = chainDirectory.resolve(fileName);
            result.put(targetPath, data);
        });

        return result;
    }

    private byte[] zipChainFiles(Map<Path, byte[]> fileContentMap) throws IOException {
        ZipOutputStream zipOut;
        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        zipOut = new ZipOutputStream(fos);
        for (Map.Entry<Path, byte[]> entry : fileContentMap.entrySet()) {
            Path path = Path.of(CHAINS_ARCH_PARENT_DIR).resolve(entry.getKey());
            ZipEntry zipEntry = new ZipEntry(path.toString());
            zipOut.putNextEntry(zipEntry);
            byte[] data = entry.getValue();
            zipOut.write(data, 0, data.length);
            zipOut.closeEntry();
        }
        zipOut.close();
        fos.close();
        return fos.toByteArray();
    }

    public Path getChainDirectory(Chain chain) {
        return Path.of(chain.getId());
    }

    public String generateExportZipName() {
        DateFormat dateFormat = new SimpleDateFormat(DATE_TIME_FORMAT_PATTERN);
        return EXPORT_FILE_NAME_PREFIX + dateFormat.format(new Date()) + ZIP_NAME_POSTFIX;
    }

    public String generateChainYamlName(Chain chain) {
        return isLegacyExport
                ? CHAIN_YAML_NAME_PREFIX + chain.getId() + YAML_FILE_NAME_POSTFIX
                : chain.getId() + CHAIN_YAML_NAME_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    private void logChainExport(Chain chain) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CHAIN)
                .entityId(chain.getId())
                .entityName(chain.getName())
                .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName())
                .operation(LogOperation.EXPORT)
                .build());
    }
}
