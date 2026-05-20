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

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction.*;

@Component
public class ChainInstructionsMapper implements ImportInstructionsMapper<ChainImportInstructionsConfig, ChainImportInstructionsDTO> {

    @Override
    public List<ImportInstruction> asEntities(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        if (generalImportInstructionsConfig.getChains() == null) {
            return Collections.emptyList();
        }

        ChainImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getChains();
        List<ImportInstruction> importInstructions = new ArrayList<>();
        importInstructions.addAll(configToEntity(
                instructionsConfig.getIgnore(),
                ImportEntityType.CHAIN,
                IGNORE,
                generalImportInstructionsConfig.getLabels()
        ));
        importInstructions.addAll(overrideConfigToEntities(
                instructionsConfig.getOverride(),
                generalImportInstructionsConfig.getLabels()
        ));
        return importInstructions;
    }

    @Override
    public List<ImportInstruction> asEntitiesIncludingDeletes(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        List<ImportInstruction> importInstructions = new ArrayList<>(asEntities(generalImportInstructionsConfig));
        ChainImportInstructionsConfig instructionsConfig = generalImportInstructionsConfig.getChains();
        importInstructions.addAll(configToEntity(
                instructionsConfig.getDelete(),
                ImportEntityType.CHAIN,
                DELETE,
                generalImportInstructionsConfig.getLabels()
        ));
        return importInstructions;
    }

    @Override
    public ChainImportInstructionsConfig asConfig(@NotNull List<ImportInstruction> importInstructions) {
        Set<String> chainsToDelete = new HashSet<>();
        Set<String> chainsToIgnore = new HashSet<>();
        Set<ChainOverrideConfig> chainsToOverride = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.CHAIN.equals(importInstruction.getEntityType())) {
                continue;
            }

            switch (importInstruction.getAction()) {
                case DELETE:
                    chainsToDelete.add(importInstruction.getId());
                    break;
                case IGNORE:
                    chainsToIgnore.add(importInstruction.getId());
                    break;
                case OVERRIDE:
                    chainsToOverride.add(ChainOverrideConfig.builder()
                            .id(importInstruction.getId())
                            .overriddenBy(importInstruction.getOverriddenBy())
                            .build());
            }
        }

        return ChainImportInstructionsConfig.builder()
                .delete(chainsToDelete)
                .ignore(chainsToIgnore)
                .override(chainsToOverride)
                .build();
    }

    @Override
    public ChainImportInstructionsDTO asDTO(@NotNull List<ImportInstruction> importInstructions) {
        Set<ImportInstructionDTO> chainsToDelete = new HashSet<>();
        Set<ImportInstructionDTO> chainsToIgnore = new HashSet<>();
        Set<ImportInstructionDTO> chainsToOverride = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (!ImportEntityType.CHAIN.equals(importInstruction.getEntityType())) {
                continue;
            }

            switch (importInstruction.getAction()) {
                case DELETE:
                    chainsToDelete.add(entityToDTO(importInstruction));
                    break;
                case IGNORE:
                    chainsToIgnore.add(entityToDTO(importInstruction));
                    break;
                case OVERRIDE:
                    chainsToOverride.add(entityToDTO(importInstruction));
            }
        }
        return ChainImportInstructionsDTO.builder()
                .delete(chainsToDelete)
                .ignore(chainsToIgnore)
                .override(chainsToOverride)
                .build();
    }

    private List<ImportInstruction> overrideConfigToEntities(Collection<ChainOverrideConfig> overrideConfigs, Set<String> labels) {
        return overrideConfigs.stream()
                .map(overrideConfig -> ImportInstruction.builder()
                        .id(overrideConfig.getId())
                        .entityType(ImportEntityType.CHAIN)
                        .action(OVERRIDE)
                        .overriddenBy(overrideConfig.getOverriddenBy())
                        .build())
                .peek(importInstruction -> importInstruction.setLabels(createLabels(labels, importInstruction)))
                .collect(Collectors.toList());
    }
}
