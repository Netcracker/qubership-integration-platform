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

package org.qubership.integration.platform.variables.management.service.exportimport.instructions;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.variables.management.consul.ConsulService;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.*;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.enums.filter.FilterCondition;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions.ImportInstruction;
import org.qubership.integration.platform.variables.management.persistence.configs.repository.exportimport.ImportInstructionsRepository;
import org.qubership.integration.platform.variables.management.rest.exception.ImportInstructionsExternalException;
import org.qubership.integration.platform.variables.management.rest.v1.dto.instructions.ImportInstructionsFilterRequest;
import org.qubership.integration.platform.variables.management.service.ActionsLogService;
import org.qubership.integration.platform.variables.management.service.exportimport.instructions.filter.ImportInstructionsFilterSpecificationBuilder;
import org.qubership.integration.platform.variables.management.service.exportimport.instructions.mapper.ImportInstructionsMapper;
import org.qubership.integration.platform.variables.management.util.ExportImportUtils;
import org.qubership.integration.platform.variables.management.validation.EntityValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ImportInstructionsService {

    private final YAMLMapper yamlMapper;
    private final ImportInstructionsRepository importInstructionsRepository;
    private final ImportInstructionsMapper importInstructionsMapper;
    private final ConsulService consulService;
    private final ImportInstructionsFilterSpecificationBuilder importInstructionsFilterSpecificationBuilder;
    private final EntityValidator entityValidator;
    private final ActionsLogService actionsLogService;

    @Autowired
    public ImportInstructionsService(
            @Qualifier("yamlMapper") YAMLMapper yamlMapper,
            ImportInstructionsRepository importInstructionsRepository,
            ImportInstructionsMapper importInstructionsMapper,
            ConsulService consulService,
            ImportInstructionsFilterSpecificationBuilder importInstructionsFilterSpecificationBuilder,
            EntityValidator entityValidator,
            ActionsLogService actionsLogService
    ) {
        this.yamlMapper = yamlMapper;
        this.importInstructionsRepository = importInstructionsRepository;
        this.importInstructionsMapper = importInstructionsMapper;
        this.consulService = consulService;
        this.importInstructionsFilterSpecificationBuilder = importInstructionsFilterSpecificationBuilder;
        this.entityValidator = entityValidator;
        this.actionsLogService = actionsLogService;
    }

    public List<ImportInstruction> getImportInstructions() {
        return importInstructionsRepository.findAll();
    }

    public List<ImportInstruction> getImportInstructions(List<ImportInstructionsFilterRequest> filters) {
        if (containsOnlyOverriddenByFilter(filters)) {
            return Collections.emptyList();
        }

        Specification<ImportInstruction> specification = importInstructionsFilterSpecificationBuilder
                .buildFilter(filters);
        return importInstructionsRepository.findAll(specification);
    }

    public List<ImportInstruction> searchImportInstructions(String searchCondition) {
        Specification<ImportInstruction> specification = importInstructionsFilterSpecificationBuilder
                .buildSearch(Collections.singletonList(
                        ImportInstructionsFilterRequest.builder()
                                .feature(ImportInstructionsFilterColumn.ID)
                                .condition(FilterCondition.CONTAINS)
                                .value(searchCondition)
                                .build()
                ));
        return importInstructionsRepository.findAll(specification);
    }

    public ImportInstructionsConfig getImportInstructionsConfig() {
        return importInstructionsMapper.asConfig(importInstructionsRepository.findAll());
    }

    @Transactional
    public ImportInstruction addImportInstruction(ImportInstruction importInstruction) {
        if (importInstructionsRepository.existsById(importInstruction.getId())) {
            log.error("Instruction for {}} already exist", importInstruction.getId());
            throw new ImportInstructionsExternalException("Instruction for " + importInstruction.getId() + " already exist");
        }

        importInstruction = importInstructionsRepository.save(importInstruction);

        logAction(importInstruction.getId(), LogOperation.CREATE);

        return importInstruction;
    }

    @Transactional
    public ImportInstruction updateImportInstruction(ImportInstruction importInstruction) {
        if (!importInstructionsRepository.existsById(importInstruction.getId())) {
            log.error("Instruction with id {} does not exist", importInstruction.getId());
            throw new ImportInstructionsExternalException("Instruction with id " + importInstruction.getId() + " does not exist");
        }

        importInstruction = importInstructionsRepository.save(importInstruction);

        logAction(importInstruction.getId(), LogOperation.UPDATE);

        return importInstruction;
    }

    public void deleteImportInstructionsByIds(List<String> importInstructionsIds) {
        importInstructionsRepository.deleteAllById(importInstructionsIds);
        importInstructionsIds.forEach(instructionId -> logAction(instructionId, LogOperation.DELETE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ImportInstructionsExecutionResult> uploadImportInstructions(
            MultipartFile importInstructionsConfigFile,
            List<String> labels
    ) {
        String fileName = importInstructionsConfigFile.getOriginalFilename();
        if (!ExportImportUtils.isYamlFile(fileName)) {
            log.error("File {} must have yaml/yml extension", fileName);
            throw new ImportInstructionsExternalException("File " + fileName + " must have yaml/yml extension");
        }

        CommonVariablesImportInstructionsConfig importInstructionsConfig;
        try {
            importInstructionsConfig = yamlMapper.readValue(
                    importInstructionsConfigFile.getBytes(),
                    CommonVariablesImportInstructionsConfig.class
            );
        } catch (IOException e) {
            log.error("Unable to parse import instructions config file: {}", fileName);
            throw new ImportInstructionsExternalException("Unable to parse import instructions config file: " + fileName, e);
        }
        importInstructionsConfig.setLabels(labels);

        entityValidator.validate(importInstructionsConfig);

        List<ImportInstructionsExecutionResult> results = performVariableDeleteInstructions(importInstructionsConfig);

        List<ImportInstruction> importInstructions = importInstructionsRepository
                .saveAll(importInstructionsMapper.asEntities(importInstructionsConfig));

        importInstructions.forEach(importInstruction -> logAction(importInstruction.getId(), LogOperation.CREATE_OR_UPDATE));

        return results;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PerformInstructionsResult performIgnoreInstructions(Set<String> variablesToImport) {
        ImportInstructionsConfig instructionsConfig = getImportInstructionsConfig();

        List<ImportInstructionsExecutionResult> instructionsExecutionResults = new ArrayList<>();
        Set<String> ignoreSet = instructionsConfig.getIgnore().stream()
                .filter(ignoreInstruction -> {
                    if (variablesToImport.contains(ignoreInstruction)) {
                        instructionsExecutionResults.add(ImportInstructionsExecutionResult.builder()
                                .id(ignoreInstruction)
                                .entityType(ImportEntityType.COMMON_VARIABLE)
                                .status(ImportInstructionExecutionStatus.IGNORED)
                                .build());
                        return true;
                    }
                    return false;
                })
                .collect(Collectors.toSet());
        return new PerformInstructionsResult(ignoreSet, instructionsExecutionResults);
    }

    private List<ImportInstructionsExecutionResult> performVariableDeleteInstructions(
            CommonVariablesImportInstructionsConfig instructionsConfig
    ) {
        if (
                Optional.ofNullable(instructionsConfig.getCommonVariables())
                        .map(ImportInstructionsConfig::getDelete)
                        .filter(deleteInstructions -> !deleteInstructions.isEmpty())
                        .isEmpty()
        ) {
            return Collections.emptyList();
        }

        Map<String, String> existingVariables = consulService.getAllCommonVariables();
        Set<String> deleteIds = instructionsConfig.getCommonVariables().getDelete();
        List<String> filteredDeleteIds = deleteIds.stream()
                .filter(existingVariables::containsKey)
                .collect(Collectors.toList());
        ImportInstructionExecutionStatus executionStatus;
        String errorMessage = null;
        try {
            consulService.deleteCommonVariables(filteredDeleteIds);

            logCommonVariablesDeleteActions(filteredDeleteIds);

            executionStatus = ImportInstructionExecutionStatus.DELETED;
        } catch (Exception e) {
            executionStatus = ImportInstructionExecutionStatus.ERROR_ON_DELETE;
            errorMessage = e.getMessage();
        }

        List<ImportInstructionsExecutionResult> results = new ArrayList<>();
        for (String deleteId : deleteIds) {
            ImportInstructionExecutionStatus executionStatusForVariable = filteredDeleteIds.contains(deleteId)
                    ? executionStatus
                    : ImportInstructionExecutionStatus.NO_ACTION;
            if (executionStatusForVariable == ImportInstructionExecutionStatus.DELETED) {
                log.info("Variable {} deleted as a part of import exclusion list", deleteId);
            }
            results.add(ImportInstructionsExecutionResult.builder()
                    .id(deleteId)
                    .entityType(ImportEntityType.COMMON_VARIABLE)
                    .status(executionStatusForVariable)
                    .errorMessage(errorMessage)
                    .build());
        }

        return results;
    }

    private boolean containsOnlyOverriddenByFilter(List<ImportInstructionsFilterRequest> filters) {
        return CollectionUtils.isNotEmpty(filters)
                && filters.size() == 1
                && ImportInstructionsFilterColumn.OVERRIDDEN_BY.equals(filters.get(0).getFeature());
    }

    private void logAction(String instructionId, LogOperation operation) {
        actionsLogService.logAction(ActionLog.builder()
                .entityName(instructionId)
                .parentName(ImportEntityType.COMMON_VARIABLE.name())
                .entityType(EntityType.IMPORT_INSTRUCTION)
                .operation(operation)
                .build());
    }

    private void logCommonVariablesDeleteActions(Collection<String> variableNames) {
        variableNames.forEach(variableName -> actionsLogService.logAction(
                ActionLog.builder()
                        .entityName(variableName)
                        .entityType(EntityType.COMMON_VARIABLE)
                        .operation(LogOperation.DELETE)
                        .build()
        ));
    }
}
