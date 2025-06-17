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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroupLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.qubership.integration.platform.runtime.catalog.util.MultipartFileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.DIFFERENT_PROTOCOL_ERROR_MESSAGE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.INVALID_INPUT_FILE_ERROR_MESSAGE;

@Slf4j
@Service
public class SpecificationGroupService extends AbstractSpecificationGroupService {

    private final SystemService systemService;
    private final ProtocolExtractionService protocolExtractionService;
    private final ElementHelperService elementHelperService;

    @Autowired
    public SpecificationGroupService(
            SpecificationGroupRepository specificationGroupRepository,
            ActionsLogService actionLogger,
            SystemService systemService,
            ProtocolExtractionService protocolExtractionService,
            SpecificationGroupLabelsRepository specificationGroupLabelsRepository,
            ElementHelperService elementHelperService
    ) {
        super(specificationGroupRepository, actionLogger, specificationGroupLabelsRepository);
        this.systemService = systemService;
        this.protocolExtractionService = protocolExtractionService;
        this.elementHelperService = elementHelperService;
    }

    public SpecificationGroup createAndSaveSpecificationGroupWithProtocol(IntegrationSystem system,
                                                                          String specificationName,
                                                                          String protocol,
                                                                          MultipartFile[] files,
                                                                          String specificationUrl) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (specificationGroupRepository.findByNameAndSystem(specificationName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            setSystemProtocol(system, protocol, files);

            SpecificationGroup specificationGroup = new SpecificationGroup();
            specificationGroup.setId(buildSpecificationGroupId(system, specificationName));
            specificationGroup.setName(specificationName);
            specificationGroup.setUrl(specificationUrl);

            specificationGroup = specificationGroupRepository.save(specificationGroup);

            system.addSpecificationGroup(specificationGroup);

            systemService.update(system, false);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public Optional<SpecificationGroup> deleteByIdExists(String specificationGroupId) {
        Optional<SpecificationGroup> specificationGroupOptional = specificationGroupRepository.findById(specificationGroupId);
        if (specificationGroupOptional.isPresent()) {
            if (elementHelperService.isSystemModelUsedByElement(specificationGroupId)) {
                throw new IllegalArgumentException("Specification group used by one or more chains");
            }

            SpecificationGroup specificationGroup = specificationGroupOptional.get();
            specificationGroupRepository.delete(specificationGroup);
            logSpecGroupAction(specificationGroup, specificationGroup.getSystem(), LogOperation.DELETE);
            return Optional.of(specificationGroup);
        }

        return Optional.empty();
    }

    private void setSystemProtocol(IntegrationSystem system, String protocol, MultipartFile[] files) {
        OperationProtocol operationProtocol;

        try {
            if (system.getProtocol() == null) {
                if (StringUtils.isBlank(protocol)) {
                    operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));
                } else {
                    operationProtocol = OperationProtocol.fromValue(protocol);
                }

                if (isNull(operationProtocol)) {
                    throw new SpecificationImportException("Unsupported protocol: " + protocol);
                } else {
                    systemService.validateSpecificationProtocol(system, operationProtocol);
                    system.setProtocol(operationProtocol);
                }
            } else {
                operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));

                if (operationProtocol != null && !system.getProtocol().equals(operationProtocol)) {
                    throw new SpecificationImportException(DIFFERENT_PROTOCOL_ERROR_MESSAGE);
                }
            }
        } catch (IOException exception) {
            throw new SpecificationImportException(INVALID_INPUT_FILE_ERROR_MESSAGE, exception);
        }
    }

    public SpecificationGroup createAndSaveSpecificationGroup(String systemId,
                                                              String specificationName,
                                                              String protocol,
                                                              MultipartFile[] files) {
        return createAndSaveSpecificationGroupWithProtocol(systemService.getByIdOrNull(systemId), specificationName, protocol, files, null);
    }

    public SpecificationGroup createAndSaveSpecificationGroup(IntegrationSystem system,
                                                              String specificationId,
                                                              String specificationName,
                                                              String specificationType,
                                                              String specificationUrl,
                                                              Boolean synchronization) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (specificationGroupRepository.findByNameAndSystem(specificationName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            SpecificationGroup specificationGroup = new SpecificationGroup();
            specificationGroup.setName(specificationName);
            specificationGroup.setId(specificationId);
            specificationGroup.setUrl(specificationUrl);
            specificationGroup.setSynchronization(synchronization);

            specificationGroup = specificationGroupRepository.save(specificationGroup);

            system.addSpecificationGroup(specificationGroup);

            system.setProtocol(protocolExtractionService.getProtocol(specificationType));
            systemService.update(system);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public SpecificationGroup createAndSaveSpecificationGroup(
            String systemId,
            String name,
            String description,
            String url,
            boolean synchronization) {
        return createAndSaveSpecificationGroup(systemService.getByIdOrNull(systemId), name, description, url, synchronization);
    }

    public SpecificationGroup createAndSaveSpecificationGroup(
            IntegrationSystem system,
            String groupName,
            String description,
            String url,
            boolean synchronization
    ) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (specificationGroupRepository.findByNameAndSystem(groupName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            SpecificationGroup specificationGroup = new SpecificationGroup();
            specificationGroup.setId(buildSpecificationGroupId(system, groupName));
            specificationGroup.setName(groupName);
            specificationGroup.setDescription(description);
            specificationGroup.setUrl(url);
            specificationGroup.setSynchronization(synchronization);

            specificationGroup = specificationGroupRepository.save(specificationGroup);

            system.addSpecificationGroup(specificationGroup);

            systemService.update(system);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public SpecificationGroup createAndSaveUniqueSpecificationGroup(IntegrationSystem system,
                                                                    String specificationName,
                                                                    String specificationType,
                                                                    String specificationUrl,
                                                                    Boolean synchronization) {
        String name = getUniqueName(system, specificationName);
        String id = buildSpecificationGroupId(system, specificationName);
        if (specGroupWithIdExists(system.getSpecificationGroups(), id)) {
            id = buildSpecificationGroupId(system, name);
        }
        return createAndSaveSpecificationGroup(system, id, name, specificationType, specificationUrl, synchronization);
    }

    public SpecificationGroup getSpecificationGroupBySystemIdAndUrl(String systemId, String url) {
        try {
            return specificationGroupRepository.findBySystemIdAndUrl(systemId, url);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw new DuplicateKeyException("Not unique specification group url found: " + url, exception);
        }
    }

    public SpecificationGroup getSpecificationGroupByNameAndSystem(String specificationGroupName, IntegrationSystem system) {
        try {
            return specificationGroupRepository.findByNameAndSystem(specificationGroupName, system);
        } catch (IncorrectResultSizeDataAccessException exception) {
            log.error("Not unique specification group name {}, for system {}", specificationGroupName, system.getName());
            throw new DuplicateKeyException("Not unique specification group name found: " + specificationGroupName, exception);
        }
    }

    public List<SpecificationGroup> getSpecificationGroups(String systemId) {
        List<SpecificationGroup> specificationGroups = specificationGroupRepository.findAllBySystemId(systemId);
        List<SpecificationGroup> specificationGroupsSorted = specificationGroups.stream()
                .peek(this::enrichSpecificationGroupWithChains)
                .sorted((sg1, sg2) -> sg2.getName().compareTo(sg1.getName()))
                .collect(Collectors.toList());

        specificationGroupsSorted.forEach(specificationGroup -> specificationGroup.getSystemModels().sort(Comparator.comparing(SystemModel::getVersion)));
        return specificationGroupsSorted;
    }

    public void delete(String specificationGroupId) {
        if (elementHelperService.isSystemModelUsedByElement(specificationGroupId)) {
            throw new SpecificationDeleteException("Specification group used by one or more chains");
        }

        SpecificationGroup specificationGroup = specificationGroupRepository.getReferenceById(specificationGroupId);
        IntegrationSystem system = specificationGroup.getSystem();

        specificationGroupRepository.delete(specificationGroup);
        system.removeSpecificationGroup(specificationGroup);

        logSpecGroupAction(specificationGroup, system, LogOperation.DELETE);
    }

    public SpecificationGroup update(SpecificationGroup specificationGroup) {
        return update(specificationGroup, null);
    }

    public SpecificationGroup update(SpecificationGroup specificationGroup, List<SpecificationGroupLabel> newLabels) {
        replaceLabels(specificationGroup, newLabels);
        specificationGroup = specificationGroupRepository.save(specificationGroup);
        logSpecGroupAction(specificationGroup, specificationGroup.getSystem(), LogOperation.UPDATE);
        return specificationGroup;
    }

    public void replaceLabels(SpecificationGroup specificationGroup, List<SpecificationGroupLabel> newLabels) {
        if (newLabels == null) {
            return;
        }
        List<SpecificationGroupLabel> finalNewLabels = newLabels;
        final SpecificationGroup finalSpecificationGroup = specificationGroup;

        finalNewLabels.forEach(label -> label.setSpecificationGroup(finalSpecificationGroup));

        // Remove absent labels from db
        specificationGroup.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalSpecificationGroup.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        newLabels = specificationGroupLabelsRepository.saveAll(finalNewLabels);
        specificationGroup.addLabels(newLabels);
    }

    public String getUniqueName(IntegrationSystem system, String desiredName) {
        String newName = desiredName;
        int iterator = 0;
        while (specGroupWithNameExists(system.getSpecificationGroups(), newName)) {
            iterator++;
            newName = desiredName + " (" + iterator + ")";
        }
        return newName;
    }

    private boolean specGroupWithNameExists(Collection<SpecificationGroup> specGroups, String name) {
        if (specGroups == null) {
            return false;
        }
        return specGroups.stream().anyMatch(specGroup ->
                name.equals(specGroup.getName()));
    }

    private boolean specGroupWithIdExists(Collection<SpecificationGroup> specificationGroups, String id) {
        return nonNull(specificationGroups)
               && specificationGroups.stream().map(SpecificationGroup::getId).anyMatch(id::equals);
    }

    private void enrichSpecificationGroupWithChains(SpecificationGroup specificationGroup) {
        List<Chain> chains = elementHelperService.findBySystemAndGroupId(specificationGroup.getSystem().getId(), specificationGroup.getId());
        specificationGroup.setChains(chains);

        for (SystemModel model : specificationGroup.getSystemModels()) {
            List<Chain> modelChains = chains.stream()
                    .flatMap(modelChain -> modelChain.getElements().stream())
                    .filter(chainElement -> StringUtils.equals(model.getId(), chainElement.getPropertyAsString(CamelOptions.MODEL_ID)))
                    .map(ChainElement::getChain)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            model.setChains(modelChains);
        }
    }
}
