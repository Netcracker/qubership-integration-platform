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
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationDeleteException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.runtime.catalog.service.compiler.CompilerService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource.DISCOVERED;

@Slf4j
@Service
public class SystemModelService extends SystemModelBaseService {

    private final ElementHelperService elementHelperService;

    @Autowired
    public SystemModelService(
            SystemModelRepository systemModelRepository,
            List<SystemModelCodeGenerator> codeGenerators,
            CompilerService compilerService,
            SystemModelLabelsRepository systemModelLabelsRepository,
            ElementHelperService elementHelperService,
            ActionsLogService actionLogger
    ) {
        super(systemModelRepository, codeGenerators, compilerService, systemModelLabelsRepository, actionLogger);
        this.elementHelperService = elementHelperService;
    }

    public SystemModel getSystemModelOrElseNull(String modelId) {
        return systemModelRepository.findById(modelId).orElse(null);
    }

    public SystemModel getLatestSystemModel(String systemId) {
        return systemModelRepository.findFirstBySpecificationGroupSystemIdOrderByCreatedWhenDesc(systemId);
    }

    public SystemModel getLastDiscoveredSystemModelInGroup(String specificationGroupId) {
        return systemModelRepository.findFirstBySpecificationGroupIdAndSourceEqualsOrderByCreatedWhenDesc(
                specificationGroupId, DISCOVERED);
    }

    public List<SystemModel> getSystemModelsBySystemId(String systemId) {
        return systemModelRepository.findSystemModelsBySpecificationGroupSystemId(systemId).stream()
                .peek(this::enrichSystemModelWithChains)
                .collect(Collectors.toList());
    }

    @Override
    public List<SystemModel> getSystemModelsBySpecificationGroupId(String specificationGroupId) {
        return super.getSystemModelsBySpecificationGroupId(specificationGroupId).stream()
                .peek(this::enrichSystemModelWithChains)
                .collect(Collectors.toList());
    }

    public SystemModel getSystemModelByVersionAndSpecificationGroupId(String specificationGroupId, String version) {
        return systemModelRepository.findFirstBySpecificationGroupIdAndVersion(specificationGroupId,
                version);
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
            if (elementHelperService.isSystemModelUsedByElement(modelId)) {
                throw new IllegalArgumentException("Specification used by one or more chains");
            }

            SystemModel specification = specificationOptional.get();
            systemModelRepository.delete(specification);
            logModelAction(specification, specification.getSpecificationGroup(), LogOperation.DELETE);
        }

        return specificationOptional;
    }

    /**
     * Modify specification after import
     *
     * @return updated specification
     */
    @Transactional
    public SystemModel partiallyUpdate(SystemModel newModel) {
        SystemModel persistedModel = getSystemModel(newModel.getId());
        if (newModel.getName() != null) {
            persistedModel.setName(newModel.getName());
        }
        if (newModel.getVersion() != null) {
            persistedModel.setVersion(newModel.getVersion());
        }
        replaceLabels(persistedModel, newModel.getLabels());
        SystemModel model = systemModelRepository.save(persistedModel);
        logModelAction(model, model.getSpecificationGroup(), LogOperation.UPDATE);
        return model;
    }

    private void replaceLabels(SystemModel specification, Set<SystemModelLabel> newLabels) {
        if (newLabels == null) {
            return;
        }
        Set<SystemModelLabel> finalNewLabels = newLabels;
        final SystemModel finalSpecification = specification;

        finalNewLabels.forEach(label -> label.setSpecification(finalSpecification));

        // Remove absent labels from db
        specification.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalSpecification.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        newLabels = new HashSet<>(systemModelLabelsRepository.saveAll(finalNewLabels));
        specification.addLabels(newLabels);
    }

    public void deleteSystemModel(SystemModel model) {
        if (!model.isDeprecated()) {
            throw new SpecificationDeleteException("Specification must be deprecated");
        }
        if (elementHelperService.isSystemModelUsedByElement(model.getId())) {
            throw new SpecificationDeleteException("Specification used by one or more chains");
        }

        SpecificationGroup specificationGroup = model.getSpecificationGroup();
        specificationGroup.removeSystemModel(model);
        systemModelRepository.delete(model);
        logModelAction(model, specificationGroup, LogOperation.DELETE);
    }

    private void enrichSystemModelWithChains(SystemModel model) {
        List<Chain> chains = elementHelperService.findBySystemAndModelId(null, model.getId());
        model.setChains(chains);

        for (Operation operation : model.getOperations()) {
            List<Chain> operationChains = chains.stream()
                    .flatMap(operationChain -> operationChain.getElements().stream())
                    .filter(chainElement -> StringUtils.equals(operation.getId(), chainElement.getPropertyAsString(CamelOptions.OPERATION_ID)))
                    .map(ChainElement::getChain)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            operation.setChains(operationChains);
        }
    }
}
