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
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ServiceInstructionsMapper implements ImportInstructionsMapper<ImportInstructionsConfig, ImportInstructionsDTO> {

    @Override
    public List<ImportInstruction> asEntities(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        if (generalImportInstructionsConfig.getServices() == null) {
            return Collections.emptyList();
        }

        ImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getServices();
        return configToEntity(
                instructionsConfig.getIgnore(),
                ImportEntityType.SERVICE,
                ImportInstructionAction.IGNORE,
                generalImportInstructionsConfig.getLabels()
        );
    }

    @Override
    public List<ImportInstruction> asEntitiesIncludingDeletes(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        List<ImportInstruction> importInstructions = asEntities(generalImportInstructionsConfig);
        ImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getServices();
        importInstructions.addAll(configToEntity(
                instructionsConfig.getDelete(),
                ImportEntityType.SERVICE,
                ImportInstructionAction.DELETE,
                generalImportInstructionsConfig.getLabels()
        ));
        return importInstructions;
    }

    @Override
    public ImportInstructionsConfig asConfig(@NotNull List<ImportInstruction> importInstructions) {
        Set<String> servicesToDelete = new HashSet<>();
        Set<String> servicesToIgnore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.SERVICE.equals(importInstruction.getEntityType())) {
                continue;
            }

            if (ImportInstructionAction.DELETE.equals(importInstruction.getAction())) {
                servicesToDelete.add(importInstruction.getId());
            } else if (ImportInstructionAction.IGNORE.equals(importInstruction.getAction())) {
                servicesToIgnore.add(importInstruction.getId());
            }
        }
        return ImportInstructionsConfig.builder()
                .delete(servicesToDelete)
                .ignore(servicesToIgnore)
                .build();
    }

    @Override
    public ImportInstructionsDTO asDTO(@NotNull List<ImportInstruction> importInstructions) {
        Set<ImportInstructionDTO> servicesToDelete = new HashSet<>();
        Set<ImportInstructionDTO> servicesToIgnore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.SERVICE.equals(importInstruction.getEntityType())) {
                continue;
            }

            if (ImportInstructionAction.DELETE.equals(importInstruction.getAction())) {
                servicesToDelete.add(entityToDTO(importInstruction));
            } else if (ImportInstructionAction.IGNORE.equals(importInstruction.getAction())) {
                servicesToIgnore.add(entityToDTO(importInstruction));
            }
        }
        return ImportInstructionsDTO.builder()
                .delete(servicesToDelete)
                .ignore(servicesToIgnore)
                .build();
    }
}
