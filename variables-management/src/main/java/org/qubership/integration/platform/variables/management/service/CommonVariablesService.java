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

package org.qubership.integration.platform.variables.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.variables.management.consul.ConsulService;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.PerformInstructionsResult;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.rest.exception.EmptyVariableFieldException;
import org.qubership.integration.platform.variables.management.rest.exception.MalformedVariableNameException;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariableDTO;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariablePreview;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.ImportVariableStatus;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.VariablesFileResponse;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.ImportVariablesResult;
import org.qubership.integration.platform.variables.management.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.variables.management.util.ExportImportUtils;
import jakarta.persistence.EntityExistsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.variables.management.service.DefaultVariablesService.DEFAULT_VARIABLES_LIST;
import static org.qubership.integration.platform.variables.management.validation.EntityValidator.VARIABLE_NAME_PATTERN_PREDICATE;

@Slf4j
@Service
public class CommonVariablesService {

    private static final String VARIABLES_PREFIX = "common-variables";
    private static final String VAR_PARENT_DIR = "variables";
    protected static final String ZIP_EXTENSION = "zip";
    private static final String YAML_EXTENSION = "yaml";
    private static final String YML_EXTENSION = "yml";
    private static final String[] NON_EXPORTABLE_VARIABLES = DEFAULT_VARIABLES_LIST;
    private static final String EMPTY_COMMON_VARIABLE_NAME_ERROR_MESSAGE = "Common variable's name is empty";

    private final ActionsLogService actionLogger;
    private final YAMLMapper yamlMapper;
    private final SecuredVariableService securedVariableService;
    private final ConsulService consulService;
    private final ImportInstructionsService importInstructionsService;

    @Autowired
    public CommonVariablesService(
            ActionsLogService actionLogger,
            @Qualifier("yamlImportExportMapper") YAMLMapper yamlImportExportMapper,
            SecuredVariableService securedVariableService,
            ConsulService consulService,
            ImportInstructionsService importInstructionsService
    ) {
        this.actionLogger = actionLogger;
        this.yamlMapper = yamlImportExportMapper;
        this.securedVariableService = securedVariableService;
        this.consulService = consulService;
        this.importInstructionsService = importInstructionsService;
    }

    public Map<String, String> getVariables() {
        return consulService.getAllCommonVariables();
    }

    public String addVariable(String key, String value) {

        if (!VARIABLE_NAME_PATTERN_PREDICATE.test(key)) {
            throw new MalformedVariableNameException(key);
        }

        Set<String> securedVariablesNames = securedVariableService.getVariablesForDefaultSecret(false);
        ImportVariableDTO commonVariable = checkAndMapVariable(key, value, securedVariablesNames, false);
        consulService.updateCommonVariable(key, value);
        return commonVariable.getName();
    }

    public List<ImportVariableDTO> addVariables(Map<String, String> variables, boolean importMode) {
        List<ImportVariableDTO> importDTOs = Collections.emptyList();
        if (!variables.isEmpty()) {
            Set<String> securedVariablesNames = securedVariableService.getVariablesForDefaultSecret(false);

            importDTOs = variables.entrySet().stream()
                    .map(entry -> checkAndMapVariable(entry.getKey(), entry.getValue(), securedVariablesNames, importMode))
                    .toList();
            consulService.updateCommonVariables(variables);
        }
        return importDTOs;
    }

    private ImportVariableDTO checkAndMapVariable(String key, String value, Set<String> securedVariablesNames, boolean importMode) {
        if (securedVariablesNames.contains(key)) {
            throw new EntityExistsException("Secured variable with name " + key + " already exists");
        }

        if (StringUtils.isBlank(key)) {
            throw new EmptyVariableFieldException(EMPTY_COMMON_VARIABLE_NAME_ERROR_MESSAGE);
        }

        if (!VARIABLE_NAME_PATTERN_PREDICATE.test(key)) {
            throw new MalformedVariableNameException(key);
        }

        ImportVariableDTO variable = new ImportVariableDTO(key, value);

        Pair<String, String> oldVar = consulService.getCommonVariable(key);
        boolean exists = oldVar != null;
        variable.setStatus(exists ? ImportVariableStatus.UPDATED : ImportVariableStatus.CREATED);
        LogOperation operation = importMode ? LogOperation.IMPORT : exists ? LogOperation.UPDATE : LogOperation.CREATE;
        logCommonVariableAction(key, operation);

        return variable;
    }

