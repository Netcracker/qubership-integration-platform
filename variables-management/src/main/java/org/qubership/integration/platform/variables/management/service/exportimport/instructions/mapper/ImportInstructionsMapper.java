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

package org.qubership.integration.platform.variables.management.service.exportimport.instructions.mapper;

import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.CommonVariablesImportInstructionsConfig;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions.ImportInstruction;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions.ImportInstructionLabel;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions.ImportInstructionDTO;
import org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions.ImportInstructionsDTO;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ImportInstructionsMapper {

    public ImportInstructionsConfig asConfig(List<ImportInstruction> importInstructions) {
        Set<String> delete = new HashSet<>();
        Set<String> ignore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (importInstruction.getAction() == ImportInstructionAction.DELETE) {
                delete.add(importInstruction.getId());
            } else if (importInstruction.getAction() == ImportInstructionAction.IGNORE) {
                ignore.add(importInstruction.getId());
            }
        }
        return ImportInstructionsConfig.builder()
                .delete(delete)
                .ignore(ignore)
                .build();
    }

    public List<ImportInstruction> asEntities(CommonVariablesImportInstructionsConfig commonVariablesImportInstructionsConfig) {
        ImportInstructionsConfig importInstructionsConfig = commonVariablesImportInstructionsConfig.getCommonVariables();
        if (importInstructionsConfig == null) {
            return Collections.emptyList();
        }

        List<ImportInstruction> importInstructions = new ArrayList<>();
        for (String id : importInstructionsConfig.getIgnore()) {
            ImportInstruction importInstruction = ImportInstruction.builder()
                    .id(id)
                    .action(ImportInstructionAction.IGNORE)
                    .build();
            if (CollectionUtils.isNotEmpty(commonVariablesImportInstructionsConfig.getLabels())) {
                importInstruction.setLabels(
                        commonVariablesImportInstructionsConfig.getLabels().stream()
                                .map(labelName -> ImportInstructionLabel.builder()
                                        .name(labelName)
                                        .importInstruction(importInstruction)
                                        .build())
                                .collect(Collectors.toList())
                );
            }
            importInstructions.add(importInstruction);
        }
        return importInstructions;
    }

    public ImportInstructionsDTO asDto(List<ImportInstruction> importInstructions) {
        Set<ImportInstructionDTO> variablesToDelete = new HashSet<>();
        Set<ImportInstructionDTO> variablesToIgnore = new HashSet<>();
        for (ImportInstruction importInstruction : importInstructions) {
            if (ImportInstructionAction.DELETE.equals(importInstruction.getAction())) {
                variablesToDelete.add(entityToDto(importInstruction));
            } else if (ImportInstructionAction.IGNORE.equals(importInstruction.getAction())) {
                variablesToIgnore.add(entityToDto(importInstruction));
            }
        }
        return ImportInstructionsDTO.builder()
                .delete(variablesToDelete)
                .ignore(variablesToIgnore)
                .build();
    }

    public ImportInstructionDTO entityToDto(ImportInstruction importInstruction) {
        Long modifiedWhen = importInstruction.getModifiedWhen() != null
                ? importInstruction.getModifiedWhen().getTime()
                : null;
        Set<String> labels = CollectionUtils.isEmpty(importInstruction.getLabels())
                ? Collections.emptySet()
                : importInstruction.getLabels().stream()
                        .map(ImportInstructionLabel::getName)
                        .collect(Collectors.toSet());
        return ImportInstructionDTO.builder()
                .id(importInstruction.getId())
                .labels(labels)
                .modifiedWhen(modifiedWhen)
                .preview(modifiedWhen == null)
                .build();
    }
}
