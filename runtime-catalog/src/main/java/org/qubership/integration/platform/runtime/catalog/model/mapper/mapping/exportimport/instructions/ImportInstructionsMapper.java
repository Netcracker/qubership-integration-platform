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

import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportEntityType;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionDTO;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstructionLabel;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.lang.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface ImportInstructionsMapper<ConfigType, DTOType> {

    List<ImportInstruction> asEntities(@NonNull GeneralImportInstructionsConfig generalImportInstructionsConfig);

    List<ImportInstruction> asEntitiesIncludingDeletes(@NonNull GeneralImportInstructionsConfig generalImportInstructionsConfig);

    ConfigType asConfig(@NonNull List<ImportInstruction> importInstructions);

    DTOType asDTO(@NonNull List<ImportInstruction> importInstructions);

    default List<ImportInstruction> configToEntity(
            Collection<String> ids,
            ImportEntityType entityType,
            ImportInstructionAction action,
            Set<String> labels
    ) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return ids.stream()
                .map(id -> ImportInstruction.builder()
                        .id(id)
                        .entityType(entityType)
                        .action(action)
                        .build())
                .peek(importInstruction -> importInstruction.setLabels(createLabels(labels, importInstruction)))
                .collect(Collectors.toList());
    }

    default ImportInstructionDTO entityToDTO(ImportInstruction importInstruction) {
        return ImportInstructionDTO.builder()
                .id(importInstruction.getId())
                .name(importInstruction.getEntityName())
                .overriddenById(importInstruction.getOverriddenBy())
                .overriddenByName(importInstruction.getOverriddenByName())
                .labels(importInstruction.getLabels().stream().map(ImportInstructionLabel::getName).collect(Collectors.toSet()))
                .modifiedWhen(MapperUtils.fromTimestamp(importInstruction.getModifiedWhen()))
                .preview(importInstruction.getModifiedWhen() == null)
                .build();
    }

    default List<ImportInstructionLabel> createLabels(Set<String> labels, ImportInstruction importInstruction) {
        if (CollectionUtils.isEmpty(labels)) {
            return Collections.emptyList();
        }

        return labels.stream()
                .map(label -> ImportInstructionLabel.builder().name(label).importInstruction(importInstruction).build())
                .collect(Collectors.toList());
    }
}
