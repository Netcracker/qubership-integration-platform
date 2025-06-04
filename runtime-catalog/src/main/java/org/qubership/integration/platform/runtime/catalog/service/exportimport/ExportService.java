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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.rest.v1.exception.exceptions.ChainExportException;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Transactional(readOnly = true)
@Service
public class ExportService {
    private final YAMLMapper yamlMapper;
    private final ObjectMapper objectMapper;
    private final ChainService chainService;
    private final ActionsLogService actionLogger;
    private final ChainExternalEntityMapper chainExternalEntityMapper;

    @Autowired
    public ExportService(
            YAMLMapper yamlMapper,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            ChainService chainService,
            ActionsLogService actionLogger,
            ChainExternalEntityMapper chainExternalEntityMapper
    ) {
        this.yamlMapper = yamlMapper;
        this.objectMapper = objectMapper;
        this.chainService = chainService;
        this.actionLogger = actionLogger;
        this.chainExternalEntityMapper = chainExternalEntityMapper;
    }

    public Pair<String, byte[]> exportAllChains() {
        List<Chain> allChains = chainService.findAll();
        return exportChain(allChains);
    }

    public Pair<String, byte[]> exportListChains(List<String> chainIds, boolean exportWithSubChains) {
        if (exportWithSubChains) {
            chainIds = chainService.getSubChainsIds(chainIds, new ArrayList<String>());
        }
        List<Chain> chains = chainService.findAllById(chainIds);
        return exportChain(chains);
    }

    public Pair<String, byte[]> exportSingleChain(String chainId) {
        Chain chain = chainService.findById(chainId);
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
        String chainYaml = yamlMapper.writeValueAsString(entity.getChainExternalEntity());
        result.put(chainDirectory.resolve(chainFileName), chainYaml.getBytes());
        entity.getElementPropertyFiles()
                .forEach((name, data) -> result.put(chainDirectory.resolve(name), data));

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
        return CHAIN_YAML_NAME_PREFIX + chain.getId() + YAML_FILE_NAME_POSTFIX;
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

    protected Map<String, String> getPropertiesToSaveInSeparateFile(Chain chain) throws JsonProcessingException, JSONException {
        Map<String, String> result = new HashMap<>();

        for (ChainElement element : chain.getElements()) {
            ArrayList<String> propsToExportSeparately = ExportImportUtils.getPropertiesToExportInSeparateFile(element);
            if (!CollectionUtils.isEmpty(propsToExportSeparately)) {
                Map<String, Object> properties = element.getProperties();
                String propString = null;
                if (ExportImportUtils.isPropertiesFileGroove(element.getProperties())
                        || ExportImportUtils.isPropertiesFileSql(element.getProperties())) {
                    Object propObject = properties.get(propsToExportSeparately.get(0));
                    if (propObject != null) {
                        propString = propObject.toString();
                    } else {
                        propString = "";
                    }
                } else if (ExportImportUtils.isPropertiesFileJson(element.getProperties())) {
                    Map<String, Object> propsToExportSeparatelyMap = properties.keySet().stream()
                            .filter(p -> propsToExportSeparately.contains(p) && properties.get(p) != null)
                            .collect(Collectors.toMap(p -> p, properties::get));
                    if (!CollectionUtils.isEmpty(propsToExportSeparatelyMap)) {
                        propString = new JSONObject(objectMapper.writeValueAsString(propsToExportSeparatelyMap))
                                .toString(4);
                    }
                } else {
                    throw new IllegalArgumentException("Invalid property '" + EXPORT_FILE_EXTENSION_PROPERTY
                            + "' of element " + element.getId());
                }

                if (propString != null) {
                    result.put(ExportImportUtils.generatePropertiesFileName(element), propString);
                }
            }

            if (SERVICE_CALL.equals(element.getType())) {
                String propString = null;
                List<Map<String, Object>> afterPropertyList = (List<Map<String, Object>>) element.getProperties().get(AFTER);
                if (!CollectionUtils.isEmpty(afterPropertyList)) {
                    for (Map<String, Object> afterProperty : afterPropertyList) {
                        if (SCRIPT.equals(afterProperty.get(TYPE))) {
                            propString = afterProperty.get(SCRIPT) != null ? afterProperty.get(SCRIPT).toString() : "";
                            result.put(ExportImportUtils.generateAfterScriptFileName(element.getId(), afterProperty), propString);
                        } else if (null != afterProperty.get(TYPE) && StringUtils.contains((String) afterProperty.get(TYPE), MAPPER)) {
                            propString = getPropertyStringForMapper(afterProperty);
                            result.put(ExportImportUtils.generateAfterMapperFileName(element.getId(), afterProperty), propString);
                        }
                    }
                }
                Map beforeProperty = (Map<String, Object>) element.getProperties().get(BEFORE);
                if (!CollectionUtils.isEmpty(beforeProperty)) {
                    if (SCRIPT.equals(beforeProperty.get(TYPE))) {
                        propString = beforeProperty.get(SCRIPT) != null ? beforeProperty.get(SCRIPT).toString() : "";
                        result.put(ExportImportUtils.generateBeforeScriptFileName(element.getId()), propString);
                    } else if (null != beforeProperty.get(TYPE) && StringUtils.contains((String) beforeProperty.get(TYPE), MAPPER)) {
                        propString = getPropertyStringForMapper(beforeProperty);
                        result.put(ExportImportUtils.generateBeforeMapperFileName(element.getId(), beforeProperty), propString);
                    }
                }
            }
        }
        return result;
    }

    private String getPropertyStringForMapper(Map beforeProperty) throws JsonProcessingException, JSONException {
        String propString = "";
        List<String> props = List.of(MAPPING_DESCRIPTION, MAPPING, SOURCE, TARGET);
        Map<String, Object> propsToExportSeparatelyMap = (Map<String, Object>) beforeProperty.keySet().stream()
                .filter(p -> props.stream().anyMatch(p1 -> p1.equals(p)) && beforeProperty.get(p) != null)
                .collect(Collectors.toMap(p -> p, beforeProperty::get));
        if (!CollectionUtils.isEmpty(propsToExportSeparatelyMap)) {
            propString = new JSONObject(objectMapper.writeValueAsString(propsToExportSeparatelyMap))
                    .toString(4);
        }
        return propString;
    }
}
