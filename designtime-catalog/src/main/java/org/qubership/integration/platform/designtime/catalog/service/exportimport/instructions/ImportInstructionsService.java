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

package org.qubership.integration.platform.designtime.catalog.service.exportimport.instructions;

import org.qubership.integration.platform.catalog.exception.ImportInstructionsExternalException;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportEntityType;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.catalog.persistence.configs.repository.instructions.ImportInstructionsRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.exportimport.instructions.DeleteInstructionsRequest;
import org.qubership.integration.platform.designtime.catalog.exception.exceptions.ImportInstructionsValidationException;
import org.qubership.integration.platform.designtime.catalog.model.enums.filter.FilterFeature;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.designtime.catalog.service.filter.ImportInstructionFilterSpecificationBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ImportInstructionsService {

    public static final String UNIQUE_OVERRIDE_DB_CONSTRAINT_NAME = "import_instructions_unique_override_idx";
    public static final String OVERRIDE_ACTION_DB_CONSTRAINT_NAME = "overridden_by_id_only_for_override";

    private final ImportInstructionsRepository importInstructionsRepository;
    private final ImportInstructionFilterSpecificationBuilder importInstructionFilterSpecificationBuilder;
    private final ActionsLogService actionsLogService;

    public ImportInstructionsService(
            ImportInstructionsRepository importInstructionsRepository,
            ImportInstructionFilterSpecificationBuilder importInstructionFilterSpecificationBuilder,
            ActionsLogService actionsLogService
    ) {
        this.importInstructionsRepository = importInstructionsRepository;
        this.importInstructionFilterSpecificationBuilder = importInstructionFilterSpecificationBuilder;
        this.actionsLogService = actionsLogService;
    }

    public List<ImportInstruction> getImportInstructions() {
        return importInstructionsRepository.findAll();
    }

    public List<ImportInstruction> getImportInstructions(Collection<FilterRequestDTO> filterRequestDTOS) {
        Specification<ImportInstruction> specification = importInstructionFilterSpecificationBuilder
                .buildFilter(filterRequestDTOS);
        return importInstructionsRepository.findAll(specification);
    }

    public List<ImportInstruction> searchImportInstructions(String searchCondition) {
        List<FilterRequestDTO> filters = Stream.of(FilterFeature.ID, FilterFeature.OVERRIDDEN_BY)
                .map(feature -> FilterRequestDTO.builder()
                        .feature(feature)
                        .condition(FilterCondition.CONTAINS)
                        .value(searchCondition)
                        .build())
                .collect(Collectors.toList());
        return importInstructionsRepository.findAll(importInstructionFilterSpecificationBuilder.buildSearch(filters));
    }

    @Transactional
    public ImportInstruction addImportInstruction(ImportInstruction importInstruction) {
        validateImportInstruction(importInstruction);

        if (importInstructionsRepository.existsById(importInstruction.getId())) {
            log.error("Instruction for {} already exist", importInstruction.getId());
            throw new ImportInstructionsExternalException("Instruction for " + importInstruction.getId() + " already exist");
        }

        importInstruction = importInstructionsRepository.persistAndReturn(importInstruction);

        logAction(importInstruction.getId(), importInstruction.getEntityType(), LogOperation.CREATE);

        return importInstruction;
    }

    @Transactional
    public ImportInstruction updateImportInstruction(ImportInstruction importInstruction) {
        validateImportInstruction(importInstruction);

        ImportInstruction existingImportInstruction = importInstructionsRepository.findById(importInstruction.getId())
                .orElseThrow(() -> {
                    log.error("Instruction with id {} does not exist", importInstruction.getId());
                    return new ImportInstructionsExternalException(
                            "Instruction with id " + importInstruction.getId() + " does not exist"
                    );
                });

        if (importInstruction.getEntityType() != existingImportInstruction.getEntityType()) {
            log.error("Import instruction entity type cannot be updated");
            throw new ImportInstructionsValidationException("Import instruction entity type cannot be updated");
        }

        ImportInstruction resultImportInstruction = importInstructionsRepository
                .mergeAndReturn(existingImportInstruction.patch(importInstruction));

        logAction(resultImportInstruction.getId(), resultImportInstruction.getEntityType(), LogOperation.UPDATE);

        return resultImportInstruction;
    }

    @Transactional
    public void deleteImportInstructionsById(DeleteInstructionsRequest deleteInstructionsRequest) {
        Set<String> instructionsToDelete = Stream.of(
                        deleteInstructionsRequest.getChains().stream(),
                        deleteInstructionsRequest.getServices().stream()
                )
                .flatMap(Function.identity())
                .collect(Collectors.toSet());

        List<ImportInstruction> importInstructions = importInstructionsRepository.findAllById(instructionsToDelete);
        importInstructionsRepository.deleteAll(importInstructions);

        importInstructions.forEach(importInstruction ->
                logAction(importInstruction.getId(), importInstruction.getEntityType(), LogOperation.DELETE)
        );
    }

    private void validateImportInstruction(ImportInstruction importInstruction) {
        ImportEntityType entityType = importInstruction.getEntityType();
        ImportInstructionAction action = importInstruction.getAction();

        boolean failed = false;
        String errorMessage = null;
        switch (entityType) {
            case CHAIN -> {
                failed = action == ImportInstructionAction.OVERRIDE && importInstruction.getOverriddenBy() == null;
                errorMessage = "Overridden By must not be null for instruction with the OVERRIDE action";
            }
            case SERVICE -> {
                failed = action == ImportInstructionAction.OVERRIDE;
                errorMessage = "Service instruction does not support the OVERRIDE action";

            }
            case SPECIFICATION_GROUP -> {
                failed = action == ImportInstructionAction.IGNORE || action == ImportInstructionAction.OVERRIDE;
                errorMessage = "Specification Group instruction does not support action IGNORE and OVERRIDE";
            }
            case SPECIFICATION -> {
                failed = action == ImportInstructionAction.IGNORE || action == ImportInstructionAction.OVERRIDE;
                errorMessage = "Specification instruction does not support action IGNORE and OVERRIDE";
            }
        }

        if (failed) {
            log.error(errorMessage);
            throw new ImportInstructionsValidationException(errorMessage);
        }
    }

    private void logAction(String entityName, ImportEntityType importEntityType, LogOperation logOperation) {
        actionsLogService.logAction(ActionLog.builder()
                .entityName(entityName)
                .parentName(importEntityType != null ? importEntityType.name() : null)
                .entityType(EntityType.IMPORT_INSTRUCTION)
                .operation(logOperation)
                .build());
    }
}