    public void addVariablesUnlogged(Map<String, String> variables) {
        consulService.updateCommonVariables(variables);
    }

    public void deleteVariables(List<String> variablesNames) {
        consulService.deleteCommonVariables(variablesNames);
        variablesNames.forEach(name -> logCommonVariableAction(name, LogOperation.DELETE));
    }

    public VariablesFileResponse exportVariables(List<String> variablesNames, boolean asArchive) {
        Map<String, String> variablesForExport = CollectionUtils.isEmpty(variablesNames) ?
                consulService.getAllCommonVariables() :
                consulService.getCommonVariables(variablesNames);

        variablesForExport = variablesForExport.entrySet().stream()
                .filter(name -> Arrays.stream(NON_EXPORTABLE_VARIABLES)
                        .noneMatch(excludedName -> excludedName.equals(name.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (variablesForExport.isEmpty()) {
            return null;
        }

        variablesForExport.forEach((k, v) -> logCommonVariableAction(k, LogOperation.EXPORT));

        byte[] contentBytes = stringMapAsByteArr(variablesForExport);
        String filename = exportVariablesGenerateFilename();
        if (asArchive) {
            try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
                try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                    String path = VAR_PARENT_DIR + File.separator;

                    zipOut.putNextEntry(new ZipEntry(path));
                    zipOut.closeEntry();

                    zipOut.putNextEntry(new ZipEntry(path + filename));
                    zipOut.write(contentBytes, 0, contentBytes.length);
                    zipOut.closeEntry();
                }
                return new VariablesFileResponse(fos.toByteArray(), filename);
            } catch (IOException e) {
                throw new RuntimeException("Unknown exception while archive creation: " + e.getMessage());
            }
        } else {
            return new VariablesFileResponse(contentBytes, filename);
        }
    }

    public ImportVariablesResult importVariables(MultipartFile file, Set<String> variablesNames) {
        Map<String, String> variablesForImport;

        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            variablesForImport = importVariableZip(file);
        } else if (YAML_EXTENSION.equalsIgnoreCase(fileExtension) || YML_EXTENSION.equalsIgnoreCase(fileExtension)) {
            try {
                variablesForImport = importVariablesFile(file.getBytes());
            } catch (Exception e) {
                log.error("Unable to convert file to variables {}", e.getMessage());
                throw new RuntimeException("Unable to convert file to variables");
            }
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        PerformInstructionsResult ignoreResult = importInstructionsService.performIgnoreInstructions(variablesForImport.keySet());
        Set<String> variablesToIgnore = ignoreResult.variablesToIgnore();

        List<ImportVariableDTO> importVariableDTOS = new ArrayList<>((addVariables(variablesForImport.entrySet().stream()
                .filter(entry -> !variablesToIgnore.contains(entry.getKey()))
                .filter(entry -> variablesNames == null || variablesNames.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), true)));
        variablesToIgnore.stream()
                .map(variableName -> ImportVariableDTO.builder()
                        .name(variableName)
                        .value(variablesForImport.get(variableName))
                        .status(ImportVariableStatus.IGNORED)
                        .build())
                .forEach(importVariableDTOS::add);
        return ImportVariablesResult.builder()
                .variables(importVariableDTOS)
                .instructions(ignoreResult.importInstructionsExecutionResults())
                .build();
    }

    private Map<String, String> importVariableZip(MultipartFile file) {
        Map<String, String> variablesForImport = Collections.emptyMap();

        String directoryForExport = UUID.randomUUID().toString();
        List<File> extractedSystemFiles;
        try (InputStream is = file.getInputStream()) {
            extractedSystemFiles = extractVariablesFromZip(is, directoryForExport);
        } catch (IOException e) {
            ExportImportUtils.deleteFile(directoryForExport);
            throw new RuntimeException("Unexpected error while archive unpacking: " + e.getMessage());
        } catch (RuntimeException e) {
            ExportImportUtils.deleteFile(directoryForExport);
            throw e;
        }

        try {
            if (!extractedSystemFiles.isEmpty()) {
                variablesForImport = importVariablesFile(Files.readString(extractedSystemFiles.get(0).toPath()));
            }
        } catch (IOException e) {
            log.error("Unable to convert file to variables {}", e.getMessage());
            throw new RuntimeException("Unable to convert file to variables");
        } finally {
            ExportImportUtils.deleteFile(directoryForExport);
        }

        return variablesForImport;
    }

    public List<ImportVariablePreview> importVariablePreview(MultipartFile file) {
        String fileExtension = FilenameUtils.getExtension(file.getOriginalFilename());
        Map<String, String> newVariables;

        if (YAML_EXTENSION.equalsIgnoreCase(fileExtension) || YML_EXTENSION.equalsIgnoreCase(fileExtension)) {
            try {
                newVariables = importVariablesFile(file.getBytes());
            } catch (Exception e) {
                log.error("Unable to convert file to preview variables {}", e.getMessage());
                throw new RuntimeException("Unable to convert file to preview variables");
            }
        } else if (ZIP_EXTENSION.equalsIgnoreCase(fileExtension)) {
            newVariables = importVariableZip(file);
        } else {
            throw new RuntimeException("Unsupported file extension: " + fileExtension);
        }

        Map<String, String> currentVariables = getVariables();
        return newVariables.entrySet().stream()
                .map(entry -> new ImportVariablePreview(
                        entry.getKey(), entry.getValue(), currentVariables.getOrDefault(entry.getKey(), "")))
                .collect(Collectors.toList());
    }

    private List<File> extractVariablesFromZip(InputStream is, String directoryForExport) throws IOException {
        List<File> result = Collections.emptyList();
        Path path = Paths.get(directoryForExport);

        try (ZipInputStream inputStream = new ZipInputStream(is)) {
            for (ZipEntry entry; (entry = inputStream.getNextEntry()) != null; ) {
                Path resolvedPath = path.resolve(entry.getName()).normalize();
                String entryName = entry.getName();
                Path entryPath = Paths.get(entryName);

                if (entryName.contains("..") || entryPath.isAbsolute()) {
                    throw new SecurityException("Invalid ZIP entry: " + entryName);
                }

                if (entryPath.startsWith(VAR_PARENT_DIR) && resolvedPath.startsWith(path)) {
                    if (!entry.isDirectory()) {
                        Files.createDirectories(resolvedPath.getParent());
                        Files.copy(inputStream, resolvedPath);
                        Files.setLastModifiedTime(resolvedPath, FileTime.fromMillis(entry.getTime()));
                    } else {
                        Files.createDirectories(resolvedPath);
                    }
                }
            }
        }

        Path importPath = Paths.get(directoryForExport + File.separator + VAR_PARENT_DIR);
        if (Files.exists(importPath)) {
            try (Stream<Path> ps = Files.walk(importPath)) {
                result = ps.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(f -> f.getName().startsWith(VARIABLES_PREFIX) && f.getName().endsWith(YAML_EXTENSION))
                        .collect(Collectors.toList());
            }
        }

        return result;
    }

    private Map<String, String> importVariablesFile(byte[] file) throws JsonProcessingException {
        return importVariablesFile(new String(file));
    }

    private Map<String, String> importVariablesFile(String file) throws JsonProcessingException {
        return yamlMapper.readValue(file, new TypeReference<>() {
        });
    }

    private String exportVariablesGenerateFilename() {
        return VARIABLES_PREFIX + "." + YAML_EXTENSION;
    }

    private byte[] stringMapAsByteArr(Map<String, String> variablesForExport) {
        try {
            return yamlMapper.writeValueAsString(variablesForExport).getBytes();
        } catch (JsonProcessingException e) {
            log.error("Unable to convert variables to file {}", e.getMessage());
            throw new RuntimeException("Unable to convert variables to file");
        }
    }

    private void logCommonVariableAction(String name, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.COMMON_VARIABLE)
                .entityName(name)
                .operation(operation)
                .build());
    }
}
