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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions;

import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CommonVariablesInstructionsMapper implements ImportInstructionsMapper<ImportInstructionsConfig, ImportInstructionsDTO> {

    @Override
    public List<ImportInstruction> asEntities(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        if (generalImportInstructionsConfig.getCommonVariables() == null) {
            return Collections.emptyList();
        }

        ImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getCommonVariables();
        return configToEntity(
                instructionsConfig.getIgnore(),
                ImportEntityType.COMMON_VARIABLE,
                ImportInstructionAction.IGNORE,
                generalImportInstructionsConfig.getLabels()
        );
    }

    public List<ImportInstruction> asEntities(@NotNull ImportInstructionsDTO importInstructionsDTO) {
        List<ImportInstruction> importInstructions = new ArrayList<>();
        for (ImportInstructionDTO deleteInstructionDTO : importInstructionsDTO.getDelete()) {
            importInstructions.add(variableInstructionDtoToEntity(deleteInstructionDTO, ImportInstructionAction.DELETE));
        }
        for (ImportInstructionDTO ignoreInstructionDTO : importInstructionsDTO.getIgnore()) {
            importInstructions.add(variableInstructionDtoToEntity(ignoreInstructionDTO, ImportInstructionAction.IGNORE));
        }
        return importInstructions;
    }

    @Override
    public List<ImportInstruction> asEntitiesIncludingDeletes(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        ImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getCommonVariables();
        List<ImportInstruction> importInstructions = new ArrayList<>(asEntities(generalImportInstructionsConfig));
        importInstructions.addAll(configToEntity(
                instructionsConfig.getDelete(),
                ImportEntityType.COMMON_VARIABLE,
                ImportInstructionAction.DELETE,
                generalImportInstructionsConfig.getLabels()
        ));
        return importInstructions;
    }

    @Override
    public ImportInstructionsConfig asConfig(@NotNull List<ImportInstruction> importInstructions) {
        Set<String> variablesToDelete = new HashSet<>();
        Set<String> variablesToIgnore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.COMMON_VARIABLE.equals(importInstruction.getEntityType())) {
                continue;
            }

            if (ImportInstructionAction.DELETE.equals(importInstruction.getAction())) {
                variablesToDelete.add(importInstruction.getId());
            } else if (ImportInstructionAction.IGNORE.equals(importInstruction.getAction())) {
                variablesToIgnore.add(importInstruction.getId());
            }
        }
        return ImportInstructionsConfig.builder()
                .delete(variablesToDelete)
                .ignore(variablesToIgnore)
                .build();
    }

    @Override
    public ImportInstructionsDTO asDTO(@NotNull List<ImportInstruction> importInstructions) {
        Set<ImportInstructionDTO> variablesToDelete = new HashSet<>();
        Set<ImportInstructionDTO> variablesToIgnore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.COMMON_VARIABLE.equals(importInstruction.getEntityType())) {
                continue;
            }

            if (ImportInstructionAction.DELETE.equals(importInstruction.getAction())) {
                variablesToDelete.add(entityToDTO(importInstruction));
            } else if (ImportInstructionAction.IGNORE.equals(importInstruction.getAction())) {
                variablesToIgnore.add(entityToDTO(importInstruction));
            }
        }
        return ImportInstructionsDTO.builder()
                .delete(variablesToDelete)
                .ignore(variablesToIgnore)
                .build();
    }

    private ImportInstruction variableInstructionDtoToEntity(ImportInstructionDTO instructionDTO, ImportInstructionAction action) {
        ImportInstruction importInstruction = ImportInstruction.builder()
                .id(instructionDTO.getId())
                .entityName(instructionDTO.getName())
                .entityType(ImportEntityType.COMMON_VARIABLE)
                .action(action)
                .modifiedWhen(MapperUtils.toTimestamp(instructionDTO.getModifiedWhen()))
                .build();
        importInstruction.setLabels(createLabels(instructionDTO.getLabels(), importInstruction));
        return importInstruction;
    }
}
