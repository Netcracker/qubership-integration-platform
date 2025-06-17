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
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GeneralInstructionsMapper implements ImportInstructionsMapper<GeneralImportInstructionsConfig, GeneralImportInstructionsDTO> {

    private final ChainInstructionsMapper chainInstructionsMapper;
    private final ServiceInstructionsMapper serviceInstructionsMapper;
    private final SpecificationGroupInstructionsMapper specificationGroupInstructionsMapper;
    private final SpecificationInstructionsMapper specificationInstructionsMapper;
    private final CommonVariablesInstructionsMapper commonVariablesInstructionsMapper;

    @Autowired
    public GeneralInstructionsMapper(
            ChainInstructionsMapper chainInstructionsMapper,
            ServiceInstructionsMapper serviceInstructionsMapper,
            SpecificationGroupInstructionsMapper specificationGroupInstructionsMapper,
            SpecificationInstructionsMapper specificationInstructionsMapper,
            CommonVariablesInstructionsMapper commonVariablesInstructionsMapper
    ) {
        this.chainInstructionsMapper = chainInstructionsMapper;
        this.serviceInstructionsMapper = serviceInstructionsMapper;
        this.specificationGroupInstructionsMapper = specificationGroupInstructionsMapper;
        this.specificationInstructionsMapper = specificationInstructionsMapper;
        this.commonVariablesInstructionsMapper = commonVariablesInstructionsMapper;
    }

    @Override
    public List<ImportInstruction> asEntities(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        List<ImportInstruction> importInstructions = new ArrayList<>();
        importInstructions.addAll(chainInstructionsMapper.asEntities(generalImportInstructionsConfig));
        importInstructions.addAll(serviceInstructionsMapper.asEntities(generalImportInstructionsConfig));
        return importInstructions;
    }

    @Override
    public List<ImportInstruction> asEntitiesIncludingDeletes(@NotNull GeneralImportInstructionsConfig generalImportInstructionsConfig) {
        List<ImportInstruction> importInstructions = new ArrayList<>();
        importInstructions.addAll(chainInstructionsMapper.asEntitiesIncludingDeletes(generalImportInstructionsConfig));
        importInstructions.addAll(serviceInstructionsMapper.asEntitiesIncludingDeletes(generalImportInstructionsConfig));
        importInstructions.addAll(specificationGroupInstructionsMapper.asEntitiesIncludingDeletes(generalImportInstructionsConfig));
        importInstructions.addAll(specificationInstructionsMapper.asEntitiesIncludingDeletes(generalImportInstructionsConfig));
        importInstructions.addAll(commonVariablesInstructionsMapper.asEntitiesIncludingDeletes(generalImportInstructionsConfig));
        return importInstructions;
    }

    @Override
    public GeneralImportInstructionsConfig asConfig(@NotNull List<ImportInstruction> importInstructions) {
        return GeneralImportInstructionsConfig.builder()
                .chains(chainInstructionsMapper.asConfig(importInstructions))
                .services(serviceInstructionsMapper.asConfig(importInstructions))
                .specificationGroups(specificationGroupInstructionsMapper.asConfig(importInstructions))
                .specifications(specificationInstructionsMapper.asConfig(importInstructions))
                .build();
    }

    @Override
    public GeneralImportInstructionsDTO asDTO(@NotNull List<ImportInstruction> importInstructions) {
        return GeneralImportInstructionsDTO.builder()
                .chains(chainInstructionsMapper.asDTO(importInstructions))
                .services(serviceInstructionsMapper.asDTO(importInstructions))
                .specificationGroups(specificationGroupInstructionsMapper.asDTO(importInstructions))
                .specifications(specificationInstructionsMapper.asDTO(importInstructions))
                .commonVariables(commonVariablesInstructionsMapper.asDTO(importInstructions))
                .build();
    }
}
