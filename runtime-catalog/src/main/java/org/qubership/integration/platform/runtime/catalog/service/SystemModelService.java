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

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.CompiledLibrary;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SystemModelLabelsRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.SystemModelBaseService;
import org.qubership.integration.platform.catalog.service.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.catalog.service.compiler.CompilerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class SystemModelService extends SystemModelBaseService {

    private final ChainService chainService;

    @Autowired
    public SystemModelService(
            SystemModelRepository systemModelRepository,
            List<SystemModelCodeGenerator> codeGenerators,
            CompilerService compilerService,
            SystemModelLabelsRepository systemModelLabelsRepository,
            ChainService chainService,
            ActionsLogService actionLogger
    ) {
        super(systemModelRepository, codeGenerators, compilerService, systemModelLabelsRepository, actionLogger);
        this.chainService = chainService;
    }

    public SystemModel getSystemModelOrElseNull(String modelId) {
        return systemModelRepository.findById(modelId).orElse(null);
    }

    @Transactional
    public Pair<byte[], String> getCompiledLibrary(String modelId) {
        SystemModel model = getSystemModel(modelId);
        CompiledLibrary compiledLibrary = model.getCompiledLibrary();
        if (isNull(compiledLibrary)) {
            return null;
        }
        String name = compiledLibrary.getName();
        byte[] data = compiledLibrary.getData();
        return isNull(data) ? null : Pair.of(data, name);
    }

    public Optional<SystemModel> deleteSystemModelByIdIfExists(String modelId) {
        Optional<SystemModel> specificationOptional = systemModelRepository.findById(modelId);
        if (specificationOptional.isPresent()) {
            if (chainService.isSystemModelUsedByChain(modelId)) {
                throw new IllegalArgumentException("Specification used by one or more chains");
            }

            SystemModel specification = specificationOptional.get();
            systemModelRepository.delete(specification);
            logModelAction(specification, specification.getSpecificationGroup(), LogOperation.DELETE);
        }

        return specificationOptional;
    }
}
